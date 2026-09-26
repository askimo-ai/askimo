/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger

/**
 * Utility functions for transforming chat requests before they are sent to the AI model.
 */
object ChatRequestTransformers {

    private val directiveRepository by lazy { DatabaseManager.getInstance().getChatDirectiveRepository() }
    private val log = logger<ChatRequestTransformers>()

    /**
     * Percentage of context window reserved for AI response.
     * 20% is a balanced default for general-purpose chat and code assistance.
     */
    private const val RESPONSE_RESERVE_PERCENT = 0.2

    /**
     * Minimum tokens required for AI response to avoid truncated/poor quality responses.
     * Most meaningful responses need at least 500-2000 tokens.
     */
    private const val MINIMUM_RESPONSE_TOKENS = 2048

    /**
     * Adds custom system messages, removes duplicates, and enforces token budget.
     */
    @JvmStatic
    fun addCustomSystemMessagesAndRemoveDuplicates(
        sessionId: String?,
        chatRequest: ChatRequest,
        memoryId: Any?,
        provider: ModelProvider,
        settings: ProviderSettings,
    ): ChatRequest {
        val modelKey = ModelCapabilitiesCache.modelKey(provider, settings.defaultModel)
        val contextSize = ModelCapabilitiesCache.get(modelKey).contextSize

        log.trace("Processing chat request for $modelKey with context size: $contextSize tokens")

        // First, add custom system messages and remove duplicates
        val requestWithCustomMessages = buildRequestWithCustomMessages(sessionId, chatRequest)

        // Then, enforce token budget
        return enforceTokenBudget(requestWithCustomMessages, contextSize, provider, settings.defaultModel)
    }

    private fun buildRequestWithCustomMessages(
        sessionId: String?,
        chatRequest: ChatRequest,
    ): ChatRequest {
        val existingMessages = chatRequest.messages()

        // Deduplicate system messages (systemMessageProvider can inject duplicates)
        val seenTexts = mutableSetOf<String>()
        val existingSystemMessages = existingMessages
            .filterIsInstance<SystemMessage>()
            .filter { seenTexts.add(it.text()) }
        val existingSystemMessageTexts: Set<String> = seenTexts

        val nonSystemMessages = existingMessages.filterNot { it is SystemMessage }

        val additionalSystemMessages = mutableListOf<SystemMessage>()

        // Add language and user profile directives if not already present
        val appSystemDirective = AppContext.getInstance().systemLanguageDirective
        if (appSystemDirective != null && appSystemDirective !in existingSystemMessageTexts) {
            additionalSystemMessages.add(SystemMessage.from(appSystemDirective))
        }

        val userProfileDirective = AppContext.getInstance().userProfileDirective
        if (userProfileDirective != null && userProfileDirective !in existingSystemMessageTexts) {
            additionalSystemMessages.add(SystemMessage.from(userProfileDirective))
        }

        if (sessionId != null) {
            val directive = directiveRepository.findDirectiveBySessionId(sessionId)
            if (directive != null &&
                directive.content.isNotBlank() &&
                directive.content !in existingSystemMessageTexts
            ) {
                additionalSystemMessages.add(SystemMessage.from(directive.content))
            }
        }

        // Remove consecutive duplicate non-system messages (can occur on retry).
        // ToolExecutionResultMessage includes tool id in dedup key to allow parallel tool calls with identical results.
        val deduplicatedNonSystem = nonSystemMessages.fold(mutableListOf<ChatMessage>()) { acc, msg ->
            val lastSameType = acc.lastOrNull { it.type() == msg.type() }
            if (lastSameType != null && getMessageText(lastSameType) == getMessageText(msg)) {
                log.debug("Dropping consecutive duplicate {} message: {}", msg.type(), getMessageText(msg).take(100))
                acc
            } else {
                acc.also { it.add(msg) }
            }
        }

        val rebuiltMessages = existingSystemMessages + additionalSystemMessages + deduplicatedNonSystem
        return chatRequest.toBuilder().messages(rebuiltMessages).build()
    }

    /**
     * Enforces token budget by truncating old messages while preserving system messages and recent conversation.
     */
    private fun enforceTokenBudget(
        chatRequest: ChatRequest,
        maxTokens: Int,
        provider: ModelProvider,
        model: String,
    ): ChatRequest {
        val messages = chatRequest.messages()

        val reservedForResponse = (maxTokens * RESPONSE_RESERVE_PERCENT).toInt()
        val availableForMessages = maxTokens - reservedForResponse

        val keptMessages = mutableListOf<ChatMessage>()
        var totalTokens = 0

        val systemMessages = messages.filterIsInstance<SystemMessage>()
        val nonSystemMessages = messages.filterNot { it is SystemMessage }

        systemMessages.forEach { msg ->
            val tokens = estimateTokens(getMessageText(msg))
            totalTokens += tokens
            keptMessages.add(msg)
        }

        if (totalTokens >= availableForMessages) {
            log.warn("System messages ($totalTokens tokens) exceed available budget ($availableForMessages tokens) for $provider:$model")
            return chatRequest.toBuilder()
                .messages(listOf(SystemMessage.from("[System messages truncated due to size]")))
                .build()
        }

        // Add recent non-system messages in reverse order, staying within budget.
        // Tool results are always grouped with their preceding tool-use call to prevent orphaned tool calls.
        val recentGroups = groupIntoToolCallUnits(nonSystemMessages).asReversed()

        if (recentGroups.isEmpty()) {
            log.warn("No conversation messages found for {}:{}. Sending only system messages.", provider, model)
            return chatRequest.toBuilder().messages(keptMessages).build()
        }

        val systemMessagesEndIndex = keptMessages.size

        // Always add the most recent message group, even if it exceeds budget (prevents empty user input)
        val firstGroup = recentGroups.first()
        val firstGroupTokens = firstGroup.sumOf { estimateTokens(getMessageText(it)) }
        keptMessages.addAll(systemMessagesEndIndex, firstGroup)
        totalTokens += firstGroupTokens

        // Add older message groups if they fit
        for (group in recentGroups.drop(1)) {
            val groupTokens = group.sumOf { estimateTokens(getMessageText(it)) }

            if (totalTokens + groupTokens > availableForMessages) {
                log.debug(
                    "Truncating message history for {}:{} at {} conversation messages ({} tokens used)",
                    provider,
                    model,
                    keptMessages.size - systemMessages.size,
                    totalTokens,
                )
                break
            }

            keptMessages.addAll(systemMessagesEndIndex, group)
            totalTokens += groupTokens
        }

        // Ensure minimum response space
        val availableForResponse = maxTokens - totalTokens
        if (availableForResponse < MINIMUM_RESPONSE_TOKENS) {
            val modelKey = "${provider.providerKey()}:$model"
            throw InsufficientContextException(
                currentModel = modelKey,
                contextSize = maxTokens,
                usedByMessages = totalTokens,
                availableForResponse = availableForResponse,
                recommendedMinimum = MINIMUM_RESPONSE_TOKENS,
            )
        }

        log.debug(
            "Token budget for {}:{} - Max: {}, Used: {}, Reserved for response: {}, Messages: {}/{}",
            provider,
            model,
            maxTokens,
            totalTokens,
            reservedForResponse,
            keptMessages.size,
            messages.size,
        )

        return chatRequest.toBuilder().messages(keptMessages).build()
    }

    /**
     * Extracts text content for deduplication and token counting.
     * For multimodal UserMessages, extracts only TextContent parts; images remain in the message.
     * For AiMessage with tool calls, includes tool-call ids in the key.
     */
    private fun getMessageText(message: ChatMessage): String = when (message) {
        is UserMessage -> {
            // Extract only TextContent parts; images preserved in the original message
            message.contents()
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text() }
                .takeIf { it.isNotBlank() } ?: ""
        }

        is AiMessage -> {
            val text = message.text() ?: ""
            if (message.hasToolExecutionRequests()) {
                "$text::" + message.toolExecutionRequests().joinToString(",") { req ->
                    req.id() ?: "${req.name()}(${req.arguments()})"
                }
            } else {
                text
            }
        }

        is SystemMessage -> message.text()

        is ToolExecutionResultMessage -> "${message.id() ?: message.toolName()}::${message.text() ?: ""}"

        else -> ""
    }

    /**
     * Groups messages into truncation units: ToolExecutionResultMessage always stays with preceding message.
     * Prevents splitting tool-use/tool-result pairs across truncation boundary.
     */
    private fun groupIntoToolCallUnits(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val groups = mutableListOf<MutableList<ChatMessage>>()
        for (msg in messages) {
            if (msg is ToolExecutionResultMessage && groups.isNotEmpty()) {
                groups.last().add(msg)
            } else {
                groups.add(mutableListOf(msg))
            }
        }
        return groups
    }

    /**
     * Estimates tokens: ~1 token per 4 characters.
     */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}
