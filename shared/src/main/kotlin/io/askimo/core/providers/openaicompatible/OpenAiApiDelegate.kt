/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import io.askimo.core.providers.ModelProvider
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
     * against the `/v1/responses` endpoint, caching the result via `ModelCapabilitiesCache`.
     */
    fun probeThinkingSupport(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        httpClientBuilder: JdkHttpClientBuilder,
        log: Logger,
    ): Boolean
}
