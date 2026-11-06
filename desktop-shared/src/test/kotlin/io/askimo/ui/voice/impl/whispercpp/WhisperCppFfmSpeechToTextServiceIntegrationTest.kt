/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl.whispercpp

import io.askimo.core.config.VoiceConfig
import io.askimo.test.extensions.AskimoTestHome
import io.askimo.ui.voice.VoiceAudioFormat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.test.assertNotNull

/**
 * End-to-end regression coverage for [WhisperCppFfmSpeechToTextService.transcribe] — unlike
 * `WhisperBindingsLayoutTest` (which only exercises the jextract-generated struct layouts in
 * isolation), this drives the *actual* reported crash's code path for real: it calls
 * [NativeLibraryLoader.ensureLoaded] to load this OS's bundled native whisper.cpp library
 * (committed under `src/main/resources/natives/whisper/<platform>/`), then
 * `whisper_h.whisper_context_default_params`/`whisper_h.whisper_init_from_file_with_params`
 * actually build and read the native structs (`whisper_context_params`, `whisper_full_params`)
 * whose `size_t` fields triggered the Windows-only `ClassCastException` these tests guard
 * against (see `whisper_h$shared.C_LONG`).
 *
 * Downloads a small real `ggml-base.bin` model once (cached under the OS temp/user-cache dir
 * across runs — see [downloadModelIfMissing]) rather than reusing [WhisperModelDownloader],
 * since that writes under [io.askimo.core.util.AskimoHome.whisperModelsDir], which
 * [AskimoTestHome] repoints at a fresh temp dir per test run. If the download can't complete
 * (e.g. no network), the test is skipped (not failed) via [assumeTrue] — CI runners have
 * outbound network access and should always exercise this for real.
 */
@AskimoTestHome
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhisperCppFfmSpeechToTextServiceIntegrationTest {

    private var modelPath: Path? = null

    @BeforeAll
    fun downloadModel() {
        modelPath = try {
            downloadModelIfMissing()
        } catch (e: Exception) {
            if (System.getenv("CI") != null) {
                throw IllegalStateException("Could not download whisper model for integration test in CI", e)
            }
            println("Skipping ${javaClass.simpleName}: could not download whisper model (${e.message})")
            null
        }
    }

    @Test
    fun `transcribe loads the native whisper context and runs whisper_full without throwing`() {
        val path = modelPath
        assumeTrue(path != null, "Skipping: whisper model unavailable (see downloadModel log above)")

        val config = VoiceConfig(localWhisperModelPath = path!!.toString())
        val service = WhisperCppFfmSpeechToTextService(config)
        try {
            val silentWav = buildSilentWav(durationSeconds = 1)
            val result = runBlocking { service.transcribe(silentWav, VoiceAudioFormat.WAV) }
            // whisper.cpp on pure silence may return an empty/near-empty string — the point of
            // this test is that native context creation + whisper_full complete without
            // throwing (ClassCastException/ExceptionInInitializerError on Windows, specifically).
            assertNotNull(result)
        } finally {
            service.close()
        }
    }

    /**
     * Downloads `ggml-base.bin` (the smallest [WhisperModelCatalog] tier) directly to a stable
     * cache directory outside of [AskimoTestHome]'s per-test temp dir, so repeated local test
     * runs reuse it instead of re-downloading ~142MB every time.
     */
    private fun downloadModelIfMissing(): Path {
        val tier = WhisperModelCatalog.FAST
        val cacheDir = Path.of(System.getProperty("user.home"), ".cache", "askimo-test-models")
        val destPath = cacheDir.resolve(tier.fileName)
        if (Files.exists(destPath) && Files.size(destPath) >= tier.approxSizeBytes / 2) {
            return destPath
        }

        Files.createDirectories(cacheDir)
        val partPath = cacheDir.resolve("${tier.fileName}.part")
        val url = tier.downloadUrl(VoiceConfig().whisperModelBaseUrl)

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() in 200..299) {
            "Downloading ${tier.fileName} failed (HTTP ${response.statusCode()})"
        }

        response.body().use { input -> Files.copy(input, partPath, StandardCopyOption.REPLACE_EXISTING) }
        check(Files.size(partPath) >= tier.approxSizeBytes / 2) {
            "Downloaded ${tier.fileName} is implausibly small (${Files.size(partPath)} bytes)"
        }
        Files.move(partPath, destPath, StandardCopyOption.REPLACE_EXISTING)
        return destPath
    }

    /** Builds a minimal valid 16kHz mono 16-bit PCM WAV of silence, as `AudioRecorder` would produce. */
    private fun buildSilentWav(durationSeconds: Int): ByteArray {
        val sampleRate = 16_000
        val numSamples = sampleRate * durationSeconds
        val dataSize = numSamples * 2 // 16-bit mono
        val out = ByteArrayOutputStream()

        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }

        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        out.writeBytes("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(36 + dataSize)
        out.writeBytes("WAVE".toByteArray(Charsets.US_ASCII))
        out.writeBytes("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(16) // fmt chunk size
        writeShortLE(1) // PCM
        writeShortLE(1) // mono
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 2) // byte rate
        writeShortLE(2) // block align
        writeShortLE(16) // bits per sample
        out.writeBytes("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(dataSize)
        repeat(numSamples) { writeShortLE(0) }

        return out.toByteArray()
    }
}
