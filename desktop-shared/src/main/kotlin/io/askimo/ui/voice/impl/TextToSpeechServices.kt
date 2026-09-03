/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl

import dev.langchain4j.model.openai.OpenAiTextToSpeechModel
import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.logging.logger
import io.askimo.ui.voice.TextToSpeechFactory
import io.askimo.ui.voice.TextToSpeechService
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Text-to-speech via OpenAI's TTS API (`/v1/audio/speech`), using langchain4j's
 * [OpenAiTextToSpeechModel].
 *
 * Uses [VoiceConfig.openAiApiKey] — a key **separate** from any `OPENAI`
 * [io.askimo.core.providers.ProviderInstance] — [VoiceConfig.ttsModel] (default `tts-1`) and
 * [VoiceConfig.ttsVoice] (default `alloy`). Response is MP3-encoded audio.
 */
class OpenAiTextToSpeechService(private val config: VoiceConfig) : TextToSpeechService {
    private val log = logger<OpenAiTextToSpeechService>()

    override val outputFormat: VoiceAudioFormat = VoiceAudioFormat.MP3

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val apiKey = config.openAiApiKey
        if (apiKey.isBlank()) {
            throw VoiceServiceException("OpenAI API key for voice is not configured. Set it in Settings > Voice.")
        }

        try {
            val tts = OpenAiTextToSpeechModel.builder()
                .apiKey(apiKey)
                .modelName(config.ttsModel.ifBlank { "tts-1" })
                .voice(config.ttsVoice.ifBlank { "alloy" })
                .build()
            tts.synthesize(text).audio().binaryData()
        } catch (e: Exception) {
            log.warn("OpenAI TTS request failed", e)
            throw VoiceServiceException("OpenAI TTS request failed: ${e.message}", e)
        }
    }
}

object OpenAiTextToSpeechFactory : TextToSpeechFactory {
    override val provider: VoiceProvider = VoiceProvider.OPENAI
    override fun create(config: VoiceConfig): TextToSpeechService = OpenAiTextToSpeechService(config)
}

/**
 * Text-to-speech via a user-hosted Piper HTTP server exposing an OpenAI-compatible
 * `/v1/audio/speech` endpoint (e.g. `piper-http` OpenAI-compatible wrappers). Free, no API key
 * required, runs fully offline.
 *
 * Uses langchain4j's [OpenAiTextToSpeechModel] pointed at a custom [VoiceConfig.localTtsEndpoint]
 * base URL (default `http://localhost:5000`) — the same "OpenAI-compatible" pattern used by
 * [io.askimo.core.providers.openaicompatible.OpenAiCompatibleModelFactory] for chat/embedding
 * models and by [LocalWhisperSpeechToTextService] for local speech-to-text.
 */
class PiperTextToSpeechService(private val config: VoiceConfig) : TextToSpeechService {
    private val log = logger<PiperTextToSpeechService>()

    override val outputFormat: VoiceAudioFormat = VoiceAudioFormat.WAV

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val baseUrl = config.localTtsEndpoint.trimEnd('/')
        if (baseUrl.isBlank()) {
            throw VoiceServiceException("Local Piper endpoint is not configured. Set it in Settings > Voice.")
        }

        try {
            val tts = OpenAiTextToSpeechModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.openAiApiKey.ifBlank { "not-needed" })
                .modelName(config.ttsModel.ifBlank { "tts-1" })
                .voice(config.ttsVoice.ifBlank { "alloy" })
                .build()

            tts.synthesize(text).audio().binaryData()
        } catch (e: Exception) {
            log.warn("Local Piper TTS request failed", e)
            throw VoiceServiceException(
                "Could not reach local Piper server at $baseUrl. Is it running? (${e.message})",
                e,
            )
        }
    }
}

object PiperTextToSpeechFactory : TextToSpeechFactory {
    override val provider: VoiceProvider = VoiceProvider.LOCAL_PIPER
    override fun create(config: VoiceConfig): TextToSpeechService = PiperTextToSpeechService(config)
}
