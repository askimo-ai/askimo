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
import dev.langchain4j.rag.DefaultRetrievalAugmentor
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolProvider
import io.askimo.core.config.AppConfig
import io.askimo.core.context.ExecutionMode
import io.askimo.core.logging.logger
import io.askimo.core.rag.MetadataAwareContentInjector
import io.askimo.core.util.SystemPrompts.systemMessage
import io.askimo.tools.fs.LocalFsTools

/**
 * Shared builder for creating ChatClient instances across all provider model factories.
 * Centralizes common AI service configuration to reduce code duplication.
 */
object AiServiceBuilder {

    private val log = logger<AiServiceBuilder>()

    /**
     * Builds a ChatClient with common configuration applied.
     *
     * @param sessionId Optional session ID for the chat
     * @param provider The model provider (OpenAI, Ollama, etc.)
     * @param chatModel The streaming chat model instance
     * @param secondaryChatModel The chat model used for query compression in RAG
     * @param chatMemory Optional chat memory for conversation context
     * @param retriever Optional content retriever for RAG (Retrieval-Augmented Generation)
     * @param executionMode The execution mode (determines if tools are enabled)
     * @param toolInstructions Custom tool response format instructions (defaults to standard format)
     * @return Configured ChatClient instance
     */
    fun buildChatClient(
        sessionId: String?,
        settings: ProviderSettings,
        provider: ModelProvider,
        chatModel: StreamingChatModel,
        secondaryChatModel: ChatModel,
        chatMemory: ChatMemory?,
        toolProvider: ToolProvider?,
        retriever: ContentRetriever?,
        executionMode: ExecutionMode,
        toolInstructions: String = defaultToolResponseFormatInstructions(),
    ): ChatClient {
        val toolsRequested = executionMode.isToolEnabled()

        if (toolsRequested) {
            // Check if we've already tested this model's tool support
            if (!ModelCapabilitiesCache.hasTestedToolSupport(provider, settings.defaultModel)) {
                // Not tested yet - test and save result
                val supportsTools = testToolSupport(settings.defaultModel, chatModel, executionMode)
                ModelCapabilitiesCache.setToolSupport(provider, settings.defaultModel, supportsTools)
            }
            // else: already tested, supportsTools value is cached (true or false)
        }

        return buildChatClientInternal(
            sessionId = sessionId,
            settings = settings,
            provider = provider,
            chatModel = chatModel,
            secondaryChatModel = secondaryChatModel,
            chatMemory = chatMemory,
            toolProvider = toolProvider,
            retriever = retriever,
            toolInstructions = toolInstructions,
        )
    }

    /**
     * Test if the model supports tools by creating a minimal streaming client and sending a test message.
     *
     * @param model The model name for logging and debugging
     * @param chatModel The streaming chat model to test (the actual model that will be used)
     * @param executionMode The execution mode to determine which tools to test
     * @return true if the model supports tools, false otherwise
     */
    private fun testToolSupport(
        model: String,
        chatModel: StreamingChatModel,
        executionMode: ExecutionMode,
    ): Boolean = try {
        // Create minimal test client with the actual streaming model and tools
        val testClientBuilder = AiServices.builder(ChatClient::class.java)
            .streamingChatModel(chatModel)

        if (executionMode.isToolEnabled()) {
            testClientBuilder.tools(LocalFsTools)
        }

        val testClient = testClientBuilder.build()

        // Send a simple test message using the ChatClient.sendMessage method
        testClient.sendStreamingMessageWithCallback(null, UserMessage("do you support tools? Answer yes or no only"))

        true
    } catch (e: Exception) {
        val errorMessage = e.message?.lowercase() ?: ""
        val causeMessage = e.cause?.message?.lowercase() ?: ""

        // Check for tool-related errors through multiple indicators
        val isToolUnsupportedError =
            // Direct error messages
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
            log.warn("Model '$model' does not support tool calling: ${e.message}. Tools will be disabled - result cached")
            false
        } else {
            // Unknown error - safest to assume tools are NOT supported to avoid crashes
            log.warn("Error testing tool support for model '$model': ${e.message}. Assuming tools are NOT supported to avoid crashes - result cached", e)
            false
        }
    }

    /**
     * Internal method that actually builds the ChatClient with full configuration.
     */
    private fun buildChatClientInternal(
        sessionId: String?,
        settings: ProviderSettings,
        provider: ModelProvider,
        chatModel: StreamingChatModel,
        secondaryChatModel: ChatModel,
        chatMemory: ChatMemory?,
        toolProvider: ToolProvider?,
        retriever: ContentRetriever?,
        toolInstructions: String,
    ): ChatClient {
        val builder = AiServices
            .builder(ChatClient::class.java)
            .streamingChatModel(chatModel)
            .apply {
                if (chatMemory != null) {
                    chatMemory(chatMemory)
                }
            }
            .hallucinatedToolNameStrategy(ProviderModelUtils::hallucinatedToolHandler)
            .systemMessageProvider {
                if (toolInstructions.isNotBlank()) {
                    systemMessage(toolInstructions)
                } else {
                    systemMessage()
                }
            }
            .chatRequestTransformer { chatRequest, memoryId ->
                ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                    sessionId,
                    chatRequest,
                    memoryId,
                    provider,
                    settings,
                )
            }

        if (toolProvider != null) {
            builder.toolProvider(toolProvider).maxToolCallingRoundTrips(10)
        }

        if (retriever != null) {
            val retrievalAugmentor = DefaultRetrievalAugmentor
                .builder()
                .queryTransformer(CompressingQueryTransformer(secondaryChatModel))
                .contentRetriever(retriever)
                .contentInjector(
                    MetadataAwareContentInjector(
                        useAbsolutePaths = AppConfig.rag.useAbsolutePathInCitations,
                    ),
                )
                .build()

            builder.retrievalAugmentor(retrievalAugmentor)
                .storeRetrievedContentInChatMemory(false)
        }

        return builder.build()
    }

    /**
     * Default tool response format instructions used across all providers.
     * Can be overridden by passing custom instructions to buildChatClient.
     */
    fun defaultToolResponseFormatInstructions(): String = """
    Tool response format:
    • All tools return: { "success": boolean, "output": string, "error": string, "metadata": object }
    • success=true: Tool executed successfully, check "output" for results and "metadata" for structured data
    • success=false: Tool failed, check "error" for reason
    • Always check the "success" field before using "output"
    • If success=false, inform the user about the error from the "error" field
    • When success=true, extract data from "metadata" field for detailed information

    Tool execution guidelines:
    • Parse the tool response JSON before responding to user
    • If success=true: Use the output and metadata to answer the user IMMEDIATELY — do NOT call the same tool again
    • If success=false: Explain what went wrong using the error message
    • Never assume tool success without checking the response
    • STOP calling tools as soon as you have enough information to answer the user
    • Do NOT call the same tool twice with the same or similar arguments
    • If a tool returns success=true with data, synthesise the answer and respond directly — do not retry
    """.trimIndent()
}
