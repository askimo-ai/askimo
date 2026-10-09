/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl.whispercpp

import io.askimo.core.config.AppConfig
import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.logging.logger
import io.askimo.ui.voice.SpeechToTextFactory
import io.askimo.ui.voice.SpeechToTextService
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import io.askimo.ui.voice.whispercpp.whisper_full_params
import io.askimo.ui.voice.whispercpp.whisper_h
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.util.Locale
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Speech-to-text via whisper.cpp running **in-process**, called directly through the Java
 * Foreign Function & Memory API (no JNI, no separate server process) — see
 * [io.askimo.ui.voice.whispercpp.whisper_h] for the jextract-generated bindings and
 * [NativeLibraryLoader] for how the bundled native library is located/loaded.
 *
 * Unlike [io.askimo.ui.voice.impl.LocalWhisperSpeechToTextService] (which talks HTTP to a
 * separately-running whisper.cpp server), this implementation loads the model and runs
 * inference directly inside the JVM process — lower latency (no HTTP hop), but requires the
 * native library + a downloaded `ggml-*.bin` model file (see [WhisperModelDownloader]) to be
 * present for the current platform.
 *
 * The whisper.cpp context is created lazily on first [transcribe] call and kept alive for the
 * lifetime of this instance — re-creating it per call would reload the (potentially multi-GB)
 * model every time. `whisper_h.whisper_full` is not reentrant on the same context, so calls are
 * serialized via [mutex].
 */
class WhisperCppFfmSpeechToTextService(private val config: VoiceConfig) : SpeechToTextService {
    private val log = logger<WhisperCppFfmSpeechToTextService>()
    private val mutex = Mutex()

    /** Lazily initialized on first [transcribe] call, guarded by [mutex]. Freed in [close]. */
    private var context: MemorySegment? = null
    private var loadedModelPath: String? = null

    override suspend fun transcribe(audio: ByteArray, format: VoiceAudioFormat): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ctx = ensureContextLoaded()
            val samples = decodeToMono16kFloat(audio)

            Arena.ofConfined().use { arena ->
                val params = buildFullParams(arena)
                val samplesSegment = arena.allocate(ValueLayout.JAVA_FLOAT.byteSize() * samples.size)
                MemorySegment.copy(samples, 0, samplesSegment, ValueLayout.JAVA_FLOAT, 0, samples.size)

                val result = whisper_h.whisper_full(ctx, params, samplesSegment, samples.size)
                if (result != 0) {
                    throw VoiceServiceException("whisper.cpp transcription failed (whisper_full returned $result)")
                }

                val segmentCount = whisper_h.whisper_full_n_segments(ctx)
                buildString {
                    for (i in 0 until segmentCount) {
                        val textPtr = whisper_h.whisper_full_get_segment_text(ctx, i)
                        append(textPtr.reinterpret(Long.MAX_VALUE).getString(0))
                    }
                }.trim()
            }
        }
    }

    /** Releases the native whisper.cpp context. Safe to call even if never initialized. */
    fun close() {
        context?.let { whisper_h.whisper_free(it) }
        context = null
        loadedModelPath = null
    }

    private fun ensureContextLoaded(): MemorySegment {
        NativeLibraryLoader.ensureLoaded()

        val modelPath = config.localWhisperModelPath
        if (modelPath.isBlank() || !Files.exists(java.nio.file.Path.of(modelPath))) {
            throw VoiceServiceException(
                "No local whisper.cpp model downloaded. Go to Settings > Voice and download a model first.",
            )
        }

        // Reload if the user switched model files since the last transcribe() call.
        if (context != null && loadedModelPath != modelPath) {
            close()
        }

        context?.let { return it }

        log.info("Loading whisper.cpp model from {}", modelPath)
        Arena.ofConfined().use { arena ->
            val contextParams = whisper_h.whisper_context_default_params(arena)
            val pathSegment = arena.allocateFrom(modelPath)
            val newContext = whisper_h.whisper_init_from_file_with_params(pathSegment, contextParams)
            if (newContext == MemorySegment.NULL) {
                throw VoiceServiceException(
                    "Failed to load whisper.cpp model from $modelPath — the file may be corrupt or " +
                        "not a valid ggml model.",
                )
            }
            context = newContext
            loadedModelPath = modelPath
            return newContext
        }
    }

    private fun buildFullParams(arena: Arena): MemorySegment {
        val params = whisper_h.whisper_full_default_params(arena, whisper_h.WHISPER_SAMPLING_GREEDY())
        whisper_full_params.n_threads(params, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        whisper_full_params.language(params, arena.allocateFrom(whisperLanguageCode()))
        whisper_full_params.translate(params, false)
        whisper_full_params.single_segment(params, false)
        whisper_full_params.no_context(params, true)
        // Silence whisper.cpp's own stdout/stderr logging — Askimo has its own logging pipeline.
        whisper_full_params.print_progress(params, false)
        whisper_full_params.print_realtime(params, false)
        whisper_full_params.print_special(params, false)
        whisper_full_params.print_timestamps(params, false)
        return params
    }

    /**
     * Same ISO-639-1 derivation as [io.askimo.ui.voice.impl.OpenAiSpeechToTextService.whisperLanguageCode] —
     * whisper.cpp's `language` param expects a plain lowercase code (e.g. `"en"`), not a full
     * BCP-47/Java locale tag.
     */
    private fun whisperLanguageCode(): String {
        val raw = AppConfig.currentLocale ?: "en"
        val primary = raw.substringBefore('-').substringBefore('_')
        return primary.lowercase(Locale.ROOT).ifBlank { "en" }
    }

    /**
     * Decodes [audio] (a WAV file, per [io.askimo.ui.voice.AudioRecorder]'s output format) into
     * normalized `[-1f, 1f]` mono float32 PCM at 16kHz, as required by
     * [whisper_h.whisper_full]'s `samples` parameter.
     *
     * [io.askimo.ui.voice.AudioRecorder] already captures at 16kHz mono 16-bit PCM, so this is
     * normally just a format conversion with no resampling. Non-matching input is best-effort
     * converted via [javax.sound.sampled], whose stock-JDK format conversion does **not**
     * include a sample-rate converter — mismatched rates will throw. This mirrors the existing
     * assumption in [io.askimo.ui.voice.impl.OpenAiSpeechToTextService]/
     * [io.askimo.ui.voice.impl.LocalWhisperSpeechToTextService], which both forward the
     * recorder's WAV bytes as-is.
     */
    private fun decodeToMono16kFloat(audio: ByteArray): FloatArray {
        val targetFormat = AudioFormat(16_000f, 16, 1, true, false)
        try {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(audio)).use { sourceStream ->
                val convertedStream = if (sourceStream.format.matches(targetFormat)) {
                    sourceStream
                } else {
                    AudioSystem.getAudioInputStream(targetFormat, sourceStream)
                }
                val pcmBytes = convertedStream.readAllBytes()
                val sampleCount = pcmBytes.size / 2
                val samples = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val low = pcmBytes[i * 2].toInt() and 0xFF
                    val high = pcmBytes[i * 2 + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    samples[i] = sample / 32768f
                }
                return samples
            }
        } catch (e: Exception) {
            throw VoiceServiceException(
                "Could not decode recorded audio for whisper.cpp (expected 16kHz mono WAV): ${e.message}",
                e,
            )
        }
    }
}

object WhisperCppFfmSpeechToTextFactory : SpeechToTextFactory {
    override val provider: VoiceProvider = VoiceProvider.LOCAL_WHISPER_FFM
    override fun create(config: VoiceConfig): SpeechToTextService = WhisperCppFfmSpeechToTextService(config)
}
