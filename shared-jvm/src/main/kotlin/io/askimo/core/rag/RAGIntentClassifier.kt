/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatClient
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * The routing decision returned by [RAGIntentClassifier].
 *
 * - [RAG]    – semantic vector+keyword search over the knowledge base
 * - [SEARCH] – structural/pattern grep search over knowledge source paths
 * - [SKIP]   – no retrieval needed; answer directly
 */
enum class RAGIntent { RAG, SEARCH, SKIP }

/**
 * Determines how to retrieve project context for the user's message.
 * Returns a tri-state [RAGIntent] instead of a boolean so that pattern queries
 * (find all X, list all Y) can be routed to grep-based search rather than
 * semantic vector retrieval.
 */
class RAGIntentClassifier(
    private val classifierChatClient: ChatClient,
) {
    private val log = logger<RAGIntentClassifier>()

    companion object {
        private const val CLASSIFICATION_TIMEOUT_MS = 5000L
    }

    /**
     * Classifies the retrieval intent for [userMessage].
     *
     * @param userMessage            The current user message.
     * @param conversationHistory    Recent conversation context.
     * @param knowledgeSourcePaths   Searchable root paths from the active project (used to inform SEARCH routing).
     * @return [RAGIntent] routing decision.
     */
    suspend fun classify(
        userMessage: String,
        conversationHistory: List<ChatMessage>,
        knowledgeSourcePaths: List<String> = emptyList(),
    ): RAGIntent = try {
        val prompt = buildPrompt(userMessage, conversationHistory, knowledgeSourcePaths)

        val response = withTimeout(CLASSIFICATION_TIMEOUT_MS.milliseconds) {
            classifierChatClient.sendMessage(prompt)
        }

        val intent = when (response.trim().uppercase()) {
            "RAG" -> RAGIntent.RAG
            "SEARCH" -> RAGIntent.SEARCH
            else -> RAGIntent.SKIP
        }

        log.debug("RAG Classification: $intent (response: $response)")
        intent
    } catch (e: Exception) {
        log.warn("Intent classification failed: ${e.message}. Defaulting to RAG", e)
        RAGIntent.RAG
    }

    private fun buildPrompt(
        userMessage: String,
        history: List<ChatMessage>,
        knowledgeSourcePaths: List<String>,
    ): String {
        val recentContext = if (history.isEmpty()) {
            "No previous conversation"
        } else {
            history.takeLast(4).joinToString("\n") {
                val role = when {
                    it.type().name.contains("USER") -> "User"
                    it.type().name.contains("AI") -> "AI"
                    else -> it.type().name
                }
                val text = it.getTextContent()
                val content = text.take(150)
                "$role: $content${if (text.length > 150) "..." else ""}"
            }
        }

        val pathsSection = if (knowledgeSourcePaths.isEmpty()) {
            ""
        } else {
            "\nSearchable knowledge source paths:\n" +
                knowledgeSourcePaths.joinToString("\n") { "- $it" } + "\n"
        }

        return """
            You are a retrieval router. Classify how to retrieve context for the user's message.

            The knowledge base contains: project-specific code, documentation, configs, and domain content.
            It does NOT contain: general programming knowledge, math, world facts, or language tasks.
            $pathsSection
            Recent conversation (last 4 turns):
            $recentContext

            Current message: "$userMessage"

            Classify as exactly one of: RAG, SEARCH, or SKIP

            SKIP when:
            - Casual chat, greetings, thanks ("ok", "got it", "thanks")
            - Pure math or logic ("calculate", "sort", "convert")
            - General programming knowledge not specific to this project ("what is a HashMap?")
            - Modifying, summarizing, or reformatting the AI's previous response
            - The answer was clearly given in the last 2 AI turns above
            - Creative writing, jokes, unrelated tasks

            SEARCH when (pattern/structural queries over project files):
            - "find all X", "list all Y", "which files contain Z"
            - "where is annotation/decorator X used?"
            - "how many classes implement X?"
            - "show all usages of X"
            - Looking for a specific pattern, token, or identifier across the codebase

            RAG when (semantic/conceptual queries):
            - "How does [project-specific thing] work?"
            - "Where is [feature] implemented?" (needs understanding, not just grep)
            - "What configuration does X use?"
            - "Explain [concept in the project]"
            - Any question where the answer requires understanding context, not just finding text

            Examples:
            Message: "How does the payment service handle retries?" → RAG
            Message: "Find all Kafka consumer group IDs" → SEARCH
            Message: "List all classes that implement RetryPolicy" → SEARCH
            Message: "What is a design pattern?" → SKIP
            Message: "Can you make that shorter?" → SKIP
            Message: "Where is the Kafka consumer configured?" → RAG
            Message: "Thanks, that helps" → SKIP
            Message: "What does OrderService.process() do?" → RAG
            Message: "Which files use @Transactional?" → SEARCH
            Message: "Explain what a coroutine is" → SKIP

            Respond with exactly one word: RAG, SEARCH, or SKIP
        """.trimIndent()
    }

    private fun ChatMessage.getTextContent(): String = when (this) {
        is UserMessage -> this.singleText() ?: ""
        is AiMessage -> this.text() ?: ""
        is SystemMessage -> this.text() ?: ""
        else -> ""
    }
}
