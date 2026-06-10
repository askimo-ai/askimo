/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.anthropic

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.image.ImageModel
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolProvider
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.logging.displayError
import io.askimo.core.logging.logger
import io.askimo.core.providers.AiServiceBuilder
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.core.telemetry.TelemetryChatModelListener
import io.askimo.core.util.ApiKeyUtils.safeApiKey
import io.askimo.core.util.ProxyUtil
import io.askimo.core.util.appJson
import io.askimo.core.util.httpGet
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpClient
import java.time.Duration
import kotlin.collections.mapNotNull
import kotlin.collections.orEmpty

class AnthropicModelFactory : ChatModelFactory<AnthropicSettings> {

    private val log = logger<AnthropicModelFactory>()

    override fun getProvider(): ModelProvider = ModelProvider.ANTHROPIC

    override fun availableModels(settings: AnthropicSettings): List<ModelDTO> {
        val apiKey = settings.apiKey.takeIf { it.isNotBlank() } ?: return emptyList()
        val url = "${settings.baseUrl.trimEnd('/')}/models"
        return fetchAnthropicModels(apiKey = apiKey, url = url)
            .map { ModelDTO.of(ModelProvider.ANTHROPIC, it) }
    }

    override fun defaultSettings(): AnthropicSettings = AnthropicSettings()

    override fun create(
        sessionId: String?,
        settings: AnthropicSettings,
        toolProvider: ToolProvider?,
        retriever: ContentRetriever?,
        executionMode: ExecutionMode,
        chatMemory: ChatMemory?,
    ): ChatClient {
        // Configure HTTP client for thinking probe (probe needs its own builder)
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)

        // Probe thinking support once — result is persisted in ModelCapabilitiesCache
        if (!ModelCapabilitiesCache.hasTestedThinkingSupport(ModelProvider.ANTHROPIC, settings.defaultModel)) {
            val supportsThinking = probeThinkingSupport(settings, jdkHttpClientBuilder)
            ModelCapabilitiesCache.setThinkingSupport(ModelProvider.ANTHROPIC, settings.defaultModel, supportsThinking)
        }

        return AiServiceBuilder.buildChatClient(
            sessionId = sessionId,
            settings = settings,
            provider = ModelProvider.ANTHROPIC,
            chatModel = createStreamingModel(settings),
            secondaryChatModel = createSecondaryModel(settings),
            chatMemory = chatMemory,
            toolProvider = toolProvider,
            retriever = retriever,
            executionMode = executionMode,
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
        settings: AnthropicSettings,
        jdkHttpClientBuilder: JdkHttpClientBuilder,
    ): Boolean = try {
        val testModel = AnthropicStreamingChatModel
            .builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(settings.defaultModel)
            .baseUrl(settings.baseUrl)
            .logger(log)
            .logRequests(log.isInfoEnabled)
            .thinkingType("enabled") // adaptive, enable, disabled
            .thinkingBudgetTokens(1024)
            .maxTokens(1025) // must be > thinkingBudgetTokens to properly test
            .sendThinking(true)
            .returnThinking(true)
            .build()

        val testClient = AiServices.builder(ChatClient::class.java)
            .streamingChatModel(testModel)
            .build()

        testClient.sendStreamingMessageWithCallback(null, UserMessage("ok"))
        log.info("Model '${settings.defaultModel}' supports thinking — thinking enabled")
        true
    } catch (e: Exception) {
        log.info("Model '${settings.defaultModel}' does not support thinking: ${e.message} — thinking disabled")
        false
    }

    override fun createImageModel(
        settings: AnthropicSettings,
    ): ImageModel {
        TODO("Not yet implemented")
    }

    override fun createStreamingModel(settings: AnthropicSettings): StreamingChatModel {
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)
        val telemetry = AppContext.getInstance().telemetry

        val supportsThinking = ModelCapabilitiesCache.supportsThinking(ModelProvider.ANTHROPIC, settings.defaultModel)

        return AnthropicStreamingChatModel.builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(settings.defaultModel)
            .baseUrl(settings.baseUrl)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
            .cacheSystemMessages(true)
            .cacheTools(true)
            .logger(log)
            .logRequests(log.isDebugEnabled)
            .logResponses(log.isTraceEnabled)
            .listeners(listOf(TelemetryChatModelListener(telemetry, ModelProvider.ANTHROPIC.name.lowercase())))
            .apply {
                if (supportsThinking) {
                    val thinkingConfig = AppConfig.models[ModelProvider.ANTHROPIC]
                    thinkingType("enabled")
                    thinkingBudgetTokens(thinkingConfig.thinkingBudgetTokens)
                    maxTokens(thinkingConfig.thinkingMaxTokens)
                    sendThinking(true)
                    returnThinking(true)
                }
            }
            .build()
    }

    override fun createSecondaryModel(settings: AnthropicSettings): ChatModel {
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)
        return AnthropicChatModel.builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(AppConfig.models[ModelProvider.ANTHROPIC].utilityModel.ifBlank { settings.defaultModel })
            .baseUrl(settings.baseUrl)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.utilityModelTimeoutSeconds))
            .build()
    }

    override fun createModel(settings: AnthropicSettings): ChatModel {
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
        val jdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(httpClientBuilder)

        return AnthropicChatModel.builder()
            .httpClientBuilder(jdkHttpClientBuilder)
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(settings.defaultModel)
            .baseUrl(settings.baseUrl)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
            .build()
    }

    override fun createUtilityClient(
        settings: AnthropicSettings,
    ): ChatClient = AiServices.builder(ChatClient::class.java)
        .chatModel(createSecondaryModel(settings))
        .build()

    private fun fetchAnthropicModels(
        apiKey: String,
        url: String,
    ): List<String> = try {
        val (_, body) = httpGet(
            url,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
        )
        val jsonElement = appJson.parseToJsonElement(body)
        val data = jsonElement.jsonObject["data"]?.jsonArray.orEmpty()
        data
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .distinct()
            .sorted()
    } catch (e: Exception) {
        log.displayError("⚠️ Failed to fetch models from Anthropic: ${e.message}", e)
        emptyList()
    }
}
