/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl

import dev.langchain4j.data.audio.Audio
import dev.langchain4j.model.audio.AudioTranscriptionRequest
import dev.langchain4j.model.openai.OpenAiAudioTranscriptionModel
import io.askimo.core.config.AppConfig
import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.logging.logger
import io.askimo.core.util.createJdkHttpClientBuilder
import io.askimo.ui.voice.SpeechToTextFactory
import io.askimo.ui.voice.SpeechToTextService
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

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
                .httpClientProvider(createJdkHttpClientBuilder())
                .apiKey(apiKey)
                .modelName(config.sttModel.ifBlank { "whisper-1" })
                .build()
            model.transcribe(
                AudioTranscriptionRequest.builder().audio(
                    Audio.builder().base64Data(Base64.getEncoder().encodeToString(audio)).mimeType("audio/wav").build(),
                ).language(whisperLanguageCode()).build(),
            ).text()
        } catch (e: Exception) {
            log.warn("OpenAI transcription request failed", e)
            throw VoiceServiceException("OpenAI transcription request failed: ${e.message}", e)
        }
    }

    /**
     * Whisper's `/v1/audio/transcriptions` API requires a plain ISO-639-1 language code
     * (e.g. `"vi"`, `"en"`), not a full BCP-47 tag like `"vi-VN"`. Strip any region/script
     * subtag from [AppConfig.currentLocale] before passing it along.
     */
    private fun whisperLanguageCode(): String = (AppConfig.currentLocale ?: "en").substringBefore('-').lowercase().ifBlank { "en" }
}

object OpenAiSpeechToTextFactory : SpeechToTextFactory {
    override val provider: VoiceProvider = VoiceProvider.OPENAI
    override fun create(config: VoiceConfig): SpeechToTextService = OpenAiSpeechToTextService(config)
}
