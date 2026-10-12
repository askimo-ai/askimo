/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.StreamingDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Downloads a [LlamaModelEntry]'s `.gguf` file to [io.askimo.core.util.AskimoHome.llamaModelsDir],
 * streaming directly to disk with progress callbacks for the Settings UI.
 */
object LlamaModelDownloader {

    private val log = logger<LlamaModelDownloader>()

    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Resolves [entry]'s `.gguf` file under [AskimoHome.llamaModelsDir] — the single chokepoint
     * every other function here goes through. [LlamaModelEntry.fileName] is partly sourced from
     * Hugging Face metadata, which only sanitizes `/` (not `\`, which Windows treats as a
     * separator) — normalize and require the result stay a direct child of the models dir.
     */
    fun modelPath(entry: LlamaModelEntry): Path {
        val base = AskimoHome.llamaModelsDir().toAbsolutePath().normalize()
        val resolved = base.resolve(entry.fileName).normalize()
        require(resolved.parent == base) { "Refusing to resolve unsafe model file name: ${entry.fileName}" }
        return resolved
    }

    fun isDownloaded(entry: LlamaModelEntry): Boolean = modelPath(entry).let { Files.exists(it) && Files.size(it) > 0 }

    /** Lists the catalog entries whose `.gguf` file is already present on disk. */
    fun listDownloaded(): List<LlamaModelEntry> = LlamaModelCatalog.allEntries.filter { isDownloaded(it) }

    /**
     * Sanity-checks [entry]'s downloaded file size against [LlamaModelEntry.approxSizeBytes] to
     * catch truncated/corrupted downloads before handing the file to `llama-server`, which
     * otherwise fails with an opaque "exited before becoming healthy" error instead of a clear one.
     *
     * Intentionally loose (50%–300% of the approximate size) since Hugging Face doesn't publish
     * per-file checksums — this is a corruption safety net, not a precise integrity check.
     * Entries with unknown size skip the ratio check but still catch the zero-byte case.
     *
     * @return a user-facing error message if the file looks corrupted, else `null`.
     */
    fun validationError(entry: LlamaModelEntry): String? {
        val path = modelPath(entry)
        if (!Files.exists(path)) return null
        val actualBytes = Files.size(path)
        if (actualBytes == 0L) {
            return "Model file \"${entry.fileName}\" is empty (0 bytes) — likely an interrupted download. " +
                "Delete and re-download it from Settings > Local LLM."
        }
        val expectedBytes = entry.approxSizeBytes
        if (expectedBytes <= 0L) return null
        val ratio = actualBytes.toDouble() / expectedBytes.toDouble()
        if (ratio < 0.5 || ratio > 3.0) {
            val actualMb = actualBytes / (1024 * 1024)
            val expectedMb = expectedBytes / (1024 * 1024)
            return "Model file \"${entry.fileName}\" looks corrupted or incomplete " +
                "($actualMb MB on disk, expected ~$expectedMb MB). Delete and re-download it from Settings > Local LLM."
        }
        return null
    }

    /**
     * Downloads [entry] if not already present, reporting progress via [onProgress] as
     * `(bytesDownloaded, totalBytes)`. Downloads to a sibling `.part` file first, then atomically
     * moves it into place on success, after which [validationError] is re-checked against the
     * final file — a corrupted/wrong-sized file is deleted rather than left looking downloaded.
     *
     * @throws DownloadException on network failure, non-2xx response, or failed [validationError] check.
     */
    suspend fun download(
        entry: LlamaModelEntry,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        val finalPath = modelPath(entry)
        if (Files.exists(finalPath) && Files.size(finalPath) > 0) {
            return@withContext finalPath
        }

        Files.createDirectories(AskimoHome.llamaModelsDir())
        val partPath = finalPath.resolveSibling("${entry.fileName}.part")

        log.info("Downloading llama model {} from {}", entry.fileName, entry.downloadUrl)
        val downloadedBytes = try {
            StreamingDownloader.download(
                url = entry.downloadUrl,
                destPath = partPath,
                connectTimeoutMs = Duration.ofSeconds(30).toMillis(),
                readTimeoutMs = Duration.ofHours(4).toMillis(),
                connectErrorMessage = { msg -> "Failed to connect to Hugging Face to download ${entry.fileName}: $msg" },
                httpErrorMessage = { status ->
                    "Downloading ${entry.fileName} failed (HTTP $status). The Hugging Face repo " +
                        "file layout may have changed — see LlamaModelCatalog."
                },
                incompleteMessage = { downloaded, total ->
                    "Download of ${entry.fileName} appears incomplete ($downloaded of $total bytes received). " +
                        "Please try downloading again."
                },
                onProgress = onProgress,
            )
        } catch (e: StreamingDownloader.DownloadException) {
            throw DownloadException(e.message ?: "Download of ${entry.fileName} failed or was cancelled.", e)
        }

        // ATOMIC_MOVE is optional per the NIO.2 spec — some filesystems don't support it and
        // throw AtomicMoveNotSupportedException, which would otherwise turn an already-successful
        // multi-GB download into a failure right at the last step.
        try {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
        }

        // A non-2xx response is already rejected by StreamingDownloader, but a *successful* HTTP
        // 200 whose body is actually an HTML/JSON error page (or otherwise wrong-sized — e.g. a
        // rate-limit or LFS-pointer response from Hugging Face) would otherwise pass straight
        // through as a "downloaded" model, only to fail opaquely the first time llama-server
        // tries to load it. Re-run the same sanity check isDownloaded()/the UI rely on against
        // the now-final file, and fail closed — deleting the bad file — rather than surfacing it
        // as a successful download.
        validationError(entry)?.let { error ->
            Files.deleteIfExists(finalPath)
            throw DownloadException(error)
        }

        log.info("Downloaded llama model {} ({} bytes) to {}", entry.fileName, downloadedBytes, finalPath)
        finalPath
    }

    /** Deletes a previously downloaded model file to free disk space. */
    fun delete(entry: LlamaModelEntry) {
        Files.deleteIfExists(modelPath(entry))
    }
}
