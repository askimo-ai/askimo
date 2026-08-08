/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import io.askimo.core.providers.ModelProvider
import io.askimo.core.telemetry.TelemetryChatModelListener
import org.slf4j.Logger

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
