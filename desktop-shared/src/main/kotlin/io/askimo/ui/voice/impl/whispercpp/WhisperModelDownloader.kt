/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl.whispercpp

import io.askimo.core.config.VoiceConfig
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.ProxyUtil
import io.askimo.ui.voice.VoiceServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Downloads a [WhisperModelCatalog]'s `ggml-*.bin` file from the official `ggerganov/whisper.cpp`
 * Hugging Face repo to [io.askimo.core.util.AskimoHome.whisperModelsDir], streaming directly to
 * disk (never buffering the full multi-GB file in memory) with progress callbacks for the
 * Settings UI.
 *
 * Triggered **explicitly** from Settings > Voice (not lazily on first [io.askimo.ui.voice.SpeechToTextService.transcribe]
 * call) — the user clicks "Download" and sees a progress bar before the
 * [io.askimo.core.config.VoiceProvider.LOCAL_WHISPER_FFM] provider becomes usable.
 */
object WhisperModelDownloader {
    private val log = logger<WhisperModelDownloader>()

    /**
     * `true` if [tier]'s model file is present **and** passes [isPlausibleSize] — a non-empty
     * file alone isn't proof of a complete, valid model (e.g. a mirror's 200 HTML error page,
     * or a file truncated by a prior crash/disk-full).
     */
    fun isDownloaded(tier: WhisperModelCatalog): Boolean = modelPath(tier).let { Files.exists(it) && isPlausibleSize(tier, Files.size(it)) }

    fun modelPath(tier: WhisperModelCatalog): Path = AskimoHome.whisperModelsDir().resolve(tier.fileName)

    /**
     * Rejects sizes under half of [WhisperModelCatalog.approxSizeBytes]. Deliberately generous
     * (catalog sizes are documented as approximate) — catches gross corruption/error pages, not
     * a full integrity check (no exact size/SHA-256 is published to validate against).
     */
    private fun isPlausibleSize(tier: WhisperModelCatalog, sizeBytes: Long): Boolean = sizeBytes >= tier.approxSizeBytes / 2

    /**
     * Downloads [tier] if not already present ([isPlausibleSize]; a failing pre-existing file is
     * deleted and re-downloaded), reporting progress via [onProgress] as
     * `(bytesDownloaded, totalBytes)` — `totalBytes` is `-1` if the server didn't report
     * `Content-Length`. Downloads to a sibling `.part` file, then atomically moves it into place.
     * The result is checked against `Content-Length` (when present) and [isPlausibleSize] before
     * being kept, so a mirror's 200 HTML/error body can't be moved in and treated as valid.
     *
     * @param baseUrl Source host/path for [tier]'s file, e.g. [VoiceConfig.whisperModelBaseUrl].
     * @throws VoiceServiceException on network failure, non-2xx response, or a downloaded file
     *   that fails the size checks above.
     */
    suspend fun download(
        tier: WhisperModelCatalog,
        baseUrl: String = VoiceConfig().whisperModelBaseUrl,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        val finalPath = modelPath(tier)
        if (Files.exists(finalPath)) {
            if (isPlausibleSize(tier, Files.size(finalPath))) {
                return@withContext finalPath
            }
            log.warn(
                "Existing whisper model file {} is smaller than expected ({} bytes) — treating as " +
                    "corrupt/incomplete and re-downloading.",
                finalPath,
                Files.size(finalPath),
            )
            Files.deleteIfExists(finalPath)
        }

        Files.createDirectories(AskimoHome.whisperModelsDir())
        val partPath = finalPath.resolveSibling("${tier.fileName}.part")
        val downloadUrl = tier.downloadUrl(baseUrl)

        val client = ProxyUtil.configureProxy(HttpClient.newBuilder(), downloadUrl)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI(downloadUrl))
            .timeout(Duration.ofHours(2))
            .GET()
            .build()

        log.info("Downloading whisper model {} from {}", tier.fileName, downloadUrl)
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw VoiceServiceException("Failed to connect to Hugging Face to download ${tier.fileName}: ${e.message}", e)
        }

        if (response.statusCode() !in 200..299) {
            throw VoiceServiceException(
                "Downloading ${tier.fileName} failed (HTTP ${response.statusCode()}). The Hugging Face repo " +
                    "file layout may have changed — see WhisperModelCatalog.",
            )
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var downloadedBytes = 0L

        try {
            response.body().use { input ->
                Files.newOutputStream(partPath).use { output ->
                    val buffer = ByteArray(1 shl 20) // 1 MiB
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Files.deleteIfExists(partPath)
            throw VoiceServiceException("Download of ${tier.fileName} failed or was cancelled: ${e.message}", e)
        }

        try {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
        }

        // A mirror's 200 HTML/error body, or a size/Content-Length mismatch, must not be trusted
        // as a valid model — isDownloaded would otherwise short-circuit on it forever.
        if (totalBytes != -1L && downloadedBytes != totalBytes) {
            Files.deleteIfExists(finalPath)
            throw VoiceServiceException(
                "Downloaded ${tier.fileName} size ($downloadedBytes bytes) does not match the " +
                    "server-reported size ($totalBytes bytes) — the file was deleted; please retry.",
            )
        }
        if (!isPlausibleSize(tier, downloadedBytes)) {
            Files.deleteIfExists(finalPath)
            throw VoiceServiceException(
                "Downloaded ${tier.fileName} is implausibly small ($downloadedBytes bytes) — likely an " +
                    "error page from the mirror rather than the model file. The file was deleted; please retry.",
            )
        }

        log.info("Downloaded whisper model {} ({} bytes) to {}", tier.fileName, downloadedBytes, finalPath)
        finalPath
    }

    /** Deletes a previously downloaded model file, e.g. when the user switches tiers to free disk space. */
    fun delete(tier: WhisperModelCatalog) {
        Files.deleteIfExists(modelPath(tier))
    }
}
