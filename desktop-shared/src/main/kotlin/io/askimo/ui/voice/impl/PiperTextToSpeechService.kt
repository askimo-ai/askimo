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
 * Text-to-speech via a user-hosted Piper HTTP server exposing an OpenAI-compatible
 * `/v1/audio/speech` endpoint (e.g. `piper-http` OpenAI-compatible wrappers). Free, no API key
 * required, runs fully offline.
 *
 * Called directly over HTTP (same reasoning as [OpenAiTextToSpeechService]) so
 * [VoiceConfig.ttsSpeed] can be sent as OpenAI's `speed` field — most OpenAI-compatible Piper
 * wrappers accept and honor it, but servers that don't recognize the field will simply ignore it.
 */
class PiperTextToSpeechService(private val config: VoiceConfig) : TextToSpeechService {
    private val log = logger<PiperTextToSpeechService>()

    override val outputFormat: VoiceAudioFormat = VoiceAudioFormat.WAV

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val baseUrl = config.localTtsEndpoint.trimEnd('/')
        if (baseUrl.isBlank()) {
            throw VoiceServiceException("Local Piper endpoint is not configured. Set it in Settings > Voice.")
        }

        val body = buildJsonObject {
            put("model", config.ttsModel.ifBlank { "tts-1" })
            put("input", text)
            put("voice", config.ttsVoice.ifBlank { "alloy" })
            put("response_format", "wav")
            put("speed", config.ttsSpeed.takeIf { it in 0.25..4.0 } ?: 1.0)
        }.toString()

        try {
            val (status, responseBytes) = httpPostForBytes(
                url = "$baseUrl/v1/audio/speech",
                body = body,
                headers = mapOf("Authorization" to "Bearer ${config.openAiApiKey.ifBlank { "not-needed" }}"),
            )
            if (status !in 200..299) {
                val errorText = String(responseBytes, Charsets.UTF_8)
                throw VoiceServiceException("Local Piper TTS request failed (HTTP $status): $errorText")
            }
            responseBytes
        } catch (e: VoiceServiceException) {
            throw e
        } catch (e: Exception) {
            log.warn("Local Piper TTS request failed", e)
            throw VoiceServiceException(
                e.toFriendlyVoiceErrorMessage("Could not reach local Piper server at $baseUrl"),
                e,
            )
        }
    }
}

object PiperTextToSpeechFactory : TextToSpeechFactory {
    override val provider: VoiceProvider = VoiceProvider.LOCAL_PIPER
    override fun create(config: VoiceConfig): TextToSpeechService = PiperTextToSpeechService(config)
}
