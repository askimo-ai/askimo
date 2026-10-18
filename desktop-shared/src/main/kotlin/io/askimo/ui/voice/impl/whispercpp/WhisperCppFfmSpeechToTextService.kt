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
import io.askimo.ui.voice.SpeechToTextStream
import io.askimo.ui.voice.StreamingSpeechToTextService
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import io.askimo.ui.voice.whispercpp.whisper_full_params
import io.askimo.ui.voice.whispercpp.whisper_h
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Speech-to-text via whisper.cpp running **in-process** through the Java Foreign Function &
 * Memory API (no JNI, no separate server) — see [io.askimo.ui.voice.whispercpp.whisper_h] for
 * the jextract-generated bindings and [NativeLibraryLoader] for native library loading.
 *
 * Unlike [io.askimo.ui.voice.impl.LocalWhisperSpeechToTextService] (HTTP to a whisper.cpp
 * server), this loads the model and runs inference directly in the JVM — lower latency, but
 * requires the native library + a downloaded `ggml-*.bin` model (see [WhisperModelDownloader]).
 *
 * The context is created lazily on first [transcribe] and kept alive for this instance's
 * lifetime (reloading a multi-GB model per call would be too costly). `whisper_full` isn't
 * reentrant on the same context, so calls are serialized via [mutex].
 *
 * Implements [AutoCloseable] to free the off-heap native context explicitly (the JVM GC can't
 * reclaim it). [io.askimo.ui.voice.VoiceServiceRegistry] owns all instances — it caches one per
 * resolved [VoiceConfig] and closes the previous one on config change — so callers should never
 * construct this directly or call [close] themselves.
 */
class WhisperCppFfmSpeechToTextService(private val config: VoiceConfig) :
    SpeechToTextService,
    StreamingSpeechToTextService,
    AutoCloseable {
    private val log = logger<WhisperCppFfmSpeechToTextService>()
    private val mutex = Mutex()

    companion object {
        /**
         * Re-run `whisper_full` on the growing buffer every ~1.2s of newly-fed audio (16kHz
         * mono). Longer than ideal for "feels live", but keeps this CPU-heavy native call from
         * competing with the audio-capture thread (see [io.askimo.ui.voice.AudioRecorder]'s
         * silence-timer doc) and the UI thread.
         */
        private const val STREAM_PROCESS_INTERVAL_SAMPLES = (16_000 * 1.2).toInt()
    }

    /** Lazily initialized on first [transcribe] call, guarded by [mutex]. Freed in [close]. */
    private var context: MemorySegment? = null
    private var loadedModelPath: String? = null

    /**
     * Guarded by [mutex]; set once [close] has run. Checked at the top of [transcribe] so a
     * caller racing a registry eviction can't resurrect a freed instance — [ensureContextLoaded]
     * alone can't tell "never initialized" from "closed".
     */
    private var closed = false

    override suspend fun transcribe(audio: ByteArray, format: VoiceAudioFormat): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (closed) {
                    throw VoiceServiceException(
                        "This speech-to-text service instance has already been closed (likely because " +
                            "the voice provider/config changed). Retry — a fresh instance will be resolved.",
                    )
                }
                val ctx = ensureContextLoaded()
                val samples = decodeToMono16kFloat(audio)
                runWhisperFullLocked(ctx, samples)
            } catch (e: LinkageError) {
                // System.load/FFM symbol resolution throw LinkageError subtypes, not Exception
                // (e.g. missing/corrupt/wrong-arch native library). VoiceRecordingController's
                // catch only handles Exception, so without this it'd escape the voice error path.
                throw VoiceServiceException(
                    "Failed to load or call the bundled whisper.cpp native library " +
                        "(${e.javaClass.simpleName}: ${e.message}). The library may be missing, " +
                        "corrupted, or incompatible with your platform. Try reinstalling Askimo, " +
                        "or switch to a different voice provider in Settings > Voice.",
                    e,
                )
            }
        }
    }

    /**
     * Releases the native whisper.cpp context. Safe even if never initialized; idempotent.
     *
     * Blocks briefly (via [runBlocking]) to acquire [mutex] — the same lock [transcribe] holds
     * for the whole native call — so this can't free the context out from under an in-flight
     * [transcribe] (e.g. [io.askimo.ui.voice.VoiceServiceRegistry] evicting it on config
     * change); otherwise that's a native use-after-free.
     *
     * Also sets [closed] under the same lock, so any blocked or later [transcribe] call fails
     * fast instead of leaking a new context. See [closed].
     */
    override fun close() {
        runBlocking {
            mutex.withLock {
                closed = true
                freeContextLocked()
            }
        }
    }

    /** Must only be called while already holding [mutex] (see [close] and [ensureContextLoaded]). */
    private fun freeContextLocked() {
        context?.let { whisper_h.whisper_free(it) }
        context = null
        loadedModelPath = null
    }

    /**
     * Runs one `whisper_full` pass over [samples] on [ctx] and returns the joined segment text.
     * Shared by [transcribe] and [WhisperStreamSession.runInferencePass]. Must be called while
     * already holding [mutex] — not reentrant-safe against a concurrent `whisper_full`.
     *
     * @param maxThreads Thread cap for `whisper_full` (see [buildFullParams]). Streaming passes
     *   use fewer threads than the default batch [transcribe]/[SpeechToTextStream.finish] call,
     *   since they run far more often while recording is still in progress and shouldn't starve
     *   [io.askimo.ui.voice.AudioRecorder]'s capture thread or the UI thread of CPU.
     */
    private fun runWhisperFullLocked(ctx: MemorySegment, samples: FloatArray, maxThreads: Int = 4): String {
        Arena.ofConfined().use { arena ->
            val params = buildFullParams(arena, maxThreads)
            val samplesSegment = arena.allocate(ValueLayout.JAVA_FLOAT.byteSize() * samples.size)
            MemorySegment.copy(samples, 0, samplesSegment, ValueLayout.JAVA_FLOAT, 0, samples.size)

            val result = whisper_h.whisper_full(ctx, params, samplesSegment, samples.size)
            if (result != 0) {
                throw VoiceServiceException("whisper.cpp transcription failed (whisper_full returned $result)")
            }

            val segmentCount = whisper_h.whisper_full_n_segments(ctx)
            return buildString {
                for (i in 0 until segmentCount) {
                    val textPtr = whisper_h.whisper_full_get_segment_text(ctx, i)
                    append(textPtr.reinterpret(Long.MAX_VALUE).getString(0))
                }
            }.trim()
        }
    }

    /**
     * See [StreamingSpeechToTextService] — this is the Siri/Alexa-style "live captions while
     * recording" entry point, available only for this in-process/FFM-based implementation.
     */
    override fun startStream(onPartialUpdate: (String) -> Unit): SpeechToTextStream = WhisperStreamSession(onPartialUpdate)

    /**
     * One live-transcription session (see [SpeechToTextStream]). Buffers incoming PCM chunks
     * and, once [STREAM_PROCESS_INTERVAL_SAMPLES] of new audio has accumulated, re-runs
     * `whisper_full` over the **entire buffered-so-far** audio — simplest strategy (vs.
     * carrying whisper's token context across windows), and consistent since each pass sees
     * the whole utterance. Cost grows with recording length; accepted trade-off since recordings
     * stay short via [io.askimo.core.config.VoiceConfig.autoStopOnSilence] or
     * [io.askimo.ui.voice.VoiceRecordingController]'s hard cap.
     *
     * All inference runs inside [consumerJob] (a single coroutine reading [incoming]), so
     * concurrent [feed] calls can't race each other, and it shares [mutex] with [transcribe] so
     * a live stream and a one-shot [transcribe] on this instance never run `whisper_full`
     * concurrently.
     */
    private inner class WhisperStreamSession(
        private val onPartialUpdate: (String) -> Unit,
    ) : SpeechToTextStream {
        private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val incoming = Channel<FloatArray>(capacity = Channel.UNLIMITED)

        // Only ever touched from inside consumerJob's single coroutine — no synchronization needed.
        private val chunks = mutableListOf<FloatArray>()
        private var totalSamples = 0
        private var samplesSinceLastRun = 0
        private var lastTranscript = ""

        private val consumerJob = sessionScope.launch {
            for (chunk in incoming) {
                chunks.add(chunk)
                totalSamples += chunk.size
                samplesSinceLastRun += chunk.size
                if (samplesSinceLastRun >= STREAM_PROCESS_INTERVAL_SAMPLES) {
                    samplesSinceLastRun = 0
                    runInferencePass()
                }
            }
        }

        override fun feed(samples: FloatArray) {
            // trySend (not send): called directly from AudioRecorder's capture thread, which
            // must never suspend. Fails only if the channel's already closed; safe to ignore.
            incoming.trySend(samples)
        }

        override suspend fun finish(): String = withContext(Dispatchers.IO) {
            incoming.close()
            consumerJob.join()
            val finalTranscript = try {
                runInferencePass()
            } catch (e: Exception) {
                log.warn("Final whisper.cpp streaming inference pass failed", e)
                lastTranscript
            }
            sessionScope.cancel()
            finalTranscript
        }

        override fun cancel() {
            incoming.close()
            sessionScope.cancel()
        }

        /** Must only be called from inside [consumerJob]/[finish] — never concurrently. */
        private suspend fun runInferencePass(): String {
            if (totalSamples == 0) return lastTranscript
            return try {
                mutex.withLock {
                    if (closed) return@withLock lastTranscript
                    val ctx = ensureContextLoaded()
                    val samples = FloatArray(totalSamples)
                    var offset = 0
                    for (chunk in chunks) {
                        chunk.copyInto(samples, offset)
                        offset += chunk.size
                    }
                    // Capped at 2 threads (vs. the 4 used by a one-shot transcribe()/finish()
                    // call) — see runWhisperFullLocked's maxThreads doc for why.
                    val text = runWhisperFullLocked(ctx, samples, maxThreads = 2)
                    lastTranscript = text
                    onPartialUpdate(text)
                    text
                }
            } catch (e: LinkageError) {
                log.warn("Failed to load or call the bundled whisper.cpp native library during streaming", e)
                lastTranscript
            }
        }
    }

    private fun ensureContextLoaded(): MemorySegment {
        NativeLibraryLoader.ensureLoaded()

        val modelPath = config.localWhisperModelPath
        if (modelPath.isBlank() || !Files.exists(java.nio.file.Path.of(modelPath))) {
            throw VoiceServiceException(
                "No local whisper.cpp model downloaded. Go to Settings > Voice and download a model first.",
            )
        }

        // Reload if the user switched model files since the last transcribe() call. Free directly
        // (not via close()) — this runs inside transcribe()'s mutex.withLock already, and close()
        // acquiring the same non-reentrant mutex here would deadlock.
        if (context != null && loadedModelPath != modelPath) {
            freeContextLocked()
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

    private fun buildFullParams(arena: Arena, maxThreads: Int = 4): MemorySegment {
        val params = whisper_h.whisper_full_default_params(arena, whisper_h.WHISPER_SAMPLING_GREEDY())
        whisper_full_params.n_threads(params, Runtime.getRuntime().availableProcessors().coerceIn(1, maxThreads))
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
     * Decodes [audio] (a WAV file, per [io.askimo.ui.voice.AudioRecorder]'s output) into
     * normalized `[-1f, 1f]` mono float32 PCM at 16kHz, as [whisper_h.whisper_full] requires.
     *
     * [io.askimo.ui.voice.AudioRecorder] already captures 16kHz mono 16-bit PCM, so this is
     * normally just a format conversion, no resampling. Non-matching input is best-effort
     * converted via [javax.sound.sampled], which has **no** sample-rate converter — mismatched
     * rates will throw. Same assumption as [io.askimo.ui.voice.impl.OpenAiSpeechToTextService]/
     * [io.askimo.ui.voice.impl.LocalWhisperSpeechToTextService].
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
