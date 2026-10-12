/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderModelUtils
import io.askimo.core.providers.llama.LlamaServerProcessManager
import io.askimo.core.util.ApiKeyUtils.safeApiKey
import io.askimo.core.util.createJdkHttpClientBuilder
import io.askimo.core.util.toJdkVersion

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

    /**
     * Resolves the base URL to actually connect to for [settings].
     *
     * For every template except [OpenAiCompatibleTemplate.ASKIMO_LOCAL_LLM], this is simply the
     * persisted [OpenAiCompatibleSettings.baseUrl].
     *
     * [OpenAiCompatibleTemplate.ASKIMO_LOCAL_LLM] is special: [LlamaServerProcessManager] binds
     * its reverse proxy to a freshly chosen free port on every app launch (to avoid clashing with
     * a user-run Ollama/LM Studio). The persisted `baseUrl` is just a stale snapshot of that port,
     * so this always reads the *live* [LlamaServerProcessManager.proxyBaseUrl] instead.
     */
    private fun effectiveBaseUrl(settings: OpenAiCompatibleSettings): String = if (settings.isTemplate && settings.templateName == OpenAiCompatibleTemplate.ASKIMO_LOCAL_LLM.name) {
        LlamaServerProcessManager.proxyBaseUrl
    } else {
        settings.baseUrl
    }

    override fun getProvider(): ModelProvider = ModelProvider.OPENAI_COMPATIBLE
    override fun defaultSettings(): OpenAiCompatibleSettings = OpenAiCompatibleSettings()
    override fun resolveApiKey(settings: OpenAiCompatibleSettings): String = safeApiKey(settings.apiKey.ifBlank { "not-needed" })

    override fun availableModels(settings: OpenAiCompatibleSettings): List<ModelDTO> {
        if (!canFetchModels(settings)) return emptyList()
        val baseUrl = effectiveBaseUrl(settings)
        val template = settings.templateName
            ?.let { name -> OpenAiCompatibleTemplate.entries.find { it.name == name } }
        val modelIds = if (template?.modelFetcher != null) {
            template.modelFetcher(
                resolveApiKey(settings),
                baseUrl,
                settings.httpVersion.toJdkVersion(),
            )
        } else {
            ProviderModelUtils.fetchModels(
                apiKey = resolveApiKey(settings),
                url = "${baseUrl.trimEnd('/')}/models",
                providerName = getProvider(),
                httpVersion = settings.httpVersion.toJdkVersion(),
            )
        }
        return modelIds.map { ModelDTO.of(getProvider(), it) }
    }

    override fun canFetchModels(settings: OpenAiCompatibleSettings): Boolean {
        if (settings.baseUrl.isBlank()) return false
        val template = settings.templateName
            ?.let { name -> OpenAiCompatibleTemplate.entries.find { it.name == name } }
        // For templates that don't require an API key (Ollama, LM Studio, etc.) we can
        // fetch models without one. For unknown / cloud templates an API key is required.
        val apiKeyNeeded = template?.apiKeyRequired ?: false
        return !apiKeyNeeded || settings.apiKey.isNotBlank()
    }

    override fun probeThinkingSupport(settings: OpenAiCompatibleSettings): Boolean {
        val baseUrl = effectiveBaseUrl(settings)
        return delegate(settings).probeThinkingSupport(
            baseUrl = baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = settings.defaultModel,
            httpClientBuilder = createJdkHttpClientBuilder(baseUrl, settings.httpVersion),
            log = log,
        )
    }

    override fun createStreamingModel(settings: OpenAiCompatibleSettings): StreamingChatModel {
        val listener = createTelemetryListener()
        val baseUrl = effectiveBaseUrl(settings)
        return delegate(settings).createStreamingModel(
            baseUrl = baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = settings.defaultModel,
            httpClientBuilder = createJdkHttpClientBuilder(baseUrl, settings.httpVersion),
            listener = listener,
            provider = getProvider(),
        )
    }

    override fun createSecondaryModel(settings: OpenAiCompatibleSettings): ChatModel {
        val listener = createTelemetryListener()
        val modelName = settings.utilityModel.ifBlank { utilityModelFallback(settings) }
        val baseUrl = effectiveBaseUrl(settings)
        return delegate(settings).createSecondaryModel(
            baseUrl = baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = modelName,
            httpClientBuilder = createJdkHttpClientBuilder(baseUrl, settings.httpVersion),
            listener = listener,
        )
    }

    override fun createModel(settings: OpenAiCompatibleSettings): ChatModel {
        val listener = createTelemetryListener()
        val baseUrl = effectiveBaseUrl(settings)
        return delegate(settings).createModel(
            baseUrl = baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = settings.defaultModel,
            httpClientBuilder = createJdkHttpClientBuilder(baseUrl, settings.httpVersion),
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
        val baseUrl = effectiveBaseUrl(settings)
        return OpenAiEmbeddingModelBuilder()
            .baseUrl(baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(modelName)
            .build()
    }
}
