/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.rag.DefaultRetrievalAugmentor
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.tool.ToolProvider
import io.askimo.core.config.AppConfig
import io.askimo.core.logging.logger
import io.askimo.core.rag.MetadataAwareContentInjector
import io.askimo.core.util.SystemPrompts.systemMessage

/**
 * Shared builder for creating ChatClient instances across all provider model factories.
 * Centralizes common AI service configuration to reduce code duplication.
 * Capability probing (tools, thinking) is the responsibility of the calling factory.
 */
object AiServiceBuilder {

    private val log = logger<AiServiceBuilder>()

    /**
     * Builds a ChatClient with common configuration applied.
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
    ): ChatClient = buildChatClientInternal(
        sessionId = sessionId,
        settings = settings,
        provider = provider,
        chatModel = chatModel,
        secondaryChatModel = secondaryChatModel,
        chatMemory = chatMemory,
        toolProvider = toolProvider,
        retriever = retriever,
    )

    private fun buildChatClientInternal(
        sessionId: String?,
        settings: ProviderSettings,
        provider: ModelProvider,
        chatModel: StreamingChatModel,
        secondaryChatModel: ChatModel,
        chatMemory: ChatMemory?,
        toolProvider: ToolProvider?,
        retriever: ContentRetriever?,
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
                systemMessage()
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
            builder.toolProvider(toolProvider)
                .maxToolCallingRoundTrips(AppConfig.models.maxToolCallingRoundTrips)
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
}
