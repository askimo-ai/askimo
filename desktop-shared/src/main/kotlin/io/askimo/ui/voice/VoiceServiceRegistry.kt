/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.config.VoiceConfig
import io.askimo.ui.voice.impl.LocalWhisperSpeechToTextFactory
import io.askimo.ui.voice.impl.OpenAiSpeechToTextFactory
import io.askimo.ui.voice.impl.OpenAiTextToSpeechFactory
import io.askimo.ui.voice.impl.PiperTextToSpeechFactory

/**
 * Resolves the active [SpeechToTextService]/[TextToSpeechService] from [VoiceConfig].
 *
 * Mirrors how `io.askimo.core.providers.ChatModelFactory` implementations are looked up per
 * `io.askimo.core.providers.ModelProvider` — but voice provider selection is entirely
 * orthogonal to the active chat provider (see `io.askimo.core.config.VoiceProvider` docs).
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
