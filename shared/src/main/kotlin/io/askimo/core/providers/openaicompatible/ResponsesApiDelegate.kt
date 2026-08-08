/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiResponsesChatModel
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel
import dev.langchain4j.service.AiServices
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ReasoningEffort
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.core.telemetry.TelemetryChatModelListener
import org.slf4j.Logger

/**
 * Builds models using the OpenAI Responses API (`/v1/responses`) with typed `input[]`
 * content parts and optional reasoning/thinking support.
 *
 * Use when the endpoint explicitly supports `/v1/responses` (native OpenAI, xAI/Grok,
 * or a compatible gateway). Most third-party providers do **not** support this endpoint.
 * This is the default for [OpenAiCompatibleChatModelFactory].
 */
class ResponsesApiDelegate : OpenAiApiDelegate {

    override fun createStreamingModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
        provider: ModelProvider,
    ): StreamingChatModel {
        val supportsThinking = ModelCapabilitiesCache.supportsThinking(provider, modelName)
        return OpenAiResponsesStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .apply {
                val reasoningLevel = ModelCapabilitiesCache.getReasoningLevel(provider, modelName)
                if (supportsThinking && reasoningLevel.isEnabled) {
                    reasoningEffort(reasoningLevel.value)
                    reasoningSummary("detailed")
                }
            }
            .strictTools(true)
            .listeners(listOf(listener))
            .build()
    }

    override fun createSecondaryModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
    ): ChatModel = OpenAiResponsesChatModel.builder()
        .httpClientBuilder(httpClientBuilder)
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .modelName(modelName)
        .listeners(listOf(listener))
        .build()

    override fun createModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
        provider: ModelProvider,
    ): ChatModel {
        val supportsThinking = ModelCapabilitiesCache.supportsThinking(provider, modelName)
        return OpenAiResponsesChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .apply {
                val reasoningLevel = ModelCapabilitiesCache.getReasoningLevel(provider, modelName)
                if (supportsThinking && reasoningLevel.isEnabled) {
                    reasoningEffort(reasoningLevel.value)
                }
            }
            .listeners(listOf(listener))
            .build()
    }

    /**
     * Fires a minimal HTTP probe against the `/v1/responses` endpoint with
     * `reasoning_effort: low` to test if the model accepts reasoning parameters.
     */
    override fun probeThinkingSupport(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        log: Logger,
    ): Boolean = try {
        val testModel = OpenAiResponsesStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .reasoningEffort(ReasoningEffort.LOW.value)
            .build()

        val testClient = AiServices.builder(ChatClient::class.java)
            .streamingChatModel(testModel)
            .build()

        testClient.sendStreamingMessageWithCallback(null, UserMessage("Capability probe — reply with 'ok'."))
        log.info("Model '$modelName' supports thinking — thinking enabled")
        true
    } catch (e: Exception) {
        log.info("Model '$modelName' does not support thinking: ${e.message} — thinking disabled", e)
        false
    }
}
