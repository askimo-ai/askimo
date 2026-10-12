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
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams a large (potentially multi-GB) GET download directly to disk with progress callbacks —
 * shared by [io.askimo.core.providers.llama.LlamaServerDownloader] and
 * [io.askimo.core.providers.llama.LlamaModelDownloader], which previously duplicated this exact
 * client-building/buffer-loop/truncation-check logic.
 *
 * Deliberately **not** built on [httpGet]/[httpPost]: those buffer the entire response into a
 * `ByteArray` before returning, which is fine for small JSON/text payloads but would mean loading
 * a whole `llama-server` archive or `.gguf` model into RAM before a single byte reaches disk, and
 * would lose the ability to report incremental progress as bytes arrive.
 */
object StreamingDownloader {

    private val log = logger<StreamingDownloader>()

    /** How often the inactivity watchdog checks for a stalled read — see [download]. */
    private const val WATCHDOG_POLL_INTERVAL_MS = 1000L

    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Downloads [url] to [destPath] via a proxy-aware streaming GET, reporting progress through
     * [onProgress] as `(bytesDownloaded, totalBytes)` (`totalBytes` is `-1` if the server didn't
     * send a `Content-Length`). Deletes a partially-written [destPath] on any failure —
     * connection error, non-2xx response, mid-stream cancellation, or a transfer that completed
     * short of the advertised `Content-Length` (a flaky proxy/CDN silently closing the socket) —
     * so a failed attempt never looks like a complete file to a caller's `isDownloaded()`-style
     * check. Callers needing an atomic "all-or-nothing" final file (e.g. [destPath] being a
     * sibling `.part` file) should `Files.move(..., ATOMIC_MOVE)` into place themselves only
     * after this returns successfully.
     *
     * Coroutine cancellation still deletes the partial file but rethrows [CancellationException]
     * unchanged, not wrapped in [DownloadException] — it's not an operational failure, and
     * wrapping it would break structured concurrency's cancellation propagation.
     *
     * [connectErrorMessage]/[httpErrorMessage]/[incompleteMessage] let each call site keep its
     * own specific, actionable wording (mentioning the right upstream service/catalog) instead of
     * a generic one-size-fits-all message, while still sharing the actual download mechanics.
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
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            // A retry can reuse the same destPath as a prior interrupted attempt — clean up any
            // leftover partial file here too (not just in the read-loop below), so a connect
            // failure never leaves a stale multi-GB file masquerading as downloaded.
            runCatching { Files.deleteIfExists(destPath) }
            throw DownloadException(connectErrorMessage(e.message ?: e.toString()), e)
        }

        if (response.statusCode() !in 200..299) {
            response.body().close()
            // Same cleanup as above — a non-2xx response must not leave a stale partial file.
            runCatching { Files.deleteIfExists(destPath) }
            throw DownloadException(httpErrorMessage(response.statusCode()))
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var downloadedBytes = 0L
        val input = response.body()

        // input.read() is a blocking call, not a suspension point — it can neither observe
        // coroutine cancellation nor be bounded by readTimeoutMs (HttpRequest.timeout only
        // covers waiting for the response headers, not consuming an ofInputStream() body
        // afterward). A stalled peer that stops sending bytes after headers would otherwise
        // hang this download forever. Closing `input` from another thread is the documented
        // way to unblock a pending read with an IOException, so a watchdog thread does that
        // on inactivity, and a Job completion handler does the same on cancellation.
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause -> if (cause != null) input.close() }
        val watchdog = Thread({
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(WATCHDOG_POLL_INTERVAL_MS)
                if (System.currentTimeMillis() - lastActivityAt.get() > readTimeoutMs) {
                    input.close()
                    break
                }
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
            // Normal coroutine cancellation, not a download failure — must not be wrapped in
            // DownloadException, or callers would treat it as an operational error and break
            // cancellation propagation. Clean up the partial file, then rethrow unchanged.
            Files.deleteIfExists(destPath)
            throw e
        } catch (e: Exception) {
            Files.deleteIfExists(destPath)
            throw DownloadException("Download from $url stalled, failed, or was cancelled: ${e.message}", e)
        } finally {
            watchdog.interrupt()
            cancelHandle?.dispose()
        }

        // Catches a connection that silently dropped mid-stream (e.g. flaky proxy/CDN closing
        // the socket without an error) — without this, a truncated transfer would still "succeed"
        // here, leaving the caller to find out only later/opaquely once it tries to use the file.
        if (totalBytes > 0 && downloadedBytes != totalBytes) {
            Files.deleteIfExists(destPath)
            throw DownloadException(incompleteMessage(downloadedBytes, totalBytes))
        }

        log.info("Downloaded {} bytes from {} to {}", downloadedBytes, url, destPath)
        downloadedBytes
    }
}
