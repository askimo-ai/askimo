/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.memory

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ChatMessageType
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.ChatMemory
import io.askimo.core.chat.domain.SessionMemory
import io.askimo.core.chat.repository.SessionMemoryRepository
import io.askimo.core.chat.repository.UserMemoryRepository
import io.askimo.core.context.AppContext
import io.askimo.core.context.MessageRole
import io.askimo.core.logging.logger
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.getSummary
import io.askimo.core.providers.getUserMemoryFacts
import io.askimo.core.util.JsonUtils.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Structured summary format for conversation analysis
 */
@Serializable
data class SessionConversationSummary(
    val keyFacts: Map<String, String> = emptyMap(),
    val mainTopics: List<String> = emptyList(),
    val recentContext: String = "",
)

/**
 * Compact summary of stable facts about the user, extracted from conversations.
 * Accumulated across all sessions — project-agnostic.
 *
 * @property facts Stable user facts: role, preferences, tech stack, constraints, etc.
 *                 Max [MAX_USER_FACTS] entries. Oldest entries are dropped when full.
 */
@Serializable
data class UserMemorySummary(
    val facts: Map<String, String> = emptyMap(),
) {
    /**
     * Merge [incoming] facts into this summary.
     *
     * - New keys are added directly.
     * - Existing keys always **append** the new value as comma-separated if not already present.
     * - If total entries exceed [MAX_USER_FACTS], oldest (by insertion order) are dropped.
     */
    fun merge(incoming: Map<String, String>): UserMemorySummary {
        val merged = LinkedHashMap<String, String>(facts)
        incoming.forEach { (k, v) ->
            val existing = merged[k]
            merged[k] = if (existing != null) {
                val existingItems = existing.split(",").map { it.trim().lowercase() }
                val newItems = v.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val toAdd = newItems.filter { it.lowercase() !in existingItems }
                if (toAdd.isEmpty()) existing else "$existing, ${toAdd.joinToString(", ")}"
            } else {
                v
            }
        }
        val trimmed = if (merged.size > MAX_USER_FACTS) {
            merged.entries.drop(merged.size - MAX_USER_FACTS).associate { it.toPair() }
        } else {
            merged
        }
        return copy(facts = trimmed)
    }

    companion object {
        const val MAX_USER_FACTS = 20
    }
}

/**
 * This memory keeps recent messages in full detail while creating either a structured
 * AI-powered summary or a simple extractive summary of older messages to preserve
 * context without exceeding token limits.
 *
 * Memory persistence is mandatory - sessionId and sessionMemoryRepository are required.
 * The memory automatically loads from database on creation and saves after every change.
 *
 * The maximum tokens for memory is dynamically calculated as 40% of the model's context
 * window size, ensuring optimal utilization regardless of which model is active.
 *
 * @param appContext The application context for accessing model information
 * @param sessionId The session ID this memory belongs to (required for persistence)
 * @param sessionMemoryRepository Repository for persisting memory state (required)
 * @param tokenEstimator Function to estimate token count for a message (default: words * 1.3)
 * @param summarizationThreshold Percentage (0.0-1.0) of maxTokens at which to trigger summarization, default 0.6
 * @param asyncSummarization Whether to run summarization asynchronously, default true
 * @param summarizationTimeoutSeconds Timeout for summarization operations in seconds, default 300
 */
class TokenAwareSummarizingMemory(
    private val appContext: AppContext,
    private val sessionId: String,
    private val sessionMemoryRepository: SessionMemoryRepository,
    private val userMemoryRepository: UserMemoryRepository? = null,
    private val tokenEstimator: (ChatMessage) -> Int = defaultTokenEstimator(),
    private val summarizationThreshold: Double = 0.6,
    asyncSummarization: Boolean = true,
    private val summarizationTimeoutSeconds: Long = 300,
) : ChatMemory,
    AutoCloseable {

    private val chatClient = appContext.createUtilityClient()

    private val executorService: ExecutorService = if (asyncSummarization) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "memory-summarizer").apply {
                isDaemon = true
            }
        }
    } else {
        Executors.newSingleThreadExecutor()
    }
    private val messages = Collections.synchronizedList(mutableListOf<ChatMessage>())

    @Volatile private var structuredSummary: SessionConversationSummary? = null

    @Volatile private var basicSummary: String? = null

    // AtomicBoolean is used as the cross-thread "in progress" guard.
    // Using AtomicBoolean.compareAndSet avoids the permanent-lock bug.
    private val summarizationInProgress = AtomicBoolean(false)
    private val log = logger<TokenAwareSummarizingMemory>()

    init {
        loadFromDatabase()
    }

    /**
     * Maximum tokens for memory, dynamically calculated from model's context size.
     * This is a computed property that recalculates every time to ensure it always
     * reflects the latest context size from ModelCapabilitiesCache (which may be updated
     * as the system learns the actual model capabilities).
     *
     * Uses 40% of the model's context window, leaving room for:
     * - Current conversation in request (~40%)
     * - AI response (20% reserved in ChatRequestTransformers)
     */
    private val maxTokens: Int
        get() = calculateMaxTokensFromCurrentModel()

    private fun calculateMaxTokensFromCurrentModel(): Int {
        val provider = appContext.getActiveProvider()
        val model = appContext.params.model
        val modelKey = ModelCapabilitiesCache.modelKey(provider, model)
        val contextSize = ModelCapabilitiesCache.get(modelKey).contextSize

        val memoryAllocation = (contextSize * 0.4).toInt()

        log.debug(
            "Calculated maxTokens for memory: {} (40% of {} tokens from {})",
            memoryAllocation,
            contextSize,
            modelKey,
        )

        return memoryAllocation
    }

    override fun id(): Any = this.hashCode()

    /**
     * Add message to memory. Non-blocking - triggers async summarization if needed.
     * Automatically persists to database if configured with sessionId and repository.
     */
    override fun add(message: ChatMessage) {
        messages.add(message)
        addMessageToDb(message)
    }

    private fun addMessageToDb(message: ChatMessage) {
        if (message.getTextContent().isBlank()) {
            return
        }
        log.debug("Added message {}, type {}. Total messages: {}", message.getTextContent().take(100), message.type(), messages.size)
        // Persist to database after adding message
        persistToDatabase()

        val totalTokens = estimateTotalTokens()
        val threshold = (maxTokens * summarizationThreshold).toInt()

        log.debug("Current tokens: $totalTokens, Threshold: $threshold, Max: $maxTokens")

        if (totalTokens > threshold && !summarizationInProgress.get()) {
            log.info("Token count ($totalTokens) exceeded threshold ($threshold). Triggering async summarization.")
            triggerAsyncSummarization()
        }
    }

    override fun messages(): List<ChatMessage> = buildList {
        // Add session conversation summary (if any)
        structuredSummary?.let { summary ->
            add(SystemMessage.from(buildStructuredSummaryMessage(summary)))
        } ?: basicSummary?.let { summary ->
            add(
                SystemMessage.from(
                    """
                    |Previous conversation summary:
                    |$summary
                    |
                    |Continue the conversation below with this context in mind.
                    """.trimMargin(),
                ),
            )
        }

        // Add user memory prefix dynamically (always fresh, updated in real-time)
        val userMemoryPrefix = appContext.buildUserMemoryPrefix()
        if (userMemoryPrefix.isNotBlank()) {
            add(SystemMessage.from(userMemoryPrefix))
        }

        // Strip base64 image data before sending to the AI so images are never
        // re-uploaded on every subsequent request (cost + latency).
        // Synchronize to prevent ConcurrentModificationException if the summarizer
        // prunes messages on its background thread while LangChain4j reads here.
        synchronized(messages) {
            addAll(messages.map { it.stripImages() })
        }
    }

    override fun clear() {
        log.info("Clearing memory. Removing ${messages.size} messages and summary.")
        messages.clear()
        structuredSummary = null
        basicSummary = null
    }

    /**
     * Load messages from filtered memory list (used after message deletion/retry).
     * Implements token limit guardrails like add() but optimized for batch processing.
     * If messages exceed token threshold, triggers summarization before persisting.
     *
     * @param filteredMessages List of MemoryMessage to load
     */
    fun loadFromFilteredMemory(filteredMessages: List<MemoryMessage>) {
        log.debug("Loading {} filtered memory messages for session: {}", filteredMessages.size, sessionId)

        messages.clear()
        structuredSummary = null
        basicSummary = null

        val validMessages = filteredMessages.filter { it.content.isNotBlank() }.map { it.toChatMessage() }

        messages.addAll(validMessages)

        log.debug(
            "Loaded {} valid messages from filtered memory (filtered out {} blank messages)",
            validMessages.size,
            filteredMessages.size - validMessages.size,
        )

        val totalTokens = estimateTotalTokens()
        val threshold = (maxTokens * summarizationThreshold).toInt()

        log.debug(
            "After batch load - Total tokens: {}, Threshold: {}, Max: {}",
            totalTokens,
            threshold,
            maxTokens,
        )

        persistToDatabase()

        if (totalTokens > threshold && !summarizationInProgress.get()) {
            log.info(
                "Token count ({}) exceeded threshold ({}) after batch load. Triggering async summarization.",
                totalTokens,
                threshold,
            )
            triggerAsyncSummarization()
        }
    }

    /**
     * Shutdown executor and wait for pending summarization
     */
    override fun close() {
        log.info("Closing memory, waiting for pending summarization...")
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
                log.warn("Forced shutdown of summarization executor")
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
            Thread.currentThread().interrupt()
            log.warn("Interrupted during shutdown, forced shutdown of summarization executor", e)
        }
    }

    /**
     * Removes exactly the given message instances (by reference/identity) from the list
     * and persists to database. Using identity comparison ensures that messages added
     * *after* the AI summarization call started are never accidentally pruned.
     */
    private fun pruneMessages(messagesToRemove: List<ChatMessage>) {
        val identitySet = IdentityHashMap<ChatMessage, Unit>(messagesToRemove.size)
        messagesToRemove.forEach { identitySet[it] = Unit }
        synchronized(messages) {
            messages.removeIf { it in identitySet }
        }
        persistToDatabase()
    }

    /**
     * Estimates total token count of all messages in memory (thread-safe).
     * Base64 image data is stripped before counting so images don't inflate the estimate.
     */
    private fun estimateTotalTokens(): Int = synchronized(messages) {
        messages
            .map { it.stripImages() }
            .filter { it.getTextContent().isNotBlank() }
            .sumOf { message ->
                tokenEstimator(message)
            }
    }

    /**
     * Trigger async summarization without blocking caller.
     * Uses AtomicBoolean.compareAndSet as the entry guard — safe across threads.
     */
    internal fun triggerAsyncSummarization() {
        // Only one summarization at a time. compareAndSet(false, true) returns false if
        // another task already set it to true, so we skip without touching the lock.
        if (!summarizationInProgress.compareAndSet(false, true)) {
            log.debug("Summarization already in progress, skipping")
            return
        }

        CompletableFuture.runAsync({
            val startTime = System.currentTimeMillis()
            try {
                summarizeAndPrune()
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                log.debug("Summarization completed in {}s", String.format("%.1f", elapsedSec))
            } catch (e: Exception) {
                log.error("Async summarization failed", e)
                // On any error, fall back to basic summary to ensure memory is managed
                try {
                    val conversationMessages = synchronized(messages) {
                        messages.filterNot { it.type() == ChatMessageType.SYSTEM }
                    }
                    if (conversationMessages.isNotEmpty()) {
                        val messagesToSummarizeCount = (conversationMessages.size * 0.45).toInt().coerceAtLeast(1)
                        val messagesToSummarize = conversationMessages.take(messagesToSummarizeCount)
                        generateBasicSummary(messagesToSummarize)
                        pruneMessages(messagesToSummarize)
                        log.info("Fallback basic summarization complete. Remaining: ${messages.size}")
                    }
                } catch (fallbackError: Exception) {
                    log.error("Fallback summarization also failed", fallbackError)
                }
            } finally {
                // Always reset on the same thread that set it — AtomicBoolean is safe here.
                summarizationInProgress.set(false)
                val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
                if (totalTime > summarizationTimeoutSeconds) {
                    log.warn("Summarization took {}s, exceeding timeout of {}s", String.format("%.1f", totalTime), summarizationTimeoutSeconds)
                }
            }
        }, executorService)
        // Fire-and-forget: no orTimeout. Task is daemon and will be cleaned up in close().
    }

    /**
     * Summarizes the oldest portion of the conversation and removes those messages
     * to free up token space while preserving context.
     *
     * System messages are excluded from summarization as they contain instructions,
     * not conversation content. They are preserved in the message list.
     */
    private fun summarizeAndPrune() {
        // Get only user and AI messages (exclude system messages from conversation)
        val conversationMessages = synchronized(messages) {
            messages.filterNot { it.type() == ChatMessageType.SYSTEM }
        }

        if (conversationMessages.isEmpty()) return

        val messagesToSummarizeCount = (conversationMessages.size * 0.45).toInt().coerceAtLeast(1)

        // Copy messages to avoid holding lock during AI call
        val messagesToSummarize = conversationMessages.take(messagesToSummarizeCount)

        log.info("Summarizing $messagesToSummarizeCount out of ${conversationMessages.size} conversation messages (excluding system messages)")

        try {
            generateStructuredSummary(messagesToSummarize)
        } catch (e: Exception) {
            log.error("Summarization failed, using basic fallback", e)
            generateBasicSummary(messagesToSummarize)
        }

        // Remove exactly the messages that were summarized — not by count, to avoid
        // pruning messages that arrived during the async AI call.
        pruneMessages(messagesToSummarize)

        log.info("Summarization complete. Remaining: ${messages.size}, Tokens: ${estimateTotalTokens()}")
    }

    /**
     * Generate structured summary using the provided summarizer function
     */
    private fun generateStructuredSummary(messagesToSummarize: List<ChatMessage>) {
        try {
            val conversationText = buildConversationText(messagesToSummarize)
            val newSummary = chatClient.getSummary(conversationText)

            structuredSummary = mergeWithExistingSummary(newSummary)
            log.info("Generated structured summary with ${newSummary.keyFacts.size} facts, ${newSummary.mainTopics.size} topics")

            // Merge stable user facts into the persistent user memory store
            mergeIntoUserMemory(conversationText)
        } catch (e: Exception) {
            log.error("Structured summarization failed for session $sessionId", e)
        }
    }

    /**
     * Extract stable user facts from the conversation and merge them into
     * the persistent [UserMemoryRepository]. No-op when repository is not configured.
     */
    private fun mergeIntoUserMemory(conversationText: String) {
        val repo = userMemoryRepository ?: return
        try {
            val extracted = chatClient.getUserMemoryFacts(conversationText)
            if (extracted.isEmpty()) return

            val existing = repo.get()
            val current = if (existing != null) {
                json.decodeFromString(UserMemorySummary.serializer(), existing.memoryJson)
            } else {
                UserMemorySummary()
            }

            val merged = current.merge(extracted)
            repo.save(json.encodeToString(UserMemorySummary.serializer(), merged))
            // Invalidate cached user memory in AppContext so next call picks up the update
            appContext.invalidateUserMemoryCache()
            log.debug("Merged {} user memory facts for session {}", extracted.size, sessionId)
        } catch (e: Exception) {
            log.warn("Failed to merge user memory for session {}: {}", sessionId, e.message)
        }
    }

    private fun mergeWithExistingSummary(newSummary: SessionConversationSummary): SessionConversationSummary {
        val existing = structuredSummary
        return if (existing != null) {
            SessionConversationSummary(
                keyFacts = existing.keyFacts + newSummary.keyFacts,
                mainTopics = (existing.mainTopics + newSummary.mainTopics).distinct(),
                recentContext = newSummary.recentContext,
            )
        } else {
            newSummary
        }
    }

    /**
     * Generate basic extractive summary (no AI required).
     * Replaces the previous basic summary entirely to avoid stale content accumulating.
     */
    private fun generateBasicSummary(messagesToSummarize: List<ChatMessage>) {
        val newSummary = buildString {
            append("Earlier conversation (${messagesToSummarize.size} messages):\n")

            val keyMessages = if (messagesToSummarize.size <= 6) {
                messagesToSummarize
            } else {
                messagesToSummarize.take(2) + messagesToSummarize.takeLast(2)
            }

            keyMessages.forEach { message ->
                val text = message.getTextContent()
                val truncated = if (text.length > 150) text.take(150) + "..." else text
                appendLine("• ${message.type()}: $truncated")
            }

            if (messagesToSummarize.size > 6) {
                appendLine("... (${messagesToSummarize.size - 4} more messages)")
            }
        }.take(MAX_SUMMARY_LENGTH)

        basicSummary = newSummary
        log.info("Generated basic summary (${newSummary.length} chars)")
    }

    /**
     * Build conversation text from messages for AI summarization.
     * Only includes User and AI messages, excluding system messages as they are instructions.
     * Base64 image data is stripped and replaced with placeholders.
     */
    private fun buildConversationText(messages: List<ChatMessage>): String = buildString {
        messages.filterNot { it.type() == ChatMessageType.SYSTEM }.forEach { message ->
            appendLine("${message.type()}: ${MemoryMessage.stripBase64Images(message.getTextContent())}")
            appendLine()
        }
    }

    private fun buildStructuredSummaryMessage(summary: SessionConversationSummary): String = buildString {
        appendLine("=== CONVERSATION CONTEXT ===")
        appendLine()

        if (summary.keyFacts.isNotEmpty()) {
            appendLine("Key Facts:")
            summary.keyFacts.forEach { (key, value) ->
                appendLine("  • $key: $value")
            }
            appendLine()
        }

        if (summary.mainTopics.isNotEmpty()) {
            appendLine("Main Topics: ${summary.mainTopics.joinToString(", ")}")
            appendLine()
        }

        if (summary.recentContext.isNotBlank()) {
            appendLine("Recent Context:")
            appendLine(summary.recentContext)
            appendLine()
        }

        appendLine("Continue the conversation below with this context in mind.")
    }

    /**
     * Export the current memory state for persistence.
     *
     * @return MemoryState containing messages and summary
     */
    fun exportState(): MemoryState = MemoryState(
        messages = messages.map { it.toMemoryMessage() },
        summary = structuredSummary,
    )

    /**
     * Import a previously saved memory state.
     *
     * @param state The memory state to restore
     */
    fun importState(state: MemoryState) {
        messages.clear()
        messages.addAll(state.messages.map { it.toChatMessage() })
        structuredSummary = state.summary
        basicSummary = null // Clear basic summary when importing
    }

    /**
     * Data class representing the complete memory state for persistence.
     */
    data class MemoryState(
        val messages: List<MemoryMessage>,
        val summary: SessionConversationSummary?,
    )

    /**
     * Load memory state from database.
     * Called automatically during initialization.
     */
    private fun loadFromDatabase() {
        try {
            val savedMemory = sessionMemoryRepository.getBySessionId(sessionId)
            if (savedMemory != null) {
                // Deserialize and restore state
                val state = deserializeMemoryState(savedMemory)
                importState(state)
                log.debug("Loaded memory from database for session: $sessionId (${messages.size} messages)")
            } else {
                log.debug("No existing memory found in database for session: $sessionId")
            }
        } catch (e: Exception) {
            log.error("Failed to load memory from database for session: $sessionId", e)
        }
    }

    /**
     * Persist current memory state to database.
     * Called automatically after adding messages or summarizing.
     */
    private fun persistToDatabase() {
        try {
            val state = exportState()
            val sessionMemory = serializeMemoryState(sessionId, state)
            sessionMemoryRepository.saveMemory(sessionMemory)
            log.debug("Persisted memory to database for session: $sessionId")
        } catch (e: Exception) {
            log.error("Failed to persist memory to database for session: $sessionId", e)
        }
    }

    /**
     * Serialize memory state to SessionMemory domain object for database storage.
     * System messages are excluded as they are instructions, not conversation history.
     */
    private fun serializeMemoryState(
        sessionId: String,
        state: MemoryState,
    ): SessionMemory {
        val summaryJson = state.summary?.let {
            json.encodeToString(
                SessionConversationSummary.serializer(),
                it,
            )
        }

        val conversationMessages = state.messages.filterNot { it.type == MessageRole.SYSTEM.value }

        val messagesJson = json.encodeToString(
            ListSerializer(MemoryMessage.serializer()),
            conversationMessages,
        )

        return SessionMemory(
            sessionId = sessionId,
            memorySummary = summaryJson,
            memoryMessages = messagesJson,
            lastUpdated = Instant.now(),
        )
    }

    /**
     * Deserialize SessionMemory from database to MemoryState.
     */
    private fun deserializeMemoryState(
        sessionMemory: SessionMemory,
    ): MemoryState {
        val summary = sessionMemory.memorySummary?.let {
            json.decodeFromString(
                SessionConversationSummary.serializer(),
                it,
            )
        }

        val messages = json.decodeFromString(
            ListSerializer(MemoryMessage.serializer()),
            sessionMemory.memoryMessages,
        )

        return MemoryState(messages = messages, summary = summary)
    }

    companion object {
        private const val MAX_SUMMARY_LENGTH = 2000

        /**
         * Default token estimator that approximates token count as word count * 1.3
         */
        fun defaultTokenEstimator(): (ChatMessage) -> Int = { message ->
            val text = when (message) {
                is UserMessage -> message.singleText() ?: ""
                is AiMessage -> message.text() ?: ""
                is SystemMessage -> message.text() ?: ""
                else -> ""
            }
            (text.split("\\s+".toRegex()).size * 1.3).toInt()
        }
    }
}
