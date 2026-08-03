/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.image.ImageModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import dev.langchain4j.model.openai.OpenAiImageModel
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolProvider
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.providers.AiServiceBuilder
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.HasBaseUrl
import io.askimo.core.providers.LocalEmbeddingTokenLimits
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ProviderModelUtils
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.telemetry.TelemetryChatModelListener
import io.askimo.core.util.ProxyUtil
import io.askimo.core.util.toJdkVersion
import io.askimo.core.util.withLoggingIfDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.time.Duration

/**
 * Abstract base factory for all OpenAI-compatible API providers.
 *
 * Subclasses must implement [getProvider] and [io.askimo.core.providers.ChatModelFactory.defaultSettings].
 * The [apiDelegate] selects which OpenAI API surface is used ([ResponsesApiDelegate] for
 * `/v1/responses`, [CompletionsApiDelegate] for
 * `/v1/chat/completions`) and owns `probeThinkingSupport`.
 * HTTP version is declared per-provider via [io.askimo.core.providers.ProviderSettings.httpVersion].
 *
 * @param T Provider-specific settings. Must implement both [io.askimo.core.providers.ProviderSettings] and [io.askimo.core.providers.HasBaseUrl].
 * @param apiDelegate Strategy that builds models and probes thinking support. Defaults to
 * [ResponsesApiDelegate]; pass [CompletionsApiDelegate]
 * for providers that target `/v1/chat/completions`.
 */
abstract class OpenAiCompatibleChatModelFactory<T>(
    protected val apiDelegate: OpenAiApiDelegate = ResponsesApiDelegate(),
) : ChatModelFactory<T>
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
     * [io.askimo.core.providers.ensureLocalEmbeddingModelAvailable], which verifies the server is reachable and the
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
     * Delegates thinking-support probing to [apiDelegate].
     * [CompletionsApiDelegate] returns `false`
     * immediately; [ResponsesApiDelegate] fires a live HTTP probe.
     * Result is cached by the calling [create] method.
     */
    protected open fun probeThinkingSupport(settings: T): Boolean = apiDelegate.probeThinkingSupport(
        baseUrl = settings.baseUrl,
        apiKey = resolveApiKey(settings),
        modelName = settings.defaultModel,
        httpClientBuilder = createHttpClientBuilder(settings.baseUrl, httpVersion = settings.httpVersion.toJdkVersion()),
        log = log,
    )

    // ── Shared helpers ─────────────────────────────────────────────────────────

    /**
     * Creates a [JdkHttpClient] builder configured with the correct HTTP version and proxy
     * settings for this provider. Proxy is automatically bypassed for localhost URLs.
     *
     * [listener] is provided when the HTTP client should be wired to a specific
     * [TelemetryChatModelListener] instance — e.g. to inject per-request headers that are
     * derived from that listener. The default implementation ignores it.
     *
     * The HTTP version defaults to [HttpClient.Version.HTTP_2]; callers pass
     * `settings.httpVersion.toJdkVersion()` to respect the per-provider/per-instance setting.
     */
    protected open fun createHttpClientBuilder(
        baseUrl: String,
        listener: TelemetryChatModelListener? = null,
        httpVersion: HttpClient.Version = HttpClient.Version.HTTP_2,
    ) = JdkHttpClient.builder().httpClientBuilder(
        ProxyUtil.configureProxy(
            HttpClient.newBuilder().version(httpVersion),
            baseUrl,
        ).withLoggingIfDebug(),
    ).readTimeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
        .connectTimeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))

    /**
     * Creates the [TelemetryChatModelListener] attached to every model built by this factory.
     */
    protected open fun createTelemetryListener(): TelemetryChatModelListener = TelemetryChatModelListener(AppContext.getInstance().telemetry, getProvider().providerKey())

    // ── ChatModelFactory implementation ────────────────────────────────────────

    override fun availableModels(settings: T): List<ModelDTO> {
        if (!canFetchModels(settings)) return emptyList()
        return ProviderModelUtils.fetchModels(
            apiKey = resolveApiKey(settings),
            url = "${settings.baseUrl.trimEnd('/')}/models",
            providerName = getProvider(),
            httpVersion = settings.httpVersion.toJdkVersion(),
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
        // Probe thinking support once — result is persisted in ModelCapabilitiesCache
        if (!ModelCapabilitiesCache.hasTestedThinkingSupport(getProvider(), settings.defaultModel)) {
            val supportsThinking = probeThinkingSupport(settings)
            ModelCapabilitiesCache.setThinkingSupport(getProvider(), settings.defaultModel, supportsThinking)
        }

        // Create streaming model once — reused for both the tool probe and the real client
        val streamingModel = createStreamingModel(settings)

        // Probe tool support once — run async so it never blocks the caller.
        // Optimistically mark as true (supported) so tools are available immediately;
        // the background probe will call setToolSupport() with the real result,
        // which fires ToolSupportDetectedEvent so the UI reacts if the model rejects tools.
        if (executionMode.isToolEnabled() &&
            !ModelCapabilitiesCache.hasTestedToolSupport(getProvider(), settings.defaultModel)
        ) {
            ModelCapabilitiesCache.setToolSupport(getProvider(), settings.defaultModel, true)
            val provider = getProvider()
            val modelName = settings.defaultModel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val supportsTools = probeToolSupport(modelName, streamingModel, executionMode)
                ModelCapabilitiesCache.setToolSupport(provider, modelName, supportsTools)
            }
        }

        // Probe image generation capability once — run async so it never blocks the caller.
        // Optimistically mark as false (not supported) so the UI is usable immediately;
        // the background probe will call setImageSupport() again with the real result,
        // which fires ImageCapabilityDetectedEvent so the UI reacts without a restart.
        if (!ModelCapabilitiesCache.hasTestedImageSupport(getProvider(), settings.defaultModel)) {
            ModelCapabilitiesCache.setImageSupport(getProvider(), settings.defaultModel, false)
            val provider = getProvider()
            val modelName = settings.defaultModel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val supportsImage = probeImageCapability(provider, modelName, streamingModel)
                ModelCapabilitiesCache.setImageSupport(provider, modelName, supportsImage)
            }
        }

        return AiServiceBuilder.buildChatClient(
            sessionId = sessionId,
            settings = settings,
            provider = getProvider(),
            chatModel = streamingModel,
            secondaryChatModel = createSecondaryModel(settings),
            chatMemory = chatMemory,
            toolProvider = toolProvider,
            retriever = retriever,
        )
    }

    override fun createStreamingModel(settings: T): StreamingChatModel {
        val listener = createTelemetryListener()
        return apiDelegate.createStreamingModel(
            baseUrl = settings.baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = settings.defaultModel,
            httpClientBuilder = createHttpClientBuilder(settings.baseUrl, listener, settings.httpVersion.toJdkVersion()),
            listener = listener,
            provider = getProvider(),
        )
    }

    override fun createSecondaryModel(settings: T): ChatModel {
        val listener = createTelemetryListener()
        val modelName = settings.utilityModel.ifBlank { utilityModelFallback(settings) }
        return apiDelegate.createSecondaryModel(
            baseUrl = settings.baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = modelName,
            httpClientBuilder = createHttpClientBuilder(settings.baseUrl, listener, settings.httpVersion.toJdkVersion()),
            listener = listener,
        )
    }

    override fun createModel(settings: T): ChatModel {
        val listener = createTelemetryListener()
        return apiDelegate.createModel(
            baseUrl = settings.baseUrl,
            apiKey = resolveApiKey(settings),
            modelName = settings.defaultModel,
            httpClientBuilder = createHttpClientBuilder(settings.baseUrl, listener, settings.httpVersion.toJdkVersion()),
            listener = listener,
            provider = getProvider(),
        )
    }

    override fun createImageModel(settings: T): ImageModel = OpenAiImageModel.builder()
        .baseUrl(settings.baseUrl)
        .apiKey(resolveApiKey(settings))
        .modelName(settings.imageModel)
        .build()

    override fun createUtilityClient(settings: T): ChatClient = AiServices.builder(ChatClient::class.java)
        .chatModel(createSecondaryModel(settings))
        .build()

    override fun supportsEmbedding(): Boolean = true

    override fun createEmbeddingModel(settings: T): EmbeddingModel {
        val baseUrl = settings.baseUrl.removeSuffix("/")
        val modelName = settings.embeddingModel
        check(modelName.isNotBlank()) {
            "No embedding model is configured for ${getProvider().name}. " +
                "Go to Settings > AI Provider and select an embedding model under the provider configuration card."
        }
        checkEmbeddingAvailability(baseUrl, modelName)
        return customizeEmbeddingBuilder(
            settings,
            OpenAiEmbeddingModelBuilder()
                .apiKey("not-needed")
                .baseUrl(baseUrl)
                .modelName(modelName),
        ).build()
    }

    override fun getEmbeddingTokenLimit(settings: T): Int = LocalEmbeddingTokenLimits.resolve(
        settings.embeddingModel,
    )
}
