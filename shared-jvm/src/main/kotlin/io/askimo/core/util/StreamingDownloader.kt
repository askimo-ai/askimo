/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import io.askimo.core.logging.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException

/**
 * Streams a large (potentially multi-GB) GET download directly to disk with progress callbacks —
 * shared by [io.askimo.core.providers.llama.LlamaServerDownloader] and
 * [io.askimo.core.providers.llama.LlamaModelDownloader].
 *
 * Not built on [httpGet]/[httpPost]: those buffer the whole response into memory first, which is
 * fine for small JSON/text payloads but unworkable for multi-GB files with progress reporting.
 */
object StreamingDownloader {

    private val log = logger<StreamingDownloader>()

    /** How often the inactivity watchdog checks for a stalled read — see [download]. */
    private const val WATCHDOG_POLL_INTERVAL_MS = 1000L

    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Downloads [url] to [destPath] via a proxy-aware streaming GET, reporting progress through
     * [onProgress] as `(bytesDownloaded, totalBytes)` (`totalBytes` is `-1` if the server didn't
     * send a `Content-Length`). Deletes a partially-written [destPath] on any failure — connect
     * error, non-2xx response, cancellation, or a short transfer — so a failed attempt never
     * looks complete to a caller's `isDownloaded()`-style check. For an atomic final file, write
     * to a sibling `.part` path and `Files.move(..., ATOMIC_MOVE)` it into place after success.
     *
     * Cancellation still deletes the partial file but rethrows [CancellationException] unchanged
     * rather than wrapping it in [DownloadException], to preserve structured concurrency.
     *
     * [connectErrorMessage]/[httpErrorMessage]/[incompleteMessage] let each call site customize
     * wording while sharing the same download mechanics.
     *
     * @return the number of bytes downloaded (== the file size written to [destPath]).
     * @throws DownloadException on connection failure, non-2xx response, or an incomplete transfer.
     * @throws CancellationException if the calling coroutine is cancelled mid-transfer.
     */
    suspend fun download(
        url: String,
        destPath: Path,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        connectErrorMessage: (String) -> String = { msg -> "Failed to connect to $url: $msg" },
        httpErrorMessage: (Int) -> String = { status -> "Download failed (HTTP $status) from $url." },
        incompleteMessage: (downloadedBytes: Long, totalBytes: Long) -> String = { downloaded, total ->
            "Download from $url appears incomplete ($downloaded of $total bytes received)."
        },
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val client = ProxyUtil.configureProxy(HttpClient.newBuilder(), url)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofMillis(readTimeoutMs))
            .GET()
            .build()

        val response = try {
            sendCancellable(client, request)
        } catch (e: CancellationException) {
            runCatching { Files.deleteIfExists(destPath) }
            throw e
        } catch (e: Exception) {
            // Clean up any leftover partial file from a prior attempt reusing this destPath.
            runCatching { Files.deleteIfExists(destPath) }
            throw DownloadException(connectErrorMessage(e.message ?: e.toString()), e)
        }

        if (response.statusCode() !in 200..299) {
            response.body().close()
            runCatching { Files.deleteIfExists(destPath) }
            throw DownloadException(httpErrorMessage(response.statusCode()))
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var downloadedBytes = 0L
        val input = response.body()

        // input.read() is a blocking call — it can't observe coroutine cancellation and isn't
        // bounded by readTimeoutMs (HttpRequest.timeout only covers waiting for headers, not
        // reading the body). Closing `input` from another thread unblocks a pending read with an
        // IOException, so the watchdog below polls for inactivity or job cancellation and closes
        // the stream the moment either is true.
        //
        // Polling instead of Job.invokeOnCompletion: that handler only fires once the job fully
        // completes, but the job can't complete until the read loop breaks — which needs the
        // handler to run first, i.e. a deadlock. (The earlier-firing `onCancelling = true`
        // variant is an internal API, so it's not used.)
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        val job = currentCoroutineContext()[Job]
        val watchdog = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(WATCHDOG_POLL_INTERVAL_MS)
                    val timedOut = System.currentTimeMillis() - lastActivityAt.get() > readTimeoutMs
                    val cancelled = job?.isActive == false
                    if (timedOut || cancelled) {
                        input.close()
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown: watchdog.interrupt() below wakes this thread once the
                // download finishes on its own. Without this catch it'd surface as a harmless
                // but noisy uncaught-exception stack trace.
                Thread.currentThread().interrupt()
            }
        }, "streaming-download-watchdog").apply { isDaemon = true }
        watchdog.start()

        try {
            input.use { stream ->
                Files.newOutputStream(destPath).use { output ->
                    val buffer = ByteArray(1 shl 20) // 1 MiB
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        lastActivityAt.set(System.currentTimeMillis())
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
        } catch (e: CancellationException) {
            // Real cancellation, not a failure — don't wrap it, or cancellation propagation breaks.
            Files.deleteIfExists(destPath)
            throw e
        } catch (e: Exception) {
            Files.deleteIfExists(destPath)
            // If the watchdog closed `input` due to job cancellation (not a timeout), the blocked
            // read() surfaces as a plain IOException rather than CancellationException. Re-wrap
            // it so callers still see a real cancellation instead of a misleading failure.
            if (job?.isActive == false) {
                throw CancellationException("Download from $url was cancelled", e)
            }
            throw DownloadException("Download from $url stalled, failed, or was cancelled: ${e.message}", e)
        } finally {
            watchdog.interrupt()
        }

        // Guards against a connection that silently dropped mid-stream without an error.
        if (totalBytes > 0 && downloadedBytes != totalBytes) {
            Files.deleteIfExists(destPath)
            throw DownloadException(incompleteMessage(downloadedBytes, totalBytes))
        }

        log.info("Downloaded {} bytes from {} to {}", downloadedBytes, url, destPath)
        downloadedBytes
    }

    /**
     * Bridges [HttpClient.sendAsync] into a cancellable suspend call — cancelling the calling
     * coroutine cancels the underlying [java.util.concurrent.CompletableFuture], which actually
     * aborts the in-flight HTTP exchange instead of merely abandoning a wait on the blocking
     * [HttpClient.send]. See the call site in [download] for why this matters specifically for
     * the connect/header-wait phase, which the body-read watchdog doesn't cover.
     */
    private suspend fun sendCancellable(
        client: HttpClient,
        request: HttpRequest,
    ): HttpResponse<java.io.InputStream> = suspendCancellableCoroutine { cont ->
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        cont.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { result, error ->
            // Guards against a double-resume: once invokeOnCancellation fires, the continuation
            // is no longer active, but cancelling `future` still completes it (exceptionally),
            // which would otherwise re-enter this callback. If the future instead completed
            // successfully (completion won the race), close its body since no one will read it.
            //
            // @Suppress: .close() runs on the future's own completion thread, not a coroutine
            // dispatcher — the IDE's warning here is a false positive from this lambda's textual
            // nesting inside a suspend fun.
            @Suppress("BlockingMethodInNonBlockingContext")
            if (!cont.isActive) {
                result?.body()?.close()
                return@whenComplete
            }
            if (error != null) {
                cont.resumeWithException(error)
            } else {
                // Uses the resource-aware resume overload: if cancellation wins the narrower race
                // between this isActive check and delivery of the resumed value, onCancellation
                // closes the body that would otherwise never be read/closed by anyone.
                cont.resume(result) { _, _, _ -> result.body().close() }
            }
        }
    }
}
