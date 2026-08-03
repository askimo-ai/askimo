/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiResponsesChatModel
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.service.AiServices
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ReasoningEffort
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.core.telemetry.TelemetryChatModelListener
import org.slf4j.Logger

/**
 * Strategy interface that encapsulates which OpenAI API surface a provider targets
 * and whether thinking/reasoning probing is supported.
 *
 * Injected into [OpenAiCompatibleChatModelFactory] at
 * construction time so that HTTP-version handling (orthogonal, lives in settings)
 * and API-surface selection are both cleanly separated from the factory lifecycle.
 *
 * Two concrete implementations:
 * - [CompletionsApiDelegate] — `/v1/chat/completions`; thinking always `false`
 * - [ResponsesApiDelegate]  — `/v1/responses`; thinking probed via HTTP on first use
 */
sealed interface OpenAiApiDelegate {

    /**
     * Creates the primary streaming model for the conversation loop.
     * The [httpClientBuilder] is already configured with the correct HTTP version,
     * proxy, and timeouts by the calling factory.
     */
    fun createStreamingModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
        provider: ModelProvider,
    ): StreamingChatModel

    /**
     * Creates the non-streaming secondary (utility) model for cheap background tasks
     * (RAG compression, title generation, intent classification, etc.).
     */
    fun createSecondaryModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
    ): ChatModel

    /**
     * Creates a non-streaming model matching [createStreamingModel]'s configuration
     * (same model name, same reasoning settings where applicable).
     */
    fun createModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
        provider: ModelProvider,
    ): ChatModel

    /**
     * Probes whether the model/endpoint supports thinking/reasoning.
     *
     * [CompletionsApiDelegate] returns `false` immediately — the `/v1/chat/completions`
     * surface has no reasoning support. [ResponsesApiDelegate] fires a minimal HTTP probe
     * against the `/v1/responses` endpoint, caching the result via [ModelCapabilitiesCache].
     */
    fun probeThinkingSupport(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        log: Logger,
    ): Boolean
}

// ── Delegate: Chat Completions API (/v1/chat/completions) ────────────────────────────────

/**
 * Builds models using the standard `/v1/chat/completions` endpoint.
 *
 * Use for virtually every third-party OpenAI-compatible provider (NVIDIA NIM, OpenRouter,
 * Groq, Together AI, Cloudflare AI, Ollama, LM Studio, Docker AI, LocalAI, etc.).
 * Thinking is hard-wired to `false` — the Completions surface has no reasoning support.
 */
class CompletionsApiDelegate : OpenAiApiDelegate {

    override fun createStreamingModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
        provider: ModelProvider,
    ): StreamingChatModel = OpenAiStreamingChatModel.builder()
        .httpClientBuilder(httpClientBuilder)
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .modelName(modelName)
        .listeners(listOf(listener))
        .build()

    override fun createSecondaryModel(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        listener: TelemetryChatModelListener,
    ): ChatModel = OpenAiChatModel.builder()
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
    ): ChatModel = OpenAiChatModel.builder()
        .httpClientBuilder(httpClientBuilder)
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .modelName(modelName)
        .listeners(listOf(listener))
        .build()

    /** Chat Completions surface has no reasoning support — always returns `false`. */
    override fun probeThinkingSupport(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        log: Logger,
    ): Boolean = false
}

// ── Delegate: Responses API (/v1/responses) ─────────────────────────────────────────────

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
