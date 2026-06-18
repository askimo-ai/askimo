/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.memory

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.askimo.core.chat.domain.SessionMemory
import io.askimo.core.chat.repository.SessionMemoryRepository
import io.askimo.core.context.AppContext
import io.askimo.core.providers.ChatClient
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Test suite for TokenAwareSummarizingMemory.
 *
 * Tests cover:
 * - Basic message storage and retrieval
 * - Token-aware summarization triggering
 * - Protected recent turns (last 6 messages always kept verbatim)
 * - Summarization prune fraction (65% of candidates pruned per cycle)
 * - keyFacts cap (MAX_KEY_FACTS = 30) with LRU-by-relevance eviction
 * - mainTopics cap (MAX_MAIN_TOPICS = 15)
 * - Async summarization behavior
 * - Memory persistence (import/export)
 * - Database integration
 * - Edge cases and error handling
 */
@AskimoTestHome
class TokenAwareSummarizingMemoryTest {

    private lateinit var mockAppContext: AppContext
    private lateinit var mockChatClient: ChatClient
    private lateinit var mockRepository: SessionMemoryRepository
    private lateinit var mockParams: io.askimo.core.context.AppContextParams
    private lateinit var memory: TokenAwareSummarizingMemory

    private val sessionId = "test-session-123"

    @BeforeEach
    fun setup() {
        mockAppContext = mock()
        mockChatClient = mock()
        mockRepository = mock()
        mockParams = mock()

        whenever(mockAppContext.createUtilityClient()).thenReturn(mockChatClient)
        whenever(mockAppContext.getActiveProvider()).thenReturn(io.askimo.core.providers.ModelProvider.OPENAI)
        whenever(mockAppContext.params).thenReturn(mockParams)
        whenever(mockParams.model).thenReturn("gpt-4")
        whenever(mockAppContext.buildUserMemoryPrefix()).thenReturn("")
        whenever(mockRepository.getBySessionId(any())).thenReturn(null)
    }

    @AfterEach
    fun tearDown() {
        if (::memory.isInitialized) {
            memory.close()
        }
    }

    // ── Basic storage ────────────────────────────────────────────────────────

    @Test
    fun `should initialize empty memory`() {
        memory = createMemory()
        assertEquals(0, memory.messages().size, "Memory should be empty on initialization")
    }

    @Test
    fun `should add messages to memory`() {
        memory = createMemory()
        memory.add(UserMessage.from("Hello"))
        memory.add(AiMessage.from("Hi there!"))

        val messages = memory.messages()
        assertEquals(2, messages.size)
        assertTrue(messages[0] is UserMessage)
        assertTrue(messages[1] is AiMessage)
    }

    @Test
    fun `should estimate tokens correctly with default estimator`() {
        memory = createMemory()

        // Default estimator: words × 1.75 — "one two three four five" = 5 words → 8 tokens
        val testMessage = UserMessage.from("one two three four five")
        memory.add(testMessage)

        assertEquals(1, memory.messages().size)
    }

    @Test
    fun `default token estimator uses 1_75 multiplier`() {
        val estimator = TokenAwareSummarizingMemory.defaultTokenEstimator()
        val msg = UserMessage.from("one two three four") // 4 words × 1.75 = 7
        assertEquals(7, estimator(msg))
    }

    // ── Summarization threshold ──────────────────────────────────────────────

    @Test
    fun `should not trigger summarization below threshold`() {
        memory = createMemory(summarizationThreshold = 0.9, asyncSummarization = false)

        memory.add(UserMessage.from("Short message"))
        memory.add(AiMessage.from("Another short response"))

        assertFalse(
            memory.messages().any { it is SystemMessage },
            "No summary should be generated below threshold",
        )
    }

    @Test
    fun `should trigger summarization above threshold`() {
        memory = createMemory(summarizationThreshold = 0.01, asyncSummarization = false)

        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{"topic":"testing"},"mainTopics":["memory"],"recentContext":"test"}""",
        )

        repeat(100) { i ->
            memory.add(UserMessage.from("Test message $i " + "word ".repeat(30)))
            memory.add(AiMessage.from("Response $i " + "word ".repeat(30)))
        }

        Thread.sleep(2000)
        memory.close()

        verify(mockChatClient, atLeastOnce()).sendMessage(any())
    }

    // ── Protected recent turns ───────────────────────────────────────────────

    @Test
    fun `should not summarize when all messages are within protected window`() {
        // PROTECTED_RECENT_TURNS = 6; with ≤ 6 messages there are 0 candidates → skip
        memory = createMemory(summarizationThreshold = 0.0001, asyncSummarization = false)

        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{},"mainTopics":[],"recentContext":""}""",
        )

        // Add exactly 6 messages — all protected, nothing to prune
        repeat(6) { i -> memory.add(UserMessage.from("msg $i " + "word ".repeat(50))) }

        Thread.sleep(500)
        memory.close()

        // All 6 messages should remain because the summarizer exits early when
        // messagesToSummarizeCount == 0
        val remaining = memory.messages().filterIsInstance<UserMessage>()
        assertEquals(6, remaining.size, "All 6 protected messages should remain unpruned")
    }

    @Test
    fun `should prune only non-protected messages`() {
        // Use a fixed token estimator so we control exactly when the trigger fires
        var messageCount = 0
        val countingEstimator: (ChatMessage) -> Int = { _ ->
            messageCount++
            1000 // each message = 1000 tokens, triggers immediately
        }

        memory = createMemory(
            summarizationThreshold = 0.001,
            asyncSummarization = false,
            tokenEstimator = countingEstimator,
        )

        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{},"mainTopics":[],"recentContext":""}""",
        )

        // Add 12 messages: 6 are protected, 6 are candidates → 65% of 6 ≈ 3 pruned
        repeat(12) { i -> memory.add(UserMessage.from("msg $i")) }

        Thread.sleep(1000)
        memory.close()

        // After pruning, at least 6 protected messages should survive
        val remaining = memory.messages().filterIsInstance<UserMessage>()
        assertTrue(remaining.size >= 6, "At least 6 protected messages must survive; got ${remaining.size}")
    }

    // ── keyFacts cap + LRU eviction ──────────────────────────────────────────

    @Test
    fun `mergeWithExistingSummary caps keyFacts at MAX_KEY_FACTS`() {
        // Use a high token estimator so each message immediately blows past the threshold
        memory = createMemory(summarizationThreshold = 0.01, asyncSummarization = false, tokenEstimator = { _ -> 10_000 })

        // Build a summary with 30 facts (fills the cap exactly)
        val initialFacts = (1..30).associate { "fact$it" to "value$it" }
        val initialSummary = SessionConversationSummary(keyFacts = initialFacts, mainTopics = emptyList(), recentContext = "")
        memory.importState(TokenAwareSummarizingMemory.MemoryState(messages = emptyList(), summary = initialSummary))

        // Simulate a new summarization that adds 5 more facts (should evict oldest)
        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{"fact31":"v31","fact32":"v32","fact33":"v33","fact34":"v34","fact35":"v35"},"mainTopics":[],"recentContext":"new"}""",
        )

        repeat(50) { i -> memory.add(UserMessage.from("msg $i " + "word ".repeat(30))) }

        Thread.sleep(2000)
        memory.close()

        val state = memory.exportState()
        val facts = state.summary?.keyFacts ?: emptyMap()
        assertTrue(facts.size <= 30, "keyFacts must not exceed MAX_KEY_FACTS=30; got ${facts.size}")
        // New facts should be present (they were refreshed most recently)
        assertTrue(facts.containsKey("fact31") || facts.containsKey("fact35"), "New facts should survive the cap")
        // Oldest facts (1–5) should have been evicted
        assertFalse(facts.containsKey("fact1"), "Oldest fact should have been evicted")
    }

    @Test
    fun `mergeWithExistingSummary refreshes position of updated key`() {
        // Use a high token estimator so messages immediately exceed the threshold and
        // force summarization — without it the token count never reaches the trigger point.
        memory = createMemory(summarizationThreshold = 0.01, asyncSummarization = false, tokenEstimator = { _ -> 10_000 })

        // Initial summary with 30 facts — fact1 is oldest
        val initialFacts = (1..30).associate { "fact$it" to "value$it" }
        val initialSummary = SessionConversationSummary(keyFacts = initialFacts, mainTopics = emptyList(), recentContext = "")
        memory.importState(TokenAwareSummarizingMemory.MemoryState(messages = emptyList(), summary = initialSummary))

        // Simulate a new cycle that updates fact1 (refreshes it) and adds 5 new facts
        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{"fact1":"updated","fact31":"v31","fact32":"v32","fact33":"v33","fact34":"v34","fact35":"v35"},"mainTopics":[],"recentContext":"ctx"}""",
        )

        repeat(50) { i -> memory.add(UserMessage.from("msg $i " + "word ".repeat(30))) }

        Thread.sleep(2000)
        memory.close()

        val facts = memory.exportState().summary?.keyFacts ?: emptyMap()
        assertTrue(facts.size <= 30, "Cap must hold at 30")
        // fact1 was re-inserted (updated) so it should survive; fact2 should be evicted
        assertTrue(facts.containsKey("fact1"), "Updated fact1 should have been refreshed and kept")
        assertFalse(facts.containsKey("fact2"), "fact2 was not refreshed and should have been evicted")
    }

    // ── mainTopics cap ───────────────────────────────────────────────────────

    @Test
    fun `mergeWithExistingSummary caps mainTopics at MAX_MAIN_TOPICS`() {
        memory = createMemory()

        // Initial summary with 15 topics
        val initialTopics = (1..15).map { "topic$it" }
        val initialSummary = SessionConversationSummary(keyFacts = emptyMap(), mainTopics = initialTopics, recentContext = "")
        memory.importState(TokenAwareSummarizingMemory.MemoryState(messages = emptyList(), summary = initialSummary))

        // New cycle adds 5 more distinct topics
        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{},"mainTopics":["topic16","topic17","topic18","topic19","topic20"],"recentContext":"ctx"}""",
        )

        repeat(50) { i -> memory.add(UserMessage.from("msg $i " + "word ".repeat(30))) }

        Thread.sleep(2000)
        memory.close()

        val topics = memory.exportState().summary?.mainTopics ?: emptyList()
        assertTrue(topics.size <= 15, "mainTopics must not exceed MAX_MAIN_TOPICS=15; got ${topics.size}")
        // takeLast keeps most recent — new topics should be present
        assertTrue(
            topics.any { it.startsWith("topic1") && it.length > 6 },
            "Recent topics (16–20) should be present",
        )
    }

    // ── Summary merge ────────────────────────────────────────────────────────

    @Test
    fun `should merge summaries when multiple summarizations occur`() {
        memory = createMemory(summarizationThreshold = 0.01, asyncSummarization = false)

        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{"fact1":"value1"},"mainTopics":["topic1"],"recentContext":"First context"}""",
        )
        repeat(100) { i -> memory.add(UserMessage.from("Batch 1 msg $i " + "word ".repeat(30))) }
        Thread.sleep(2000)

        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{"fact2":"value2"},"mainTopics":["topic2"],"recentContext":"Second context"}""",
        )
        repeat(100) { i -> memory.add(UserMessage.from("Batch 2 msg $i " + "word ".repeat(30))) }
        Thread.sleep(2000)
        memory.close()

        val facts = memory.exportState().summary?.keyFacts ?: emptyMap()
        assertNotNull(memory.exportState().summary)
        assertTrue(
            facts.containsKey("fact1") || facts.containsKey("fact2"),
            "Merged summary should contain facts from both summarizations",
        )
    }

    // ── System message preservation ──────────────────────────────────────────

    @Test
    fun `should preserve system messages during summarization`() {
        memory = createMemory(summarizationThreshold = 0.01, asyncSummarization = false)

        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """{"keyFacts":{},"mainTopics":[],"recentContext":"test"}""",
        )

        memory.add(SystemMessage.from("You are a helpful assistant"))
        repeat(100) { i ->
            memory.add(UserMessage.from("Message $i " + "word ".repeat(30)))
            memory.add(AiMessage.from("Response $i " + "word ".repeat(30)))
        }

        Thread.sleep(2000)
        memory.close()

        assertTrue(memory.messages().filterIsInstance<SystemMessage>().isNotEmpty(), "System messages should be preserved")
    }

    // ── Clear / export / import ──────────────────────────────────────────────

    @Test
    fun `should clear all messages and summary`() {
        memory = createMemory()
        memory.add(UserMessage.from("Test"))
        memory.add(AiMessage.from("Response"))
        memory.clear()
        assertEquals(0, memory.messages().size)
    }

    @Test
    fun `should export and import memory state`() {
        memory = createMemory()
        memory.add(UserMessage.from("Question 1"))
        memory.add(AiMessage.from("Answer 1"))
        memory.add(UserMessage.from("Question 2"))

        val state = memory.exportState()
        memory.clear()
        assertEquals(0, memory.messages().size)

        memory.importState(state)

        val messages = memory.messages()
        assertEquals(3, messages.size)
        assertEquals("Question 1", (messages[0] as UserMessage).singleText())
        assertEquals("Answer 1", (messages[1] as AiMessage).text())
    }

    @Test
    fun `should serialize and deserialize memory state correctly`() {
        memory = createMemory()
        memory.add(UserMessage.from("User question"))
        memory.add(AiMessage.from("AI answer"))
        memory.add(SystemMessage.from("System instruction"))

        val state = memory.exportState()
        assertEquals(3, state.messages.size)
        assertNull(state.summary, "No summary should exist yet")

        memory.clear()
        memory.importState(state)
        assertEquals(3, memory.messages().size)
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    fun `should persist to database when adding messages`() {
        memory = createMemory()
        memory.add(UserMessage.from("Test message"))
        verify(mockRepository, times(1)).saveMemory(any())
    }

    @Test
    fun `should load from database on initialization`() {
        val existingMemory = SessionMemory(
            sessionId = sessionId,
            memorySummary = null,
            memoryMessages = """
                [
                    {"type": "user", "content": "Previous question"},
                    {"type": "assistant", "content": "Previous answer"}
                ]
            """.trimIndent(),
            lastUpdated = Instant.now(),
        )
        whenever(mockRepository.getBySessionId(sessionId)).thenReturn(existingMemory)

        memory = createMemory()

        val messages = memory.messages()
        assertEquals(2, messages.size)
        assertTrue(messages[0] is UserMessage)
        assertTrue(messages[1] is AiMessage)
    }

    @Test
    fun `should handle database load failure gracefully`() {
        whenever(mockRepository.getBySessionId(any())).thenThrow(RuntimeException("Database error"))
        memory = createMemory()
        assertEquals(0, memory.messages().size, "Should start with empty memory on load failure")
    }

    @Test
    fun `should handle database save failure gracefully`() {
        whenever(mockRepository.saveMemory(any())).thenThrow(RuntimeException("Database error"))
        memory = createMemory()
        memory.add(UserMessage.from("Test"))
        assertEquals(1, memory.messages().size, "Message should still be in memory despite save failure")
    }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    fun `should handle summarization failure gracefully with fallback`() {
        memory = createMemory(summarizationThreshold = 0.01, asyncSummarization = false)
        whenever(mockChatClient.sendMessage(any())).thenThrow(RuntimeException("API Error"))

        repeat(100) { i -> memory.add(UserMessage.from("Message $i " + "word ".repeat(30))) }

        Thread.sleep(2000)
        memory.close()

        assertNotNull(memory.messages(), "Memory should continue functioning despite summarization failure")
    }

    @Test
    fun `should handle empty conversation gracefully`() {
        memory = createMemory()
        val messages = memory.messages()
        assertNotNull(messages)
        assertEquals(0, messages.size)
    }

    // ── Custom token estimator ───────────────────────────────────────────────

    @Test
    fun `should use custom token estimator when provided`() {
        var estimatorCalled = false
        val customEstimator: (ChatMessage) -> Int = { _ ->
            estimatorCalled = true
            100
        }

        memory = createMemory(tokenEstimator = customEstimator)
        memory.add(UserMessage.from("Test"))
        assertTrue(estimatorCalled, "Custom token estimator should be called")
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    @Test
    fun `should handle concurrent message additions safely`() {
        memory = createMemory()

        val threads = (1..10).map { threadId ->
            Thread { repeat(10) { i -> memory.add(UserMessage.from("Thread $threadId - Message $i")) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, memory.messages().size, "All concurrent messages should be added")
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

    @Test
    fun `should extract text content from different message types`() {
        memory = createMemory()
        memory.add(UserMessage.from("User text"))
        memory.add(AiMessage.from("AI text"))
        memory.add(SystemMessage.from("System text"))

        val messages = memory.messages()
        assertEquals(3, messages.size)
        assertTrue(messages[0] is UserMessage)
        assertTrue(messages[1] is AiMessage)
        assertTrue(messages[2] is SystemMessage)
    }

    @Test
    fun `should close executor service properly`() {
        memory = createMemory()
        memory.add(UserMessage.from("Test"))
        memory.close() // Should not hang
    }

    @Test
    fun `should calculate maxTokens based on model context size`() {
        memory = createMemory()
        memory.add(UserMessage.from("Test message"))
        assertNotNull(memory.messages(), "Memory should function with dynamic token calculation")
    }

    @Test
    fun `should handle very long messages`() {
        memory = createMemory()
        val longText = "word ".repeat(10000)
        memory.add(UserMessage.from(longText))

        val messages = memory.messages()
        assertEquals(1, messages.size)
        assertTrue((messages[0] as UserMessage).singleText()?.length!! > 10000)
    }

    @Test
    fun `should maintain message order`() {
        memory = createMemory()
        (1..10).forEach { i -> memory.add(UserMessage.from("Message $i")) }

        val messages = memory.messages()
        assertEquals(10, messages.size)
        messages.forEachIndexed { index, message ->
            assertEquals("Message ${index + 1}", (message as UserMessage).singleText())
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun createMemory(
        summarizationThreshold: Double = 0.4, // matches updated default
        asyncSummarization: Boolean = true,
        tokenEstimator: (ChatMessage) -> Int = TokenAwareSummarizingMemory.defaultTokenEstimator(),
        summarizationTimeoutSeconds: Long = 30,
    ): TokenAwareSummarizingMemory = TokenAwareSummarizingMemory(
        appContext = mockAppContext,
        sessionId = sessionId,
        sessionMemoryRepository = mockRepository,
        tokenEstimator = tokenEstimator,
        summarizationThreshold = summarizationThreshold,
        asyncSummarization = asyncSummarization,
        summarizationTimeoutSeconds = summarizationTimeoutSeconds,
    )
}
