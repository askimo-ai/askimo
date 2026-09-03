/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.ui.voice.impl.LocalWhisperSpeechToTextFactory
import io.askimo.ui.voice.impl.OpenAiSpeechToTextFactory
import io.askimo.ui.voice.impl.OpenAiTextToSpeechFactory
import io.askimo.ui.voice.impl.PiperTextToSpeechFactory

/**
 * Audio encoding used when exchanging bytes with [SpeechToTextService]/[TextToSpeechService].
 * Deliberately distinct from the low-level PCM description used by the audio recorder/player —
 * this is just a wire-format label for HTTP payloads.
 */
enum class VoiceAudioFormat(val mimeType: String, val fileExtension: String) {
    WAV("audio/wav", "wav"),
    MP3("audio/mpeg", "mp3"),
}

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
 * Synthesises plain text into spoken audio.
 *
 * Implementations may call a cloud API (OpenAI TTS) or a locally-hosted server (Piper) —
 * the concrete implementation is selected via [VoiceConfig.ttsProvider] through
 * [VoiceServiceRegistry], fully independent of the active chat
 * [io.askimo.core.providers.ModelProvider].
 */
interface TextToSpeechService {
    /** Audio format returned by [synthesize] — used by the audio player to decode correctly. */
    val outputFormat: VoiceAudioFormat

    /**
     * Synthesises [text] into audio bytes.
     *
     * @throws VoiceServiceException on network, authentication, or server-side errors.
     */
    suspend fun synthesize(text: String): ByteArray
}

/**
 * Thrown by [SpeechToTextService]/[TextToSpeechService] implementations on recoverable failure
 * (auth error, network error, unreachable local server, unexpected HTTP status, etc.).
 * [message] is expected to be user-presentable.
 */
class VoiceServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Factory for a specific [SpeechToTextService] implementation, keyed by [provider]. */
interface SpeechToTextFactory {
    val provider: VoiceProvider
    fun create(config: VoiceConfig): SpeechToTextService
}

/** Factory for a specific [TextToSpeechService] implementation, keyed by [provider]. */
interface TextToSpeechFactory {
    val provider: VoiceProvider
    fun create(config: VoiceConfig): TextToSpeechService
}

/**
 * Resolves the active [SpeechToTextService]/[TextToSpeechService] from [VoiceConfig].
 *
 * Mirrors how [io.askimo.core.providers.ChatModelFactory] implementations are looked up per
 * [io.askimo.core.providers.ModelProvider] — but voice provider selection is entirely
 * orthogonal to the active chat provider (see [VoiceProvider] docs).
 */
object VoiceServiceRegistry {
    private val sttFactories: List<SpeechToTextFactory> = listOf(
        OpenAiSpeechToTextFactory,
        LocalWhisperSpeechToTextFactory,
    )

    private val ttsFactories: List<TextToSpeechFactory> = listOf(
        OpenAiTextToSpeechFactory,
        PiperTextToSpeechFactory,
    )

    /**
     * Resolves the configured [VoiceConfig.sttProvider] to a ready-to-use [SpeechToTextService].
     * @throws VoiceServiceException if no factory is registered for the configured provider —
     *   keeps the same exception type callers already catch for network/auth failures.
     */
    fun speechToText(config: VoiceConfig): SpeechToTextService {
        val factory = sttFactories.find { it.provider == config.sttProvider }
            ?: throw VoiceServiceException("No speech-to-text implementation registered for provider ${config.sttProvider}")
        return factory.create(config)
    }

    /**
     * Resolves the configured [VoiceConfig.ttsProvider] to a ready-to-use [TextToSpeechService].
     * @throws VoiceServiceException if no factory is registered for the configured provider —
     *   keeps the same exception type callers already catch for network/auth failures.
     */
    fun textToSpeech(config: VoiceConfig): TextToSpeechService {
        val factory = ttsFactories.find { it.provider == config.ttsProvider }
            ?: throw VoiceServiceException("No text-to-speech implementation registered for provider ${config.ttsProvider}")
        return factory.create(config)
    }
}
