/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.gemini

import dev.langchain4j.data.message.TextContent
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
import io.askimo.core.util.appJson
import io.askimo.core.util.createJdkHttpClientBuilder
import io.askimo.core.util.httpGet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // Probe thinking support once — result is persisted in ModelCapabilitiesCache
        if (!ModelCapabilitiesCache.hasTestedThinkingSupport(GEMINI, settings.defaultModel)) {
            val supportsThinking = probeThinkingSupport(settings)
            ModelCapabilitiesCache.setThinkingSupport(GEMINI, settings.defaultModel, supportsThinking)
        }

        // Create streaming model once — reused for both the tool probe and the real client
        val streamingModel = createStreamingModel(settings)

        // Probe tool support once — run async so it never blocks the caller.
        // Optimistically mark as true (supported) so tools are available immediately;
        // the background probe will call setToolSupport() with the real result,
        // which fires ToolSupportDetectedEvent so the UI reacts if the model rejects tools.
        if (executionMode.isToolEnabled() &&
            !ModelCapabilitiesCache.hasTestedToolSupport(GEMINI, settings.defaultModel)
        ) {
            ModelCapabilitiesCache.setToolSupport(GEMINI, settings.defaultModel, true)
            val modelName = settings.defaultModel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val supportsTools = probeToolSupport(modelName, streamingModel, executionMode)
                ModelCapabilitiesCache.setToolSupport(GEMINI, modelName, supportsTools)
            }
        }

        // Probe image generation capability once — run async so it never blocks the caller.
        // Optimistically mark as false (not supported) so the UI is usable immediately;
        // the background probe will call setImageSupport() again with the real result,
        // which fires ImageCapabilityDetectedEvent so the UI reacts without a restart.
        if (!ModelCapabilitiesCache.hasTestedImageSupport(GEMINI, settings.defaultModel)) {
            ModelCapabilitiesCache.setImageSupport(GEMINI, settings.defaultModel, false)
            val modelName = settings.defaultModel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val supportsImage = probeImageCapability(GEMINI, modelName, streamingModel)
                ModelCapabilitiesCache.setImageSupport(GEMINI, modelName, supportsImage)
            }
        }

        // Probe the real context-window size once — run async so it never blocks the caller.
        // Gemini uses a dedicated REST endpoint (not the OpenAI-compat one) for model metadata.
        val modelKey = ModelCapabilitiesCache.modelKey(GEMINI, settings.defaultModel)
        if (!ModelCapabilitiesCache.isContextSizeLearned(modelKey)) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                probeContextSize(settings)?.let { size ->
                    ModelCapabilitiesCache.setContextSize(modelKey, size)
                }
            }
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
     * Probes the real context-window size from the Gemini model-info endpoint.
     * Uses the dedicated REST API (not the OpenAI-compatible one):
     * `GET https://generativelanguage.googleapis.com/v1beta/models/{modelName}?key={apiKey}`
     * → `inputTokenLimit` field.
     */
    override fun probeContextSize(settings: GeminiSettings): Int? = try {
        val normalizedName = if (settings.defaultModel.startsWith("models/")) settings.defaultModel else "models/${settings.defaultModel}"
        val url = "https://generativelanguage.googleapis.com/v1beta/$normalizedName?key=${settings.apiKey}"
        val (status, body) = httpGet(url, connectTimeoutMs = 5_000, readTimeoutMs = 8_000)
        if (status == 200) {
            appJson.parseToJsonElement(body)
                .jsonObject["inputTokenLimit"]?.jsonPrimitive?.intOrNull
                ?.also { log.debug("Probed context size for '{}' (Gemini): {} tokens", settings.defaultModel, it) }
        } else {
            log.debug("Context size probe returned HTTP {} for '{}' (Gemini)", status, settings.defaultModel)
            null
        }
    } catch (e: Exception) {
        log.debug("Context size probe failed for '{}' (Gemini): {}", settings.defaultModel, e.message)
        null
    }

    /**
     * Probes whether the model supports thinking by building a minimal thinking-enabled
     * streaming client and sending a test message. Returns true on success, false if the
     * API rejects the thinking configuration.
     *
     * This is called only once per model — the result is cached in [ModelCapabilitiesCache].
     */
    private fun probeThinkingSupport(settings: GeminiSettings): Boolean = try {
        val testModel = GoogleAiGeminiStreamingChatModel
            .builder()
            .httpClientBuilder(createJdkHttpClientBuilder())
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

        testClient.sendStreamingMessageWithCallback(null, listOf(TextContent("Capability probe — reply with 'ok'.")))
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
        .httpClientBuilder(createJdkHttpClientBuilder())
        .baseUrl(settings.baseUrl)
        .modelName(settings.imageModel)
        .build()

    override fun createStreamingModel(settings: GeminiSettings): StreamingChatModel {
        val telemetry = AppContext.getInstance().telemetry

        val supportsThinking = ModelCapabilitiesCache.supportsThinking(GEMINI, settings.defaultModel)
        val reasoningLevel = ModelCapabilitiesCache.getReasoningLevel(GEMINI, settings.defaultModel)

        return GoogleAiGeminiStreamingChatModel.builder()
            .httpClientBuilder(createJdkHttpClientBuilder())
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
                            .includeThoughts(true)
                            .build(),
                    )
                    sendThinking(true)
                    returnThinking(true)
                }
            }
            .build()
    }

    override fun createSecondaryModel(settings: GeminiSettings): ChatModel = GoogleAiGeminiChatModel.builder()
        .httpClientBuilder(createJdkHttpClientBuilder())
        .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
        .apiKey(safeApiKey(settings.apiKey))
        .modelName(
            settings.utilityModel
                .ifBlank { settings.defaultModel },
        )
        .timeout(Duration.ofSeconds(AppConfig.models.timeouts.utilityModelTimeoutSeconds))
        .build()

    override fun createModel(settings: GeminiSettings): ChatModel = GoogleAiGeminiChatModel.builder()
        .httpClientBuilder(createJdkHttpClientBuilder())
        .apiKey(safeApiKey(settings.apiKey))
        .modelName(settings.defaultModel)
        .timeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
        .build()

    override fun createUtilityClient(
        settings: GeminiSettings,
    ): ChatClient = AiServices.builder(ChatClient::class.java)
        .chatModel(createSecondaryModel(settings))
        .build()

    override fun supportsEmbedding(): Boolean = true

    override fun createEmbeddingModel(settings: GeminiSettings): EmbeddingModel {
        check(settings.embeddingModel.isNotBlank()) {
            "No embedding model is configured for ${GEMINI.name}. " +
                "Go to Settings > AI Provider and select an embedding model under the provider configuration card."
        }
        return GoogleAiEmbeddingModel.builder()
            .apiKey(safeApiKey(settings.apiKey))
            .httpClientBuilder(createJdkHttpClientBuilder())
            .modelName(settings.embeddingModel)
            .build()
    }
}
