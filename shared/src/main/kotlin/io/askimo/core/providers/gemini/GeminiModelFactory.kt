/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.gemini

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.Capability
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.googleai.GeminiThinkingConfig
import dev.langchain4j.model.googleai.GeminiThinkingConfig.GeminiThinkingLevel
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.googleai.GoogleAiGeminiImageModel
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel
import dev.langchain4j.model.image.ImageModel
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolProvider
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.logging.logger
import io.askimo.core.providers.AiServiceBuilder
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.GEMINI
import io.askimo.core.providers.ProviderModelUtils.fetchModels
import io.askimo.core.providers.ReasoningEffort
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.core.telemetry.TelemetryChatModelListener
import io.askimo.core.util.ApiKeyUtils.safeApiKey
import io.askimo.core.util.ProxyUtil
import java.net.http.HttpClient
import java.time.Duration

class GeminiModelFactory : ChatModelFactory<GeminiSettings> {

    private val log = logger<GeminiModelFactory>()

    override fun getProvider(): ModelProvider = GEMINI

    override fun availableModels(settings: GeminiSettings): List<ModelDTO> {
        val apiKey = settings.apiKey.takeIf { it.isNotBlank() } ?: return emptyList()
        val url = "${settings.baseUrl.trimEnd('/')}/models"
        return fetchModels(apiKey = apiKey, url = url, providerName = GEMINI)
            .map { ModelDTO.of(GEMINI, it.removePrefix("models/")) }
    }

    override fun defaultSettings(): GeminiSettings = GeminiSettings()

    override fun create(
        sessionId: String?,
        settings: GeminiSettings,
        toolProvider: ToolProvider?,
        retriever: ContentRetriever?,
        executionMode: ExecutionMode,
        chatMemory: ChatMemory?,
    ): ChatClient {
        // Configure HTTP client for thinking probe (probe needs its own builder)
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)

        // Probe thinking support once — result is persisted in ModelCapabilitiesCache
        if (!ModelCapabilitiesCache.hasTestedThinkingSupport(GEMINI, settings.defaultModel)) {
            val supportsThinking = probeThinkingSupport(settings, jdkHttpClientBuilder)
            ModelCapabilitiesCache.setThinkingSupport(GEMINI, settings.defaultModel, supportsThinking)
        }

        // Create streaming model once — reused for both the tool probe and the real client
        val streamingModel = createStreamingModel(settings)

        // Probe tool support once — result is persisted in ModelCapabilitiesCache
        if (executionMode.isToolEnabled() &&
            !ModelCapabilitiesCache.hasTestedToolSupport(GEMINI, settings.defaultModel)
        ) {
            val supportsTools = probeToolSupport(settings.defaultModel, streamingModel, executionMode)
            ModelCapabilitiesCache.setToolSupport(GEMINI, settings.defaultModel, supportsTools)
        }

        return AiServiceBuilder.buildChatClient(
            sessionId = sessionId,
            settings = settings,
            provider = GEMINI,
            chatModel = streamingModel,
            secondaryChatModel = createSecondaryModel(settings),
            chatMemory = chatMemory,
            toolProvider = toolProvider,
            retriever = retriever,
        )
    }

    /**
     * Probes whether the model supports thinking by building a minimal thinking-enabled
     * streaming client and sending a test message. Returns true on success, false if the
     * API rejects the thinking configuration.
     *
     * This is called only once per model — the result is cached in [ModelCapabilitiesCache].
     */
    private fun probeThinkingSupport(
        settings: GeminiSettings,
        jdkHttpClientBuilder: JdkHttpClientBuilder,
    ): Boolean = try {
        val testModel = GoogleAiGeminiStreamingChatModel
            .builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(settings.defaultModel)
            .thinkingConfig(
                GeminiThinkingConfig.builder()
                    .thinkingLevel(GeminiThinkingLevel.LOW)
                    .build(),
            )
            .sendThinking(true)
            .returnThinking(true)
            .build()

        val testClient = AiServices.builder(ChatClient::class.java)
            .streamingChatModel(testModel)
            .build()

        testClient.sendStreamingMessageWithCallback(null, UserMessage("Capability probe — reply with 'ok'."))
        log.info("Model '${settings.defaultModel}' supports thinking — thinking enabled")
        true
    } catch (e: Exception) {
        log.info("Model '${settings.defaultModel}' does not support thinking: ${e.message} — thinking disabled")
        false
    }

    override fun createImageModel(
        settings: GeminiSettings,
    ): ImageModel = GoogleAiGeminiImageModel.builder()
        .apiKey(safeApiKey(settings.apiKey))
        .baseUrl(settings.baseUrl)
        .modelName(AppConfig.models[GEMINI].imageModel)
        .logger(log)
        .logRequests(log.isDebugEnabled)
        .logResponses(log.isTraceEnabled)
        .build()

    override fun createStreamingModel(settings: GeminiSettings): StreamingChatModel {
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)
        val telemetry = AppContext.getInstance().telemetry

        val supportsThinking = ModelCapabilitiesCache.supportsThinking(GEMINI, settings.defaultModel)
        val reasoningLevel = ModelCapabilitiesCache.getReasoningLevel(GEMINI, settings.defaultModel)

        return GoogleAiGeminiStreamingChatModel.builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(settings.defaultModel)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
            .listeners(listOf(TelemetryChatModelListener(telemetry, GEMINI.name.lowercase())))
            .apply {
                if (supportsThinking && reasoningLevel.isEnabled) {
                    val geminiLevel = when (reasoningLevel) {
                        ReasoningEffort.LOW -> GeminiThinkingLevel.LOW
                        ReasoningEffort.HIGH -> GeminiThinkingLevel.HIGH
                        else -> GeminiThinkingLevel.MEDIUM
                    }
                    thinkingConfig(
                        GeminiThinkingConfig.builder()
                            .thinkingLevel(geminiLevel)
                            .build(),
                    )
                    sendThinking(true)
                    returnThinking(true)
                }
            }
            .build()
    }

    override fun createSecondaryModel(settings: GeminiSettings): ChatModel {
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)
        return GoogleAiGeminiChatModel.builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(AppConfig.models[GEMINI].utilityModel.ifBlank { settings.defaultModel })
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.utilityModelTimeoutSeconds))
            .build()
    }

    override fun createModel(settings: GeminiSettings): ChatModel {
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)

        return GoogleAiGeminiChatModel.builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(settings.defaultModel)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
            .build()
    }

    override fun createUtilityClient(
        settings: GeminiSettings,
    ): ChatClient = AiServices.builder(ChatClient::class.java)
        .chatModel(createSecondaryModel(settings))
        .build()

    override fun supportsEmbedding(): Boolean = true

    override fun createEmbeddingModel(settings: GeminiSettings): EmbeddingModel = GoogleAiEmbeddingModel.builder()
        .apiKey(safeApiKey(settings.apiKey))
        .modelName(AppConfig.models[GEMINI].embeddingModel)
        .build()

    override fun getEmbeddingTokenLimit(settings: GeminiSettings): Int {
        val modelName = AppConfig.models[GEMINI].embeddingModel.lowercase()
        return when {
            modelName.contains("embedding-001") -> 2048
            modelName.contains("text-embedding-004") -> 2048
            else -> 2048
        }
    }
}
