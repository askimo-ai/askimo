/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import io.askimo.core.providers.ModelProvider
import io.askimo.core.util.ApiKeyUtils.safeApiKey
import io.askimo.core.util.createJdkHttpClientBuilder

/**
 * The registered factory for [ModelProvider.OPENAI_COMPATIBLE].
 *
 * Routes model creation to [CompletionsApiDelegate] (`/v1/chat/completions`) or
 * [ResponsesApiDelegate] (`/v1/responses`) based on [OpenAiCompatibleSettings.apiMode],
 * which is user-configurable per instance.
 *
 * HTTP version and API mode are orthogonal: the delegate controls the endpoint surface;
 * [OpenAiCompatibleSettings.httpVersion] controls the transport version.
 */
class OpenAiCompatibleModelFactory : OpenAiCompatibleChatModelFactory<OpenAiCompatibleSettings>() {

    private val completionsApiDelegate = CompletionsApiDelegate()
    private val responsesApiDelegate = ResponsesApiDelegate()

    private fun delegate(settings: OpenAiCompatibleSettings): OpenAiApiDelegate = when (settings.apiMode) {
        OpenAiApiMode.CHAT_COMPLETIONS -> completionsApiDelegate
        OpenAiApiMode.RESPONSES -> responsesApiDelegate
    }

    override fun getProvider(): ModelProvider = ModelProvider.OPENAI_COMPATIBLE
    override fun defaultSettings(): OpenAiCompatibleSettings = OpenAiCompatibleSettings()
    override fun resolveApiKey(settings: OpenAiCompatibleSettings): String = safeApiKey(settings.apiKey.ifBlank { "not-needed" })

    override fun probeThinkingSupport(settings: OpenAiCompatibleSettings): Boolean = delegate(settings).probeThinkingSupport(
        baseUrl = settings.baseUrl,
        apiKey = resolveApiKey(settings),
        modelName = settings.defaultModel,
        httpClientBuilder = createJdkHttpClientBuilder(settings.baseUrl, settings.httpVersion),
        log = log,
    )

    override fun createStreamingModel(settings: OpenAiCompatibleSettings): StreamingChatModel {
        val listener = createTelemetryListener()
        return delegate(settings).createStreamingModel(
            baseUrl = settings.baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = settings.defaultModel,
            httpClientBuilder = createJdkHttpClientBuilder(settings.baseUrl, settings.httpVersion),
            listener = listener,
            provider = getProvider(),
        )
    }

    override fun createSecondaryModel(settings: OpenAiCompatibleSettings): ChatModel {
        val listener = createTelemetryListener()
        val modelName = settings.utilityModel.ifBlank { utilityModelFallback(settings) }
        return delegate(settings).createSecondaryModel(
            baseUrl = settings.baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = modelName,
            httpClientBuilder = createJdkHttpClientBuilder(settings.baseUrl, settings.httpVersion),
            listener = listener,
        )
    }

    override fun createModel(settings: OpenAiCompatibleSettings): ChatModel {
        val listener = createTelemetryListener()
        return delegate(settings).createModel(
            baseUrl = settings.baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = settings.defaultModel,
            httpClientBuilder = createJdkHttpClientBuilder(settings.baseUrl, settings.httpVersion),
            listener = listener,
            provider = getProvider(),
        )
    }

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
