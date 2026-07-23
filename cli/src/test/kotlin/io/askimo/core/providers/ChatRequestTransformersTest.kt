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
import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.ChatSession
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
    @DisplayName("Session Directives")
    inner class SessionDirectiveTests {

        @Test
        fun `should add every active session directive in name order`() {
            val directiveRepository = DatabaseManager.getInstance().getChatDirectiveRepository()
            val sessionRepository = DatabaseManager.getInstance().getChatSessionRepository()
            val second = directiveRepository.save(ChatDirective(name = "Beta", content = "Second instruction"))
            val first = directiveRepository.save(ChatDirective(name = "Alpha", content = "First instruction"))
            val session = sessionRepository.createSession(ChatSession(id = "", title = "Multiple directives"))
            sessionRepository.replaceSessionDirectives(session.id, setOf(second.id, first.id))
            val request = ChatRequest.builder().messages(listOf(UserMessage.from("Hello"))).build()

            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = session.id,
                chatRequest = request,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            val systemTexts = result.messages().filterIsInstance<SystemMessage>().map { it.text() }
            assertEquals(listOf("First instruction", "Second instruction"), systemTexts)
        }

        @Test
        fun `should skip blank and duplicate active directive content`() {
            val directiveRepository = DatabaseManager.getInstance().getChatDirectiveRepository()
            val sessionRepository = DatabaseManager.getInstance().getChatSessionRepository()
            val duplicate = directiveRepository.save(ChatDirective(name = "Duplicate", content = "Existing instruction"))
            val blank = directiveRepository.save(ChatDirective(name = "Blank", content = ""))
            val session = sessionRepository.createSession(ChatSession(id = "", title = "Filtered directives"))
            sessionRepository.replaceSessionDirectives(session.id, setOf(duplicate.id, blank.id))
            val request = ChatRequest.builder()
                .messages(
                    listOf(
                        SystemMessage.from("Existing instruction"),
                        UserMessage.from("Hello"),
                    ),
                )
                .build()

            val result = ChatRequestTransformers.addCustomSystemMessagesAndRemoveDuplicates(
                sessionId = session.id,
                chatRequest = request,
                memoryId = null,
                provider = ModelProvider.OPENAI,
                settings = OpenAiSettings(defaultModel = "gpt-4"),
            )

            val systemTexts = result.messages().filterIsInstance<SystemMessage>().map { it.text() }
            assertEquals(listOf("Existing instruction"), systemTexts)
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
                provider = ModelProvider.OLLAMA,
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
}
