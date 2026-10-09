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

    /** `true` if [tier]'s model file is already present under [io.askimo.core.util.AskimoHome.whisperModelsDir]. */
    fun isDownloaded(tier: WhisperModelCatalog): Boolean = modelPath(tier).let { Files.exists(it) && Files.size(it) > 0 }

    fun modelPath(tier: WhisperModelCatalog): Path = AskimoHome.whisperModelsDir().resolve(tier.fileName)

    /**
     * Downloads [tier] if not already present, reporting progress via [onProgress] as
     * `(bytesDownloaded, totalBytes)` — `totalBytes` is `-1` if the server didn't report
     * `Content-Length`. Downloads to a sibling `.part` file first, then atomically moves it into
     * place on success — a cancelled/failed download never leaves a corrupt file at the final path.
     *
     * @param baseUrl Source host/path for [tier]'s file, e.g. [VoiceConfig.whisperModelBaseUrl].
     * @throws VoiceServiceException on network failure or non-2xx response.
     */
    suspend fun download(
        tier: WhisperModelCatalog,
        baseUrl: String = VoiceConfig().whisperModelBaseUrl,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        val finalPath = modelPath(tier)
        if (Files.exists(finalPath) && Files.size(finalPath) > 0) {
            return@withContext finalPath
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

        Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info("Downloaded whisper model {} ({} bytes) to {}", tier.fileName, downloadedBytes, finalPath)
        finalPath
    }

    /** Deletes a previously downloaded model file, e.g. when the user switches tiers to free disk space. */
    fun delete(tier: WhisperModelCatalog) {
        Files.deleteIfExists(modelPath(tier))
    }
}
