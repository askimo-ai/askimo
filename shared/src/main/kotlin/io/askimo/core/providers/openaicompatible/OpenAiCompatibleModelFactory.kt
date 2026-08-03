/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.util.ApiKeyUtils.safeApiKey
import java.net.http.HttpClient

/**
 * Intermediate base that adds [shouldProbeThinking] as a stable, non-protected surface so
 * [OpenAiCompatibleModelFactory] can call it on whichever delegate [delegate] returns —
 * without a `when` expression and without touching the visibility of the base-class method.
 */
internal abstract class OpenAiCompatibleDelegateFactory : OpenAiCompatibleChatModelFactory<OpenAiCompatibleSettings>() {

    /** Expose the protected [probeThinkingSupport] to the router as an internal method. */
    internal fun shouldProbeThinking(settings: OpenAiCompatibleSettings): Boolean = probeThinkingSupport(settings)

    /**
     * Read the HTTP version from per-instance [settings] rather than using a class-level constant.
     * Maps [OpenAiHttpVersion] to the JDK [HttpClient.Version] enum.
     */
    override fun httpVersion(settings: OpenAiCompatibleSettings): HttpClient.Version = when (settings.httpVersion) {
        OpenAiHttpVersion.HTTP_1_1 -> HttpClient.Version.HTTP_1_1
        OpenAiHttpVersion.HTTP_2 -> HttpClient.Version.HTTP_2
    }
}

// ── Delegate: Chat Completions API (/v1/chat/completions) ─────────────────────────────────

/**
 * Handles the standard Chat Completions API path.
 * Used for virtually every third-party OpenAI-compatible provider
 * (NVIDIA NIM, OpenRouter, Groq, Together AI, Cloudflare AI, etc.).
 */
internal class OpenAiCompatibleCompletionsModelFactory : OpenAiCompatibleDelegateFactory() {

    override fun getProvider(): ModelProvider = ModelProvider.OPENAI_COMPATIBLE
    override fun defaultSettings(): OpenAiCompatibleSettings = OpenAiCompatibleSettings()
    override fun resolveApiKey(settings: OpenAiCompatibleSettings): String = safeApiKey(settings.apiKey.ifBlank { "not-needed" })

    /** Chat Completions providers don't support /v1/responses — thinking is never available. */
    override fun probeThinkingSupport(settings: OpenAiCompatibleSettings): Boolean = false

    override fun createStreamingModel(settings: OpenAiCompatibleSettings): StreamingChatModel {
        val listener = createTelemetryListener()
        return OpenAiStreamingChatModel.builder()
            .httpClientBuilder(createHttpClientBuilder(settings.baseUrl, listener, httpVersion(settings)))
            .baseUrl(settings.baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(settings.defaultModel)
            .listeners(listOf(listener))
            .build()
    }

    override fun createSecondaryModel(settings: OpenAiCompatibleSettings): ChatModel {
        val listener = createTelemetryListener()
        val modelName = settings.utilityModel
            .ifBlank { utilityModelFallback(settings) }
        return OpenAiChatModel.builder()
            .httpClientBuilder(createHttpClientBuilder(settings.baseUrl, listener, httpVersion(settings)))
            .baseUrl(settings.baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(modelName)
            .listeners(listOf(listener))
            .build()
    }

    override fun createModel(settings: OpenAiCompatibleSettings): ChatModel {
        val listener = createTelemetryListener()
        return OpenAiChatModel.builder()
            .httpClientBuilder(createHttpClientBuilder(settings.baseUrl, listener, httpVersion(settings)))
            .baseUrl(settings.baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(settings.defaultModel)
            .listeners(listOf(listener))
            .build()
    }
}

// ── Delegate: Responses API (/v1/responses) ───────────────────────────────────────────────

/**
 * Handles the OpenAI Responses API path with typed content parts and thinking support.
 * Use when pointing at an endpoint that explicitly supports `/v1/responses`
 * (native OpenAI, or a compatible gateway). Inherits all Responses API model creation
 * from [OpenAiCompatibleChatModelFactory] — no overrides needed.
 */
internal class OpenAiCompatibleResponsesModelFactory : OpenAiCompatibleDelegateFactory() {

    override fun getProvider(): ModelProvider = ModelProvider.OPENAI_COMPATIBLE
    override fun defaultSettings(): OpenAiCompatibleSettings = OpenAiCompatibleSettings()
    override fun resolveApiKey(settings: OpenAiCompatibleSettings): String = safeApiKey(settings.apiKey.ifBlank { "not-needed" })
}

/**
 * The registered factory for [ModelProvider.OPENAI_COMPATIBLE].
 */
class OpenAiCompatibleModelFactory : OpenAiCompatibleChatModelFactory<OpenAiCompatibleSettings>() {

    private val completionsDelegate = OpenAiCompatibleCompletionsModelFactory()
    private val responsesDelegate = OpenAiCompatibleResponsesModelFactory()

    private fun delegate(settings: OpenAiCompatibleSettings): OpenAiCompatibleDelegateFactory = when (settings.apiMode) {
        OpenAiApiMode.CHAT_COMPLETIONS -> completionsDelegate
        OpenAiApiMode.RESPONSES -> responsesDelegate
    }

    override fun getProvider(): ModelProvider = ModelProvider.OPENAI_COMPATIBLE
    override fun defaultSettings(): OpenAiCompatibleSettings = OpenAiCompatibleSettings()
    override fun resolveApiKey(settings: OpenAiCompatibleSettings): String = safeApiKey(settings.apiKey.ifBlank { "not-needed" })

    override fun createStreamingModel(settings: OpenAiCompatibleSettings): StreamingChatModel = delegate(settings).createStreamingModel(settings)

    override fun createSecondaryModel(settings: OpenAiCompatibleSettings): ChatModel = delegate(settings).createSecondaryModel(settings)

    override fun createModel(settings: OpenAiCompatibleSettings): ChatModel = delegate(settings).createModel(settings)

    override fun probeThinkingSupport(settings: OpenAiCompatibleSettings): Boolean = delegate(settings).shouldProbeThinking(settings)

    override fun getNoModelsHelpText(): String = """
        One possible reason is that your server URL or API key is not configured.

        1. Set the Base URL for your OpenAI-compatible server (e.g., http://localhost:8000/v1)
        2. Add an API key if your server requires it
    """.trimIndent()

    override fun createEmbeddingModel(settings: OpenAiCompatibleSettings): EmbeddingModel {
        val modelName = settings.embeddingModel
        check(modelName.isNotBlank()) {
            "No embedding model is configured for ${ModelProvider.OPENAI_COMPATIBLE.name}. " +
                "Go to Settings > AI Provider and select an embedding model under the provider configuration card."
        }
        return OpenAiEmbeddingModelBuilder()
            .baseUrl(settings.baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(modelName)
            .build()
    }

    override fun getEmbeddingTokenLimit(settings: OpenAiCompatibleSettings): Int {
        val modelName = settings.embeddingModel.lowercase()
        return when {
            modelName.contains("text-embedding-3") -> 8191
            modelName.contains("ada-002") -> 8191
            else -> 8191
        }
    }
}
