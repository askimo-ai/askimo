/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.image.ImageModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import dev.langchain4j.model.openai.OpenAiImageModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolProvider
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.telemetry.TelemetryChatModelListener
import io.askimo.core.util.ProxyUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.time.Duration

/**
 * Abstract base factory for all OpenAI-compatible API providers.
 *
 * Subclasses must implement [getProvider] and [defaultSettings].
 * Everything else has a sensible default that can be selectively overridden.
 *
 * @param T Provider-specific settings. Must implement both [ProviderSettings] and [HasBaseUrl].
 */
abstract class OpenAiCompatibleChatModelFactory<T> : ChatModelFactory<T>
    where T : ProviderSettings, T : HasBaseUrl {

    /**
     * Logger named after the concrete subclass so log output identifies the right factory.
     * Resolved at construction time using the runtime class.
     */
    protected val log: Logger = LoggerFactory.getLogger(this::class.java)

    // ── Template-method hooks ──────────────────────────────────────────────────

    /**
     * Returns the API key sent in every HTTP request to this provider.
     *
     * Local providers (Ollama, LocalAI, LmStudio, Docker AI) don't require a real key but
     * include one as a placeholder to satisfy defensive null/blank checks in certain server
     * implementations. The default value `"not-needed"` is safe for all such servers.
     *
     * Cloud providers (OpenAI-compatible with auth, xAI/Grok) override this to return the
     * real key from [settings].
     */
    protected open fun resolveApiKey(settings: T): String = "not-needed"

    /**
     * HTTP protocol version used for all connections to this provider.
     *
     * Defaults to [HttpClient.Version.HTTP_2]. Override to [HttpClient.Version.HTTP_1_1]
     * for servers that do not support HTTP/2 (e.g., LmStudio, Docker AI).
     */
    protected open fun httpVersion(): HttpClient.Version = HttpClient.Version.HTTP_2

    /**
     * Fallback model name used for the secondary/utility model when no explicit utility model
     * is configured in [AppConfig].
     *
     * Defaults to [ProviderSettings.defaultModel]. Override for providers where the active
     * model is tracked elsewhere (e.g., LmStudio and Docker AI read from AppContext.params.model).
     */
    protected open fun utilityModelFallback(settings: T): String = settings.defaultModel

    /**
     * Guards the model-list fetch in [availableModels].
     *
     * Return `false` to skip the HTTP call and return an empty list immediately.
     * Use this when a prerequisite (e.g., base URL or API key) is not yet configured.
     */
    protected open fun canFetchModels(settings: T): Boolean = true

    /**
     * Template method for embedding model availability verification.
     *
     * **Local providers** (Ollama, LocalAI, LmStudio, Docker AI) override this to call
     * [ensureLocalEmbeddingModelAvailable], which verifies the server is reachable and the
     * requested model is pulled/available.
     *
     * **Remote / cloud providers** leave this as a no-op — the embedding endpoint is assumed
     * to be available whenever the credentials are valid.
     */
    protected open fun checkEmbeddingAvailability(baseUrl: String, modelName: String) { /* no-op for remote providers */ }

    /**
     * Hook to apply additional configuration to the [OpenAiEmbeddingModelBuilder] before [build].
     *
     * The default is an identity transform (no changes).
     * Override when the provider needs extra builder settings for embeddings
     * (e.g., LmStudio injects an HTTP/1.1 client via [createHttpClientBuilder]).
     */
    protected open fun customizeEmbeddingBuilder(
        settings: T,
        builder: OpenAiEmbeddingModelBuilder,
    ): OpenAiEmbeddingModelBuilder = builder

    /**
     * Probe whether the current model supports thinking/reasoning capabilities.
     *
     * Default implementation is intentionally conservative and returns false.
     * Providers can override this with a real probe request.
     */
    protected open fun probeThinkingSupport(settings: T): Boolean = false

    // ── Shared helpers ─────────────────────────────────────────────────────────

    /**
     * Creates a JdkHttpClient builder configured with the correct HTTP version and proxy
     * settings for this provider. Proxy is automatically bypassed for localhost URLs.
     */
    protected fun createHttpClientBuilder(baseUrl: String) = JdkHttpClient.builder().httpClientBuilder(
        ProxyUtil.configureProxy(
            HttpClient.newBuilder().version(httpVersion()),
            baseUrl,
        ),
    )

    // ── ChatModelFactory implementation ────────────────────────────────────────

    override fun availableModels(settings: T): List<ModelDTO> {
        if (!canFetchModels(settings)) return emptyList()
        return ProviderModelUtils.fetchModels(
            apiKey = resolveApiKey(settings),
            url = "${settings.baseUrl.trimEnd('/')}/models",
            providerName = getProvider(),
            httpVersion = httpVersion(),
        ).map { ModelDTO.of(getProvider(), it) }
    }

    override fun create(
        sessionId: String?,
        settings: T,
        toolProvider: ToolProvider?,
        retriever: ContentRetriever?,
        executionMode: ExecutionMode,
        chatMemory: ChatMemory?,
    ): ChatClient {
        if (!ModelCapabilitiesCache.hasTestedThinkingSupport(getProvider(), settings.defaultModel)) {
            val supportsThinking = probeThinkingSupport(settings)
            ModelCapabilitiesCache.setThinkingSupport(getProvider(), settings.defaultModel, supportsThinking)
        }

        return AiServiceBuilder.buildChatClient(
            sessionId = sessionId,
            settings = settings,
            provider = getProvider(),
            chatModel = createStreamingModel(settings),
            secondaryChatModel = createSecondaryModel(settings),
            chatMemory = chatMemory,
            toolProvider = toolProvider,
            retriever = retriever,
            executionMode = executionMode,
        )
    }

    override fun createStreamingModel(settings: T): StreamingChatModel {
        val telemetry = AppContext.getInstance().telemetry
        return OpenAiStreamingChatModel.builder()
            .httpClientBuilder(createHttpClientBuilder(settings.baseUrl))
            .baseUrl(settings.baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(settings.defaultModel)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
            .logger(log)
            .logRequests(log.isDebugEnabled)
            .logResponses(log.isTraceEnabled)
            .listeners(listOf(TelemetryChatModelListener(telemetry, getProvider().providerKey())))
            .build()
    }

    override fun createSecondaryModel(settings: T): ChatModel = OpenAiChatModel.builder()
        .httpClientBuilder(createHttpClientBuilder(settings.baseUrl))
        .baseUrl(settings.baseUrl)
        .apiKey(resolveApiKey(settings))
        .modelName(AppConfig.models[getProvider()].utilityModel.ifBlank { utilityModelFallback(settings) })
        .timeout(Duration.ofSeconds(AppConfig.models.timeouts.utilityModelTimeoutSeconds))
        .logger(log)
        .logRequests(log.isDebugEnabled)
        .build()

    override fun createModel(settings: T): ChatModel {
        val telemetry = AppContext.getInstance().telemetry
        return OpenAiChatModel.builder()
            .httpClientBuilder(createHttpClientBuilder(settings.baseUrl))
            .baseUrl(settings.baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(settings.defaultModel)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
            .logger(log)
            .logRequests(log.isDebugEnabled)
            .logResponses(log.isTraceEnabled)
            .listeners(listOf(TelemetryChatModelListener(telemetry, getProvider().providerKey())))
            .build()
    }

    override fun createImageModel(settings: T): ImageModel = OpenAiImageModel.builder()
        .baseUrl(settings.baseUrl)
        .apiKey(resolveApiKey(settings))
        .modelName(AppConfig.models[getProvider()].imageModel)
        .logger(log)
        .logRequests(log.isDebugEnabled)
        .logResponses(log.isTraceEnabled)
        .build()

    override fun createUtilityClient(settings: T): ChatClient = AiServices.builder(ChatClient::class.java)
        .chatModel(createSecondaryModel(settings))
        .build()

    override fun supportsEmbedding(): Boolean = true

    override fun createEmbeddingModel(settings: T): EmbeddingModel {
        val baseUrl = settings.baseUrl.removeSuffix("/")
        val modelName = AppConfig.models[getProvider()].embeddingModel
        checkEmbeddingAvailability(baseUrl, modelName)
        return customizeEmbeddingBuilder(
            settings,
            OpenAiEmbeddingModelBuilder()
                .apiKey("not-needed")
                .baseUrl(baseUrl)
                .modelName(modelName),
        ).build()
    }

    override fun getEmbeddingTokenLimit(settings: T): Int = LocalEmbeddingTokenLimits.resolve(AppConfig.models[getProvider()].embeddingModel)
}
