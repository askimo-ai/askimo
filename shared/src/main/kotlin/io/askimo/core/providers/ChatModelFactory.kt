/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.exception.InvalidRequestException
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.image.ImageModel
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolProvider
import io.askimo.core.context.ExecutionMode
import io.askimo.tools.fs.LocalFsTools
import org.slf4j.LoggerFactory

/**
 * Factory interface for creating chat model instances for a specific AI provider.
 * Each implementation corresponds to a different model provider (e.g., OpenAI, Ollama).
 *
 * @param T The specific ProviderSettings type for this factory
 */
interface ChatModelFactory<T : ProviderSettings> {
    /**
     * Returns a list of available models for this provider.
     *
     * Each entry carries the [ModelProvider], the [ModelDTO.modelId] sent in API requests,
     * and a human-readable [ModelDTO.displayName] for the UI.
     *
     * @param settings Provider-specific settings that may be needed to retrieve available models
     * @return List of [ModelDTO] entries that can be selected by the user
     */
    fun availableModels(settings: T): List<ModelDTO>

    /**
     * Returns the provider type for this factory.
     *
     * @return The ModelProvider enum value representing this factory's provider
     */
    fun getProvider(): ModelProvider

    /**
     * Returns the default settings for this provider.
     *
     * @return Default provider-specific settings that can be used to initialize models
     */
    fun defaultSettings(): T

    /**
     * Creates a chat service instance with the specified parameters.
     *
     * @param settings Provider-specific settings to configure the model
     * @param retriever Optional content retriever for RAG (Retrieval-Augmented Generation).
     * If provided, the factory will create a RetrievalAugmentor internally with appropriate
     * configuration. Pass null to disable retrieval augmentation.
     * @toolProvider Optional tool provider for providing tools to the model. If provided, the factory will integrate the tools into the model configuration. Pass null to disable tools.
     * @param executionMode The execution mode indicating how the user is running the application.
     * Tools are disabled for DESKTOP mode.
     * @param chatMemory Optional chat memory for conversation context. If provided, memory will be
     * integrated into the LangChain4j AI service.
     * @return A configured ChatModel instance
     */
    fun create(
        sessionId: String? = null,
        settings: T,
        toolProvider: ToolProvider? = null,
        retriever: ContentRetriever? = null,
        executionMode: ExecutionMode,
        chatMemory: ChatMemory? = null,
    ): ChatClient

    fun createImageModel(
        settings: T,
    ): ImageModel

    /**
     * Creates a bare [StreamingChatModel] instance configured for this provider.
     * @param settings Provider-specific settings (credentials, model name, base URL, etc.)
     * @return A fully configured [StreamingChatModel] ready to be used or wrapped
     */
    fun createStreamingModel(settings: T): StreamingChatModel

    /**
     * Creates a bare [ChatModel] (non-streaming) instance configured for this provider.
     *
     * Uses the same model as [createStreamingModel] — i.e. `settings.defaultModel` with the
     * same temperature — but returns a synchronous [ChatModel] instead of a streaming one.
     * Useful when a caller needs a blocking chat call (e.g. LangChain4j components that only
     * accept [ChatModel]) without sacrificing model quality.
     *
     * Note: for lightweight classification or utility tasks, prefer [createUtilityClient] which
     * uses a cheaper secondary model where available.
     *
     * @param settings Provider-specific settings (credentials, model name, base URL, etc.)
     * @return A fully configured [ChatModel] ready to be used or wrapped
     */
    fun createModel(settings: T): ChatModel

    /**
     * Creates a lightweight secondary [ChatModel] using the provider's utility/cheap model.
     *
     * This is the non-streaming counterpart used for tasks that don't need the full frontier
     * model — e.g. RAG query compression, intent classification, session title generation.
     * For cloud providers this maps to a smaller/cheaper model (e.g. `utilityModel` from
     * AppConfig). For local providers it typically falls back to `settings.defaultModel`
     * since there is no extra cost distinction.
     *
     * @param settings Provider-specific settings (credentials, model name, base URL, etc.)
     * @return A fully configured cheap [ChatModel] ready to be used or wrapped
     */
    fun createSecondaryModel(settings: T): ChatModel

    /**
     * Returns helpful guidance text to display when no models are available for this provider.
     * Each factory can override this to provide provider-specific instructions.
     *
     * @return Help text explaining how to set up or configure the provider
     */
    fun getNoModelsHelpText(): String = "Please check your provider configuration."

    /**
     * Creates an intent classification client for RAG decisions.
     * Returns a cheap, fast model suitable for YES/NO classification.
     *
     * For cloud providers: returns a cheap model (e.g., GPT-3.5-turbo for OpenAI)
     * For local providers: returns the current model (no extra overhead)
     *
     * @param settings Provider-specific settings
     * @return ChatClient configured with a classification model
     */
    fun createUtilityClient(
        settings: T,
    ): ChatClient

    /**
     * Indicates whether this provider supports embedding models.
     * Providers that do not support embeddings (e.g., Anthropic, xAI) return false.
     */
    fun supportsEmbedding(): Boolean = false

    /**
     * Creates an embedding model for this provider.
     * Throws [UnsupportedOperationException] by default for providers that don't support embeddings.
     *
     * @param settings Provider-specific settings
     * @return Configured [EmbeddingModel] instance
     */
    fun createEmbeddingModel(settings: T): EmbeddingModel = throw UnsupportedOperationException(
        "${getProvider().name} does not support embedding models. " +
            "Please switch to a provider that supports embeddings (OpenAI, Gemini, Ollama, etc.) to use RAG features.",
    )

    /**
     * Returns the maximum token limit for the embedding model of this provider.
     * Returns a safe conservative default if not overridden.
     *
     * @param settings Provider-specific settings
     * @return Maximum number of tokens the embedding model can handle
     */
    fun getEmbeddingTokenLimit(settings: T): Int = 2048

    /**
     * Probes whether the model supports tool calling by sending a minimal test request
     * with tools attached. Called once per model — result should be cached in
     * [ModelCapabilitiesCache] by the calling [create] implementation.
     *
     * Default implementation is generic and works for any [StreamingChatModel].
     * Override in provider-specific factories if the probe needs special handling.
     *
     * @param modelName Model name used for log messages
     * @param streamingChatModel The streaming model instance to probe
     * @param executionMode Determines which tools to attach during the probe
     * @return true if the model accepted the tool-enabled request, false otherwise
     */
    fun probeToolSupport(
        modelName: String,
        streamingChatModel: StreamingChatModel,
        executionMode: ExecutionMode,
    ): Boolean {
        val log = LoggerFactory.getLogger(this::class.java)
        return try {
            val testClientBuilder = AiServices.builder(ChatClient::class.java)
                .streamingChatModel(streamingChatModel)

            if (executionMode.isToolEnabled()) {
                testClientBuilder.tools(LocalFsTools)
            }

            val testClient = testClientBuilder.build()
            testClient.sendStreamingMessageWithCallback(null, UserMessage("Capability probe — reply with 'ok'."))
            true
        } catch (e: Exception) {
            val errorMessage = e.message?.lowercase() ?: ""
            val causeMessage = e.cause?.message?.lowercase() ?: ""

            val isToolUnsupportedError =
                errorMessage.contains("does not support tool") ||
                    (
                        errorMessage.contains("tool") && (
                            errorMessage.contains("not supported") ||
                                errorMessage.contains("unsupported") ||
                                errorMessage.contains("not available") ||
                                errorMessage.contains("unavailable")
                            )
                        ) ||
                    causeMessage.contains("does not support tool") ||
                    (
                        causeMessage.contains("tool") && (
                            causeMessage.contains("not supported") ||
                                causeMessage.contains("unsupported")
                            )
                        ) ||
                    e is InvalidRequestException ||
                    e.cause is InvalidRequestException

            if (isToolUnsupportedError) {
                log.warn("Model '$modelName' does not support tool calling: ${e.message}. Tools will be disabled")
            } else {
                log.warn("Error testing tool support for model '$modelName': ${e.message}. Assuming tools are NOT supported", e)
            }
            false
        }
    }
}
