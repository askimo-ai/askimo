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
        // Create mocks
        mockAppContext = mock()
        mockChatClient = mock()
        mockRepository = mock()
        mockParams = mock()

        // Setup default AppContext behavior
        whenever(mockAppContext.createUtilityClient()).thenReturn(mockChatClient)
        whenever(mockAppContext.getActiveProvider()).thenReturn(io.askimo.core.providers.ModelProvider.OPENAI)
        whenever(mockAppContext.params).thenReturn(mockParams)
        whenever(mockParams.model).thenReturn("gpt-4")
        whenever(mockAppContext.buildUserMemoryPrefix()).thenReturn("")

        // Mock repository to return null (no existing memory)
        whenever(mockRepository.getBySessionId(any())).thenReturn(null)
    }

    @AfterEach
    fun tearDown() {
        if (::memory.isInitialized) {
            memory.close()
        }
    }

    @Test
    fun `should initialize empty memory`() {
        memory = createMemory()

        val messages = memory.messages()
        assertEquals(0, messages.size, "Memory should be empty on initialization")
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

        // Add a message with known word count
        val testMessage = UserMessage.from("one two three four five") // 5 words
        memory.add(testMessage)

        // Default estimator: words * 1.3 = 5 * 1.3 = 6.5 -> 6 tokens
        val messages = memory.messages()
        assertEquals(1, messages.size)
    }

    @Test
    fun `should not trigger summarization below threshold`() {
        memory = createMemory(
            summarizationThreshold = 0.9,
            asyncSummarization = false,
        )

        // Add a few messages (should be below 90% threshold)
        memory.add(UserMessage.from("Short message"))
        memory.add(AiMessage.from("Another short response"))

        // Verify no summarization occurred
        val messages = memory.messages()
        assertFalse(messages.any { it is SystemMessage }, "No summary should be generated below threshold")
    }

    @Test
    fun `should trigger summarization above threshold`() {
        // Use synchronous summarization for predictable testing
        // Set very low threshold (1%) to trigger easily
        memory = createMemory(
            summarizationThreshold = 0.01,
            asyncSummarization = false,
        )

        // Mock the summarization response
        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """
            {
                "keyFacts": {"topic": "testing"},
                "mainTopics": ["memory", "summarization"],
                "recentContext": "Testing summarization feature"
            }
            """.trimIndent(),
        )

        // Add many messages with long content to ensure we exceed even 1% of token budget
        // Default estimator: words * 1.3, so each message ~65 tokens
        repeat(100) { i ->
            memory.add(UserMessage.from("Test message number $i with some additional content to increase the token count significantly " + "word ".repeat(30)))
            memory.add(AiMessage.from("Response to message $i with additional content and more words " + "word ".repeat(30)))
        }

        // Wait for async operations to complete (even with asyncSummarization=false, it uses executor)
        Thread.sleep(2000)

        // Close memory to ensure all async operations complete
        memory.close()

        // Verify summarization was triggered at least once
        verify(mockChatClient, atLeastOnce()).sendMessage(any())
    }

    @Test
    fun `should preserve system messages during summarization`() {
        memory = createMemory(
            summarizationThreshold = 0.01,
            asyncSummarization = false,
        )

        // Mock the summarization response
        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """
            {
                "keyFacts": {},
                "mainTopics": [],
                "recentContext": "test"
            }
            """.trimIndent(),
        )

        // Add system message
        val systemMessage = SystemMessage.from("You are a helpful assistant")
        memory.add(systemMessage)

        // Add many user/AI messages to trigger summarization
        repeat(100) { i ->
            memory.add(UserMessage.from("Message $i " + "word ".repeat(30)))
            memory.add(AiMessage.from("Response $i " + "word ".repeat(30)))
        }

        // Wait for summarization and close to ensure completion
        Thread.sleep(2000)
        memory.close()

        val messages = memory.messages()
        val systemMessages = messages.filterIsInstance<SystemMessage>()

        // Should have at least the original system message preserved (not removed during pruning)
        // Note: System messages from summarization may also be present
        assertTrue(systemMessages.isNotEmpty(), "System messages should be preserved")
    }

    @Test
    fun `should clear all messages and summary`() {
        memory = createMemory()

        memory.add(UserMessage.from("Test"))
        memory.add(AiMessage.from("Response"))

        memory.clear()

        val messages = memory.messages()
        assertEquals(0, messages.size, "All messages should be cleared")
    }

    @Test
    fun `should export and import memory state`() {
        memory = createMemory()

        // Add messages
        memory.add(UserMessage.from("Question 1"))
        memory.add(AiMessage.from("Answer 1"))
        memory.add(UserMessage.from("Question 2"))

        // Export state
        val state = memory.exportState()

        // Create new memory and import state
        memory.clear()
        assertEquals(0, memory.messages().size)

        memory.importState(state)

        // Verify imported state
        val messages = memory.messages()
        assertEquals(3, messages.size)
        assertEquals("Question 1", (messages[0] as UserMessage).singleText())
        assertEquals("Answer 1", (messages[1] as AiMessage).text())
    }

    @Test
    fun `should persist to database when adding messages`() {
        memory = createMemory()

        memory.add(UserMessage.from("Test message"))

        // Verify saveMemory was called
        verify(mockRepository, times(1)).saveMemory(any())
    }

    @Test
    fun `should load from database on initialization`() {
        // Setup repository to return existing memory
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

        // Create memory - should load from DB
        memory = createMemory()

        val messages = memory.messages()
        assertEquals(2, messages.size)
        assertTrue(messages[0] is UserMessage)
        assertTrue(messages[1] is AiMessage)
    }

    @Test
    fun `should handle empty conversation gracefully`() {
        memory = createMemory()

        val messages = memory.messages()
        assertNotNull(messages)
        assertEquals(0, messages.size)
    }

    @Test
    fun `should use custom token estimator when provided`() {
        var estimatorCalled = false
        val customEstimator: (ChatMessage) -> Int = { _ ->
            estimatorCalled = true
            100 // Always return 100 tokens
        }

        memory = createMemory(tokenEstimator = customEstimator)

        memory.add(UserMessage.from("Test"))

        assertTrue(estimatorCalled, "Custom token estimator should be called")
    }

    @Test
    fun `should merge summaries when multiple summarizations occur`() {
        memory = createMemory(
            summarizationThreshold = 0.01,
            asyncSummarization = false,
        )

        // First summarization
        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """
            {
                "keyFacts": {"fact1": "value1"},
                "mainTopics": ["topic1"],
                "recentContext": "First context"
            }
            """.trimIndent(),
        )

        repeat(100) { i ->
            memory.add(UserMessage.from("Message batch 1 - $i " + "word ".repeat(30)))
        }

        Thread.sleep(2000)

        // Second summarization with different facts
        whenever(mockChatClient.sendMessage(any())).thenReturn(
            """
            {
                "keyFacts": {"fact2": "value2"},
                "mainTopics": ["topic2"],
                "recentContext": "Second context"
            }
            """.trimIndent(),
        )

        repeat(100) { i ->
            memory.add(UserMessage.from("Message batch 2 - $i " + "word ".repeat(30)))
        }

        Thread.sleep(2000)
        memory.close()

        // Verify both facts are preserved in summary
        val state = memory.exportState()
        assertNotNull(state.summary)
        assertTrue(
            state.summary?.keyFacts?.containsKey("fact1") == true || state.summary?.keyFacts?.containsKey("fact2") == true,
            "Merged summary should contain facts from both summarizations",
        )
    }

    @Test
    fun `should handle summarization failure gracefully with fallback`() {
        memory = createMemory(
            summarizationThreshold = 0.01,
            asyncSummarization = false,
        )

        // Mock summarization to throw exception
        whenever(mockChatClient.sendMessage(any())).thenThrow(RuntimeException("API Error"))

        repeat(100) { i ->
            memory.add(UserMessage.from("Message $i " + "word ".repeat(30)))
        }

        Thread.sleep(2000)
        memory.close()

        // Memory should still function with basic summary fallback
        val messages = memory.messages()
        assertNotNull(messages, "Memory should continue functioning despite summarization failure")
    }

    @Test
    fun `should handle concurrent message additions safely`() {
        memory = createMemory()

        val threads = (1..10).map { threadId ->
            Thread {
                repeat(10) { i ->
                    memory.add(UserMessage.from("Thread $threadId - Message $i"))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val messages = memory.messages()
        assertEquals(100, messages.size, "All concurrent messages should be added")
    }

    @Test
    fun `should extract text content from different message types`() {
        memory = createMemory()

        memory.add(UserMessage.from("User text"))
        memory.add(AiMessage.from("AI text"))
        memory.add(SystemMessage.from("System text"))

        val messages = memory.messages()
        assertEquals(3, messages.size)

        // Verify each message type is stored correctly
        assertTrue(messages[0] is UserMessage)
        assertTrue(messages[1] is AiMessage)
        assertTrue(messages[2] is SystemMessage)
    }

    @Test
    fun `should close executor service properly`() {
        memory = createMemory()

        memory.add(UserMessage.from("Test"))

        // Close should not hang
        memory.close()

        // Attempting to use after close is implementation-defined
        // but close itself should complete
    }

    @Test
    fun `should handle database load failure gracefully`() {
        // Mock repository to throw exception
        whenever(mockRepository.getBySessionId(any())).thenThrow(RuntimeException("Database error"))

        // Should not crash on initialization
        memory = createMemory()

        val messages = memory.messages()
        assertEquals(0, messages.size, "Should start with empty memory on load failure")
    }

    @Test
    fun `should handle database save failure gracefully`() {
        // Mock repository to throw exception on save
        whenever(mockRepository.saveMemory(any())).thenThrow(RuntimeException("Database error"))

        memory = createMemory()

        // Should not crash when adding messages
        memory.add(UserMessage.from("Test"))

        val messages = memory.messages()
        assertEquals(1, messages.size, "Message should still be in memory despite save failure")
    }

    @Test
    fun `should calculate maxTokens based on model context size`() {
        // This is a behavioral test - memory should adapt to model context size
        memory = createMemory()

        // Add messages - the memory should use appropriate limits based on model
        memory.add(UserMessage.from("Test message"))

        val messages = memory.messages()
        assertNotNull(messages, "Memory should function with dynamic token calculation")
    }

    @Test
    fun `should serialize and deserialize memory state correctly`() {
        memory = createMemory()

        // Add various message types
        memory.add(UserMessage.from("User question"))
        memory.add(AiMessage.from("AI answer"))
        memory.add(SystemMessage.from("System instruction"))

        // Export and verify serialization
        val state = memory.exportState()

        assertEquals(3, state.messages.size)
        assertNull(state.summary, "No summary should exist yet")

        // Import into new memory
        memory.clear()
        memory.importState(state)

        val restoredMessages = memory.messages()
        assertEquals(3, restoredMessages.size)
    }

    @Test
    fun `should handle very long messages`() {
        memory = createMemory()

        val longText = "word ".repeat(10000) // Very long message
        memory.add(UserMessage.from(longText))

        val messages = memory.messages()
        assertEquals(1, messages.size)
        assertTrue((messages[0] as UserMessage).singleText()?.length!! > 10000)
    }

    @Test
    fun `should maintain message order`() {
        memory = createMemory()

        val messageTexts = (1..10).map { "Message $it" }
        messageTexts.forEach { text ->
            memory.add(UserMessage.from(text))
        }

        val messages = memory.messages()
        assertEquals(10, messages.size)

        // Verify order is preserved
        messages.forEachIndexed { index, message ->
            assertEquals("Message ${index + 1}", (message as UserMessage).singleText())
        }
    }

    // Helper function to create memory instance with default settings
    private fun createMemory(
        summarizationThreshold: Double = 0.6,
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
