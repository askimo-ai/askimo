/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl

import dev.langchain4j.data.audio.Audio
import dev.langchain4j.model.openai.OpenAiAudioTranscriptionModel
import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.logging.logger
import io.askimo.ui.voice.SpeechToTextFactory
import io.askimo.ui.voice.SpeechToTextService
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Speech-to-text via OpenAI's Whisper API (`/v1/audio/transcriptions`), using langchain4j's
 * [OpenAiAudioTranscriptionModel].
 *
 * Uses [VoiceConfig.openAiApiKey] — a key **separate** from any `OPENAI`
 * [io.askimo.core.providers.ProviderInstance] — and [VoiceConfig.sttModel] (default `whisper-1`).
 */
class OpenAiSpeechToTextService(private val config: VoiceConfig) : SpeechToTextService {
    private val log = logger<OpenAiSpeechToTextService>()

    override suspend fun transcribe(audio: ByteArray, format: VoiceAudioFormat): String = withContext(Dispatchers.IO) {
        val apiKey = config.openAiApiKey
        if (apiKey.isBlank()) {
            throw VoiceServiceException("OpenAI API key for voice is not configured. Set it in Settings > Voice.")
        }

        try {
            val model = OpenAiAudioTranscriptionModel.builder()
                .apiKey(apiKey)
                .modelName(config.sttModel.ifBlank { "whisper-1" })
                .build()

            model.transcribeToText(Audio.builder().binaryData(audio).build())
        } catch (e: Exception) {
            log.warn("OpenAI transcription request failed", e)
            throw VoiceServiceException("OpenAI transcription request failed: ${e.message}", e)
        }
    }
}

object OpenAiSpeechToTextFactory : SpeechToTextFactory {
    override val provider: VoiceProvider = VoiceProvider.OPENAI
    override fun create(config: VoiceConfig): SpeechToTextService = OpenAiSpeechToTextService(config)
}

/**
 * Speech-to-text via a user-hosted whisper.cpp / faster-whisper server exposing an
 * OpenAI-compatible `/v1/audio/transcriptions` endpoint (e.g. `whisper-server`,
 * `faster-whisper-server`). Free, no API key required, runs fully offline.
 *
 * Uses langchain4j's [OpenAiAudioTranscriptionModel] pointed at a custom [VoiceConfig.localSttEndpoint]
 * base URL (default `http://localhost:8081`) — the same "OpenAI-compatible" pattern used by
 * [io.askimo.core.providers.openaicompatible.OpenAiCompatibleModelFactory] for chat/embedding models.
 */
class LocalWhisperSpeechToTextService(private val config: VoiceConfig) : SpeechToTextService {
    private val log = logger<LocalWhisperSpeechToTextService>()

    override suspend fun transcribe(audio: ByteArray, format: VoiceAudioFormat): String = withContext(Dispatchers.IO) {
        val baseUrl = config.localSttEndpoint.trimEnd('/')
        if (baseUrl.isBlank()) {
            throw VoiceServiceException("Local whisper.cpp endpoint is not configured. Set it in Settings > Voice.")
        }

        try {
            val model = OpenAiAudioTranscriptionModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.openAiApiKey.ifBlank { "not-needed" })
                .modelName(config.sttModel.ifBlank { "whisper-1" })
                .build()

            model.transcribeToText(Audio.builder().binaryData(audio).build())
        } catch (e: Exception) {
            log.warn("Local whisper transcription request failed", e)
            throw VoiceServiceException(
                "Could not reach local whisper.cpp server at $baseUrl. Is it running? (${e.message})",
                e,
            )
        }
    }
}

object LocalWhisperSpeechToTextFactory : SpeechToTextFactory {
    override val provider: VoiceProvider = VoiceProvider.LOCAL_WHISPER_CPP
    override fun create(config: VoiceConfig): SpeechToTextService = LocalWhisperSpeechToTextService(config)
}
