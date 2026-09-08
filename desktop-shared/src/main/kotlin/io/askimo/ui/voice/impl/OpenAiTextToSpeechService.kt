/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl

import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.logging.logger
import io.askimo.core.util.httpPostForBytes
import io.askimo.ui.voice.TextToSpeechFactory
import io.askimo.ui.voice.TextToSpeechService
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import io.askimo.ui.voice.toFriendlyVoiceErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Text-to-speech via OpenAI's TTS API (`/v1/audio/speech`), called directly over HTTP
 *
 * Uses [VoiceConfig.openAiApiKey] — a key **separate** from any `OPENAI`
 * [io.askimo.core.providers.ProviderInstance] — [VoiceConfig.ttsModel] (default `tts-1`),
 * [VoiceConfig.ttsVoice] (default `alloy`), and [VoiceConfig.ttsSpeed] (default `1.0`).
 * Response is MP3-encoded audio.
 */
class OpenAiTextToSpeechService(private val config: VoiceConfig) : TextToSpeechService {
    private val log = logger<OpenAiTextToSpeechService>()

    override val outputFormat: VoiceAudioFormat = VoiceAudioFormat.MP3

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val apiKey = config.openAiApiKey
        if (apiKey.isBlank()) {
            throw VoiceServiceException("OpenAI API key for voice is not configured. Set it in Settings > Voice.")
        }

        val body = buildJsonObject {
            put("model", config.ttsModel.ifBlank { "tts-1" })
            put("input", text)
            put("voice", config.ttsVoice.ifBlank { "alloy" })
            put("response_format", "mp3")
            put("speed", config.ttsSpeed.takeIf { it in 0.25..4.0 } ?: 1.0)
        }.toString()

        try {
            val (status, responseBytes) = httpPostForBytes(
                url = "https://api.openai.com/v1/audio/speech",
                body = body,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )
            if (status != 200) {
                val errorText = String(responseBytes, Charsets.UTF_8)
                throw VoiceServiceException("OpenAI TTS request failed (HTTP $status): $errorText")
            }
            responseBytes
        } catch (e: VoiceServiceException) {
            throw e
        } catch (e: Exception) {
            log.warn("OpenAI TTS request failed", e)
            throw VoiceServiceException(e.toFriendlyVoiceErrorMessage("OpenAI TTS request failed"), e)
        }
    }
}

object OpenAiTextToSpeechFactory : TextToSpeechFactory {
    override val provider: VoiceProvider = VoiceProvider.OPENAI
    override fun create(config: VoiceConfig): TextToSpeechService = OpenAiTextToSpeechService(config)
}
