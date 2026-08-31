/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.anthropic

import dev.langchain4j.data.message.TextContent
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import dev.langchain4j.model.chat.Capability
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
import io.askimo.core.providers.ModelProvider.ANTHROPIC
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.core.telemetry.TelemetryChatModelListener
import io.askimo.core.util.ApiKeyUtils.safeApiKey
import io.askimo.core.util.appJson
import io.askimo.core.util.createJdkHttpClientBuilder
import io.askimo.core.util.httpGet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.collections.mapNotNull
import kotlin.collections.orEmpty

class AnthropicModelFactory : ChatModelFactory<AnthropicSettings> {

    private val log = logger<AnthropicModelFactory>()

    override fun getProvider(): ModelProvider = ANTHROPIC

    override fun availableModels(settings: AnthropicSettings): List<ModelDTO> {
        val apiKey = settings.apiKey.takeIf { it.isNotBlank() } ?: return emptyList()
        val url = "${settings.baseUrl.trimEnd('/')}/models"
        return fetchAnthropicModels(apiKey = apiKey, url = url)
            .map { ModelDTO.of(ANTHROPIC, it) }
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
        // Probe thinking support once — result is persisted in ModelCapabilitiesCache
        if (!ModelCapabilitiesCache.hasTestedThinkingSupport(ANTHROPIC, settings.defaultModel)) {
            val supportsThinking = probeThinkingSupport(settings)
            ModelCapabilitiesCache.setThinkingSupport(ANTHROPIC, settings.defaultModel, supportsThinking)
        }

        // Create streaming model once — reused for both the tool probe and the real client
        val streamingModel = createStreamingModel(settings)

        // Probe tool support once — run async so it never blocks the caller.
        // Optimistically mark as true (supported) so tools are available immediately;
        // the background probe will call setToolSupport() with the real result,
        // which fires ToolSupportDetectedEvent so the UI reacts if the model rejects tools.
        if (executionMode.isToolEnabled() &&
            !ModelCapabilitiesCache.hasTestedToolSupport(ANTHROPIC, settings.defaultModel)
        ) {
            ModelCapabilitiesCache.setToolSupport(ANTHROPIC, settings.defaultModel, true)
            val modelName = settings.defaultModel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val supportsTools = probeToolSupport(modelName, streamingModel, executionMode)
                ModelCapabilitiesCache.setToolSupport(ANTHROPIC, modelName, supportsTools)
            }
        }

        // Probe image generation capability once — run async so it never blocks the caller.
        // Optimistically mark as false (not supported) so the UI is usable immediately;
        // the background probe will call setImageSupport() again with the real result,
        // which fires ImageCapabilityDetectedEvent so the UI reacts without a restart.
        if (!ModelCapabilitiesCache.hasTestedImageSupport(ANTHROPIC, settings.defaultModel)) {
            ModelCapabilitiesCache.setImageSupport(ANTHROPIC, settings.defaultModel, false)
            val modelName = settings.defaultModel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val supportsImage = probeImageCapability(ANTHROPIC, modelName, streamingModel)
                ModelCapabilitiesCache.setImageSupport(ANTHROPIC, modelName, supportsImage)
            }
        }

        // Probe the real context-window size once — run async so it never blocks the caller.
        val modelKey = ModelCapabilitiesCache.modelKey(ANTHROPIC, settings.defaultModel)
        if (!ModelCapabilitiesCache.isContextSizeLearned(modelKey)) {
            val capturedSettings = settings
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                probeContextSize(capturedSettings)?.let { size ->
                    ModelCapabilitiesCache.setContextSize(modelKey, size)
                }
            }
        }

        return AiServiceBuilder.buildChatClient(
            sessionId = sessionId,
            settings = settings,
            provider = ANTHROPIC,
            chatModel = streamingModel,
            secondaryChatModel = createSecondaryModel(settings),
            chatMemory = chatMemory,
            toolProvider = toolProvider,
            retriever = retriever,
        )
    }

    // probeContextSize() intentionally not overridden — Anthropic's API does not expose
    // context_window in any endpoint. Returns null (interface default); the UI chip shows "?"
    // until a context-length error fires reduceContextSize().

    /**
     * Probes whether the model supports thinking by building a minimal thinking-enabled
     * streaming client and sending a test message. Returns true on success, false if the
     * API rejects the thinking configuration.
     *
     * This is called only once per model — the result is cached in [ModelCapabilitiesCache].
     */
    private fun probeThinkingSupport(settings: AnthropicSettings): Boolean = try {
        val testModel = AnthropicStreamingChatModel
            .builder()
            .httpClientBuilder(createJdkHttpClientBuilder())
            .apiKey(safeApiKey(settings.apiKey))
            .modelName(settings.defaultModel)
            .baseUrl(settings.baseUrl)
            .thinkingType("adaptive") // adaptive, disabled
            .maxTokens(1024)
            .sendThinking(true)
            .returnThinking(true)
            .build()

        val testClient = AiServices.builder(ChatClient::class.java)
            .streamingChatModel(testModel)
            .build()

        testClient.sendStreamingMessageWithCallback(null, listOf(TextContent("Capability probe — reply with 'ok'.")))
        log.info("Model '${settings.defaultModel}' supports thinking — thinking enabled")
        true
    } catch (e: Exception) {
        log.info("Model '${settings.defaultModel}' does not support thinking: ${e.message} — thinking disabled", e)
        false
    }

    override fun createImageModel(
        settings: AnthropicSettings,
    ): ImageModel {
        TODO("Not yet implemented")
    }

    override fun createStreamingModel(settings: AnthropicSettings): StreamingChatModel {
        val telemetry = AppContext.getInstance().telemetry

        val supportsThinking = ModelCapabilitiesCache.supportsThinking(ANTHROPIC, settings.defaultModel)
        val reasoningLevel = ModelCapabilitiesCache.getReasoningLevel(ANTHROPIC, settings.defaultModel)

        return AnthropicStreamingChatModel.builder()
            .httpClientBuilder(createJdkHttpClientBuilder())
            .apiKey(safeApiKey(settings.apiKey))
            .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
            .modelName(settings.defaultModel)
            .baseUrl(settings.baseUrl)
            .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
            .cacheSystemMessages(true)
            .cacheTools(true)
            .logger(log)
            .logRequests(log.isDebugEnabled)
            .logResponses(log.isDebugEnabled)
            .listeners(listOf(TelemetryChatModelListener(telemetry, ANTHROPIC.name.lowercase())))
            .apply {
                if (supportsThinking) {
                    if (reasoningLevel.isEnabled) {
                        thinkingType("adaptive")
                        // Only set explicit budget if user configured one (0 = let Anthropic decide)
                        if (settings.thinkingBudgetTokens > 0) {
                            thinkingBudgetTokens(settings.thinkingBudgetTokens)
                        }
                        // Use thinkingMaxTokens if set, otherwise fall back to maxTokens
                        val effectiveMaxTokens = if (settings.thinkingMaxTokens > 0) settings.thinkingMaxTokens else settings.maxTokens
                        maxTokens(effectiveMaxTokens)
                        sendThinking(true)
                        returnThinking(true)
                    } else {
                        // OFF — explicitly disable extended thinking
                        thinkingType("disabled")
                        maxTokens(settings.maxTokens)
                    }
                } else {
                    maxTokens(settings.maxTokens)
                }
            }
            .build()
    }

    override fun createSecondaryModel(settings: AnthropicSettings): ChatModel = AnthropicChatModel.builder()
        .httpClientBuilder(createJdkHttpClientBuilder())
        .apiKey(safeApiKey(settings.apiKey))
        .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
        .modelName(
            settings.utilityModel
                .ifBlank { settings.defaultModel },
        )
        .baseUrl(settings.baseUrl)
        .timeout(Duration.ofSeconds(AppConfig.models.timeouts.utilityModelTimeoutSeconds))
        .build()

    override fun createModel(settings: AnthropicSettings): ChatModel = AnthropicChatModel.builder()
        .httpClientBuilder(createJdkHttpClientBuilder())
        .apiKey(safeApiKey(settings.apiKey))
        .modelName(settings.defaultModel)
        .baseUrl(settings.baseUrl)
        .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
        .build()

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
