/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.db.DatabaseManager
import io.askimo.core.providers.openai.OpenAiSettings
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for ChatRequestTransformers focusing on token budget enforcement and message handling.
 */
class ChatRequestTransformersTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testBaseScope: AskimoHome.TestBaseScope
    private lateinit var databaseManager: DatabaseManager

    @BeforeEach
    fun setUp() {
        testBaseScope = AskimoHome.withTestBase(tempDir)
        databaseManager = DatabaseManager.getInMemoryTestInstance(this)
        AppContext.reset() // defensive reset in case another test left instance set
        AppContext.initialize(ExecutionMode.STATELESS_MODE)
    }

    @AfterEach
    fun tearDown() {
        AppContext.reset()
        databaseManager.close()
        DatabaseManager.reset()
        ModelCapabilitiesCache.clear()
        testBaseScope.close()
    }

    @Nested
    @DisplayName("Token Budget Enforcement")
    inner class TokenBudgetTests {

        @Test
        @DisplayName("should keep all messages when under budget")
        fun shouldKeepAllMessagesUnderBudget() {
            // Given
            val messages = listOf(
                SystemMessage.from("You are helpful."),
                UserMessage.from("Hi"),
                AiMessage.from("Hello!"),
                UserMessage.from("How are you?"),
                AiMessage.from("I'm good, thanks!"),
            )

            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When - using gpt-4 which has a large context size
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then - should have at least the original number of non-system messages
            val resultUserMessages = result.messages().filterIsInstance<UserMessage>()
            val resultAiMessages = result.messages().filterIsInstance<AiMessage>()
            assertEquals(2, resultUserMessages.size, "Should keep all user messages")
            assertEquals(2, resultAiMessages.size, "Should keep all AI messages")
        }

        @Test
        @DisplayName("should truncate old messages when exceeding budget")
        fun shouldTruncateOldMessagesWhenExceedingBudget() {
            val modelKey = ModelCapabilitiesCache.modelKey(ModelProvider.OPENAI, "gpt-3.5-turbo")
            ModelCapabilitiesCache.update(modelKey) { it.copy(contextSize = 16_384) }

            val systemMessage = SystemMessage.from("System directive")
            val messages = mutableListOf<ChatMessage>(systemMessage)

            // Add many large user/ai message pairs (each ~500 chars = ~125 tokens)
            repeat(100) { i ->
                messages.add(UserMessage.from("User message $i: " + "x".repeat(500)))
                messages.add(AiMessage.from("AI response $i: " + "y".repeat(500)))
            }

            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When - using gpt-3.5-turbo with the forced small context (16 384 tokens)
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-3.5-turbo"),
            )

            // Then - should have significantly fewer messages than original
            val resultMessages = result.messages()
            assertTrue(resultMessages.size < messages.size, "Should truncate messages to fit budget")
            assertTrue(resultMessages.size > 1, "Should keep at least some messages")
        }

        @Test
        @DisplayName("should keep most recent messages when truncating")
        fun shouldKeepMostRecentMessages() {
            // Given
            val systemMessage = SystemMessage.from("System")

            // Add old messages that will be truncated
            val oldMessages = List(30) { i ->
                if (i % 2 == 0) {
                    UserMessage.from("Old user $i: " + "x".repeat(300))
                } else {
                    AiMessage.from("Old AI $i: " + "y".repeat(300))
                }
            }

            // Add recent messages that should be kept
            val recentUser = UserMessage.from("Recent user question")
            val recentAi = AiMessage.from("Recent AI answer")

            val messages = listOf(systemMessage) + oldMessages + listOf(recentUser, recentAi)
            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-3.5-turbo"),
            )

            // Then - recent messages should be in the result
            val resultMessages = result.messages()
            val resultTexts = resultMessages.mapNotNull { msg ->
                when (msg) {
                    is UserMessage -> msg.singleText()
                    is AiMessage -> msg.text()
                    else -> null
                }
            }

            assertTrue(
                resultTexts.any { it.contains("Recent user question") },
                "Should keep most recent user message",
            )
            assertTrue(
                resultTexts.any { it.contains("Recent AI answer") },
                "Should keep most recent AI message",
            )
        }

        @Test
        @DisplayName("should handle very large context models")
        fun shouldHandleLargeContextModels() {
            // Given
            val messages = listOf(
                SystemMessage.from("System"),
                UserMessage.from("Question"),
                AiMessage.from("Answer"),
            )

            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When - using Claude which supports very large contexts
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.ANTHROPIC,
                settings = OpenAiSettings(defaultModel = "claude-3-opus"),
            )

            // Then - all messages should be kept (well under budget)
            val resultNonSystemMessages = result.messages().filterNot { it is SystemMessage }
            assertTrue(
                resultNonSystemMessages.size >= 2,
                "Should keep all conversation messages for large context model",
            )
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("should handle request with only user messages")
        fun shouldHandleOnlyUserMessages() {
            // Given
            val messages = listOf(
                UserMessage.from("Question 1"),
                UserMessage.from("Question 2"),
            )
            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then - should work and keep user messages
            assertNotNull(result)
            val userMessages = result.messages().filterIsInstance<UserMessage>()
            assertEquals(2, userMessages.size, "Should keep all user messages")
        }

        @Test
        @DisplayName("should handle null sessionId gracefully")
        fun shouldHandleNullSessionId() {
            // Given
            val messages = listOf(
                UserMessage.from("Test"),
                AiMessage.from("Response"),
            )
            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then - should work without session directive
            assertNotNull(result)
            assertTrue(result.messages().isNotEmpty())
        }

        @Test
        @DisplayName("should preserve message order after transformation")
        fun shouldPreserveMessageOrder() {
            // Given
            val systemMsg = SystemMessage.from("System")
            val user1 = UserMessage.from("Question 1")
            val ai1 = AiMessage.from("Answer 1")
            val user2 = UserMessage.from("Question 2")
            val ai2 = AiMessage.from("Answer 2")

            val messages = listOf(systemMsg, user1, ai1, user2, ai2)
            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then - conversation messages should maintain chronological order
            val resultUserMessages = result.messages().filterIsInstance<UserMessage>()
            assertEquals(2, resultUserMessages.size)

            val firstUserIdx = result.messages().indexOf(resultUserMessages[0])
            val secondUserIdx = result.messages().indexOf(resultUserMessages[1])

            assertTrue(firstUserIdx < secondUserIdx, "User messages should maintain chronological order")
        }

        @Test
        @DisplayName("should handle messages with very short content")
        fun shouldHandleShortMessages() {
            // Given
            val messages = listOf(
                SystemMessage.from("Hi"),
                UserMessage.from("?"),
                AiMessage.from("Yes"),
            )
            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then - all short messages should be kept
            val nonSystemMessages = result.messages().filterNot { it is SystemMessage }
            assertEquals(2, nonSystemMessages.size, "Should keep all short messages")
        }

        @Test
        @DisplayName("should handle messages with very long content")
        fun shouldHandleLongMessages() {
            // Given
            val longContent = "x".repeat(10000) // Very long message
            val messages = listOf(
                SystemMessage.from("System"),
                UserMessage.from(longContent),
                AiMessage.from("Acknowledged"),
            )
            val chatRequest = ChatRequest.builder()
                .messages(messages)
                .build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then - should handle without errors
            assertNotNull(result)
            assertTrue(result.messages().isNotEmpty())
        }
    }

    @Nested
    @DisplayName("Model Provider Variations")
    inner class ModelProviderTests {

        @Test
        @DisplayName("should work with OpenAI models")
        fun shouldWorkWithOpenAI() {
            // Given
            val messages = listOf(
                UserMessage.from("Test"),
                AiMessage.from("Response"),
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then
            assertNotNull(result)
            assertTrue(result.messages().size >= 2)
        }

        @Test
        @DisplayName("should work with Anthropic models")
        fun shouldWorkWithAnthropic() {
            // Given
            val messages = listOf(
                UserMessage.from("Test"),
                AiMessage.from("Response"),
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.ANTHROPIC,
                settings = OpenAiSettings(defaultModel = "claude-3-opus"),
            )

            // Then
            assertNotNull(result)
            assertTrue(result.messages().size >= 2)
        }

        @Test
        @DisplayName("should work with Gemini models")
        fun shouldWorkWithGemini() {
            // Given
            val messages = listOf(
                UserMessage.from("Test"),
                AiMessage.from("Response"),
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.GEMINI,
                settings = OpenAiSettings(defaultModel = "gemini-pro"),
            )

            // Then
            assertNotNull(result)
            assertTrue(result.messages().size >= 2)
        }

        @Test
        @DisplayName("should work with Ollama models")
        fun shouldWorkWithOllama() {
            // Given
            val messages = listOf(
                UserMessage.from("Test"),
                AiMessage.from("Response"),
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI_COMPATIBLE,
                settings = OpenAiSettings(defaultModel = "llama3"),
            )

            // Then
            assertNotNull(result)
            assertTrue(result.messages().size >= 2)
        }

        @Test
        @DisplayName("should place system messages before user messages")
        fun shouldPlaceSystemMessagesBeforeUserMessages() {
            // Given - create a request with user messages first
            val messages = listOf(
                UserMessage.from("Hello"),
                AiMessage.from("Hi there!"),
                UserMessage.from("How are you?"),
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When - transform with custom system messages (simulating user profile directive)
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            // Then - all system messages should come before any user/ai messages
            val resultMessages = result.messages()
            var foundNonSystemMessage = false
            for (msg in resultMessages) {
                if (msg is SystemMessage) {
                    assertTrue(!foundNonSystemMessage, "System message found after non-system message: ${msg.text()}")
                } else {
                    foundNonSystemMessage = true
                }
            }
        }
    }

    @Nested
    @DisplayName("Tool Call Handling")
    inner class ToolCallTests {

        @Test
        @DisplayName("should not drop tool_result messages with identical text from parallel tool calls")
        fun shouldNotDropParallelToolResultsWithIdenticalText() {
            // Given - an AiMessage with two parallel tool_use calls, both returning identical
            // text results (e.g. two "list files" calls on empty directories both returning "[]")
            val request1 = ToolExecutionRequest.builder()
                .id("toolu_01Aoaxsi9ygKmuSFYAwcRFTf")
                .name("list_files")
                .arguments("{\"path\":\"a\"}")
                .build()
            val request2 = ToolExecutionRequest.builder()
                .id("toolu_01VAxJvxu9WCD2Hb3yabKtPY")
                .name("list_files")
                .arguments("{\"path\":\"b\"}")
                .build()

            val aiMessage = AiMessage.from(listOf(request1, request2))
            val result1 = ToolExecutionResultMessage.from(request1, "[]")
            val result2 = ToolExecutionResultMessage.from(request2, "[]")

            val messages = listOf(
                UserMessage.from("List files in a and b"),
                aiMessage,
                result1,
                result2,
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.ANTHROPIC,
                settings = OpenAiSettings(defaultModel = "claude-3-opus"),
            )

            // Then - both tool_result messages must survive, matching their tool_use ids
            val toolResults = result.messages().filterIsInstance<ToolExecutionResultMessage>()
            assertEquals(2, toolResults.size, "Both tool_result messages must be kept, not deduplicated")
            val resultIds = toolResults.map { it.id() }.toSet()
            assertTrue(resultIds.contains("toolu_01Aoaxsi9ygKmuSFYAwcRFTf"))
            assertTrue(resultIds.contains("toolu_01VAxJvxu9WCD2Hb3yabKtPY"))
        }

        @Test
        @DisplayName("should collapse a consecutive tool_result duplicate with the same id and text (retry)")
        fun shouldCollapseSameIdSameTextToolResultDuplicate() {
            // Given - a retry appended the exact same tool_result (same id, same text) twice
            // in a row, e.g. because the streaming request was retried after a transient error
            // and the memory ended up with the tool result persisted twice.
            val request = ToolExecutionRequest.builder()
                .id("toolu_retry_dup")
                .name("some_tool")
                .arguments("{}")
                .build()

            val aiMessage = AiMessage.from(request)
            val result1 = ToolExecutionResultMessage.from(request, "same output")
            val result2 = ToolExecutionResultMessage.from(request, "same output")

            val messages = listOf(
                UserMessage.from("Run the tool"),
                aiMessage,
                result1,
                result2,
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.ANTHROPIC,
                settings = OpenAiSettings(defaultModel = "claude-3-opus"),
            )

            // Then - only one tool_result should remain for this id, avoiding a provider error
            // ("multiple tool_result blocks for the same tool_use id") and wasted tokens.
            val toolResults = result.messages().filterIsInstance<ToolExecutionResultMessage>()
            assertEquals(1, toolResults.size, "Exact duplicate tool_result (same id + text) must be collapsed")
            assertEquals("toolu_retry_dup", toolResults.first().id())
        }

        @Test
        @DisplayName("should NOT collapse consecutive tool_result messages with the same text but different ids")
        fun shouldNotCollapseDifferentIdSameTextToolResults() {
            // Given - two different tool_use ids (not a retry) that both happen to return the
            // exact same output text. These must both be preserved even though they are
            // "consecutive" and "identical" by text alone.
            val request1 = ToolExecutionRequest.builder()
                .id("toolu_call_one")
                .name("some_tool")
                .arguments("{\"x\":1}")
                .build()
            val request2 = ToolExecutionRequest.builder()
                .id("toolu_call_two")
                .name("some_tool")
                .arguments("{\"x\":2}")
                .build()

            val aiMessage = AiMessage.from(listOf(request1, request2))
            val result1 = ToolExecutionResultMessage.from(request1, "identical output")
            val result2 = ToolExecutionResultMessage.from(request2, "identical output")

            val messages = listOf(
                UserMessage.from("Run both tools"),
                aiMessage,
                result1,
                result2,
            )
            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.ANTHROPIC,
                settings = OpenAiSettings(defaultModel = "claude-3-opus"),
            )

            // Then
            val toolResults = result.messages().filterIsInstance<ToolExecutionResultMessage>()
            assertEquals(2, toolResults.size, "Different tool_use ids must never be collapsed, even with identical text")
            val resultIds = toolResults.map { it.id() }.toSet()
            assertTrue(resultIds.contains("toolu_call_one"))
            assertTrue(resultIds.contains("toolu_call_two"))
        }

        @Test
        @DisplayName("should keep tool_use/tool_result pairs atomic when truncating for token budget")
        fun shouldKeepToolCallPairsAtomicWhenTruncating() {
            val modelKey = ModelCapabilitiesCache.modelKey(ModelProvider.OPENAI, "gpt-3.5-turbo")
            ModelCapabilitiesCache.update(modelKey) { it.copy(contextSize = 16_384) }

            val messages = mutableListOf<ChatMessage>(SystemMessage.from("System directive"))

            // Add many large user/ai pairs to force truncation of older history
            repeat(50) { i ->
                messages.add(UserMessage.from("User message $i: " + "x".repeat(500)))
                messages.add(AiMessage.from("AI response $i: " + "y".repeat(500)))
            }

            // Add a tool_use/tool_result pair near the middle of history (a truncation candidate)
            val request = ToolExecutionRequest.builder()
                .id("toolu_middle_call")
                .name("some_tool")
                .arguments("{}")
                .build()
            messages.add(AiMessage.from(request))
            messages.add(ToolExecutionResultMessage.from(request, "tool output " + "z".repeat(500)))

            repeat(20) { i ->
                messages.add(UserMessage.from("Later user message $i: " + "x".repeat(500)))
                messages.add(AiMessage.from("Later AI response $i: " + "y".repeat(500)))
            }

            val chatRequest = ChatRequest.builder().messages(messages).build()

            // When
            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = null,
                chatRequest = chatRequest,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-3.5-turbo"),
            )

            // Then - if the tool_use AiMessage survived truncation, its tool_result must too (and vice versa)
            val resultMessages = result.messages()
            val hasToolUse = resultMessages.any { it is AiMessage && it.hasToolExecutionRequests() }
            val hasToolResult = resultMessages.any { it is ToolExecutionResultMessage }
            assertEquals(hasToolUse, hasToolResult, "tool_use and tool_result must be kept or dropped together")
        }
    }
}
