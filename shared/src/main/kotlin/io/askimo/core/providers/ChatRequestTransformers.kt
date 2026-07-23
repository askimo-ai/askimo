/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger

/**
 * Utility functions for transforming chat requests before they are sent to the AI model.
 */
object ChatRequestTransformers {

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
     * Adds custom system messages, removes duplicates, and enforces token budget from the chat request.
     *
     * @param sessionId The session ID from the create method
     * @param chatRequest The original chat request
     * @param memoryId The memory ID (can be null)
     * @param provider The AI provider
     * @return A new chat request with custom system messages added, duplicates removed, and token budget enforced
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

        // Deduplicate existing system messages — LangChain4j can inject the systemMessageProvider
        // message on top of one already stored in chatMemory, producing identical duplicates.
        val seenTexts = mutableSetOf<String>()
        val existingSystemMessages = existingMessages
            .filterIsInstance<SystemMessage>()
            .filter { seenTexts.add(it.text()) }
        val existingSystemMessageTexts: Set<String> = seenTexts

        val nonSystemMessages = existingMessages.filterNot { it is SystemMessage }

        val additionalSystemMessages = mutableListOf<SystemMessage>()

        // Add language directive if set and not already present
        val appSystemDirective = AppContext.getInstance().systemLanguageDirective
        if (appSystemDirective != null && appSystemDirective !in existingSystemMessageTexts) {
            additionalSystemMessages.add(SystemMessage.from(appSystemDirective))
        }

        // Add user profile directive if set and not already present
        val userProfileDirective = AppContext.getInstance().userProfileDirective
        if (userProfileDirective != null && userProfileDirective !in existingSystemMessageTexts) {
            additionalSystemMessages.add(SystemMessage.from(userProfileDirective))
        }

        if (sessionId != null) {
            val addedDirectiveTexts = existingSystemMessageTexts.toMutableSet()
            DatabaseManager.getInstance().getChatDirectiveRepository().findDirectivesBySessionId(sessionId).forEach { directive ->
                if (directive.content.isNotBlank() && addedDirectiveTexts.add(directive.content)) {
                    additionalSystemMessages.add(SystemMessage.from(directive.content))
                }
            }
        }

        // Preserve existing system messages (e.g. tool instructions from AiServiceBuilder),
        // append new non-duplicate ones after, then all conversation messages
        val rebuiltMessages = existingSystemMessages + additionalSystemMessages + nonSystemMessages
        return chatRequest.toBuilder().messages(rebuiltMessages).build()
    }

    /**
     * Enforces token budget on the chat request by truncating messages if needed.
     * Keeps system messages and recent messages within the available token budget.
     *
     * @param chatRequest The chat request to enforce budget on
     * @param maxTokens The maximum allowed tokens for the entire context
     * @param provider The AI provider name (for logging)
     * @param model The model name (for logging)
     * @return A chat request with messages truncated to fit within the budget
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

        // Check if system messages already exceed budget
        if (totalTokens >= availableForMessages) {
            log.warn("System messages ($totalTokens tokens) exceed available budget ($availableForMessages tokens) for $provider:$model")
            return chatRequest.toBuilder()
                .messages(listOf(SystemMessage.from("[System messages truncated due to size]")))
                .build()
        }

        // Add non-system messages from most recent, staying within budget
        // IMPORTANT: Always keep the most recent message (usually user message) to ensure AI has something to respond to
        val recentMessages = nonSystemMessages.asReversed()

        if (recentMessages.isEmpty()) {
            log.warn("No conversation messages found for {}:{}. Sending only system messages.", provider, model)
            return chatRequest.toBuilder().messages(keptMessages).build()
        }

        // Track the index where system messages end, so we can insert conversation messages after them
        val systemMessagesEndIndex = keptMessages.size

        // Always add the first (most recent) message, even if it exceeds budget
        // Better to get a context length error than send no user input
        val firstMessage = recentMessages.first()
        val firstMessageTokens = estimateTokens(getMessageText(firstMessage))
        keptMessages.add(systemMessagesEndIndex, firstMessage) // Insert after system messages
        totalTokens += firstMessageTokens

        // Now add remaining messages if they fit within budget
        for (msg in recentMessages.drop(1)) {
            val msgTokens = estimateTokens(getMessageText(msg))

            if (totalTokens + msgTokens > availableForMessages) {
                log.debug(
                    "Truncating message history for {}:{} at {} conversation messages ({} tokens used)",
                    provider,
                    model,
                    keptMessages.size - systemMessages.size,
                    totalTokens,
                )
                break
            }

            keptMessages.add(systemMessagesEndIndex, msg) // Insert after system messages, maintaining chronological order
            totalTokens += msgTokens
        }

        // Check if there's enough space left for a quality AI response
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
     * Extracts text content from a ChatMessage based on its type.
     */
    private fun getMessageText(message: ChatMessage): String = when (message) {
        is UserMessage -> message.singleText() ?: ""
        is AiMessage -> message.text() ?: ""
        is SystemMessage -> message.text()
        else -> ""
    }

    /**
     * Estimates the number of tokens in a text string.
     * Uses a simple heuristic: 1 token ≈ 4 characters
     */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}
