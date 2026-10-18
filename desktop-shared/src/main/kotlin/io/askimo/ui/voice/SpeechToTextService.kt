/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

/**
 * Converts recorded audio into plain text.
 *
 * Implementations may call a cloud API (OpenAI Whisper) or a locally-hosted server
 * (whisper.cpp / faster-whisper) — the concrete implementation is selected via
 * [VoiceConfig.sttProvider] through [VoiceServiceRegistry], fully independent of the
 * active chat [io.askimo.core.providers.ModelProvider].
 */
interface SpeechToTextService {
    /**
     * Transcribes [audio] bytes encoded as [format] into plain text.
     *
     * @throws VoiceServiceException on network, authentication, or server-side errors.
     *   Callers should catch this and surface [VoiceServiceException.message] to the user
     *   (e.g. via `EventBus`/`AppErrorEvent`) rather than a raw stack trace.
     */
    suspend fun transcribe(audio: ByteArray, format: VoiceAudioFormat = VoiceAudioFormat.WAV): String
}

/**
 * Optional capability for Siri/Alexa-style live captions — a growing transcript shown while
 * recording, instead of only after it stops. Implemented only by
 * [io.askimo.ui.voice.impl.whispercpp.WhisperCppFfmSpeechToTextService], the sole in-process
 * (FFM) provider cheap enough to re-run every second or so. HTTP-based providers
 * ([io.askimo.ui.voice.impl.OpenAiSpeechToTextService], [io.askimo.ui.voice.impl.LocalWhisperSpeechToTextService])
 * could implement this too, but each partial update would cost a network round-trip.
 *
 * [io.askimo.ui.voice.VoiceRecordingController] uses this path when available, falling back to
 * [SpeechToTextService.transcribe] on the final WAV otherwise — purely additive.
 */
interface StreamingSpeechToTextService {
    /**
     * Starts a new live-transcription stream for one recording.
     *
     * @param onPartialUpdate Called with the full transcript-so-far after each inference pass.
     *   May run on a background thread — hop to your own dispatcher before touching UI state.
     */
    fun startStream(onPartialUpdate: (String) -> Unit): SpeechToTextStream
}

/**
 * One live-transcription session, fed audio as it's captured. Not thread-safe for concurrent
 * [feed] calls — intended to be fed from a single capture thread/coroutine.
 */
interface SpeechToTextStream {
    /** Feeds more mono float32 PCM samples (16kHz, `[-1f, 1f]`) into the stream, in order. */
    fun feed(samples: FloatArray)

    /**
     * Signals no more audio is coming, runs one final inference pass, and returns the complete
     * transcript. Suspends until done. Safe to call even if [feed] was never called.
     */
    suspend fun finish(): String

    /** Aborts without producing a final transcript. Idempotent. */
    fun cancel()
}
