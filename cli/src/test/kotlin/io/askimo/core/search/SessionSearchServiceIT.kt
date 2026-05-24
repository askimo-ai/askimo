/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.search

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.context.MessageRole
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionSearchServiceIT {

    private lateinit var testSession1: ChatSession
    private lateinit var testSession2: ChatSession
    private lateinit var testProject: Project

    @BeforeEach
    fun setUp() {
        // Create test sessions
        testSession1 = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Bug Fix Discussion",
            ),
        )
        testSession2 = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Code Review Session",
            ),
        )

        // Create test project
        testProject = projectRepository.createProject(
            Project(
                id = "",
                name = "Test Project",
                description = "Test project for search",
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        // Clean up sessions - handle exceptions to ensure all cleanup happens
        runCatching {
            if (::testSession1.isInitialized) {
                sessionRepository.deleteSession(testSession1.id)
            }
        }
        runCatching {
            if (::testSession2.isInitialized) {
                sessionRepository.deleteSession(testSession2.id)
            }
        }
        runCatching {
            if (::testProject.isInitialized) {
                projectRepository.deleteProject(testProject.id)
            }
        }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var messageRepository: ChatMessageRepository
        private lateinit var projectRepository: ProjectRepository
        private lateinit var searchService: SessionSearchService

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)

            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            // Get repositories from DatabaseManager
            sessionRepository = databaseManager.getChatSessionRepository()
            messageRepository = databaseManager.getChatMessageRepository()
            projectRepository = databaseManager.getProjectRepository()

            // Initialize search service
            searchService = SessionSearchService(
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    @Test
    fun `should find messages matching search query`() = runBlocking {
        // Given: Messages with specific content
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "How do I fix the null pointer exception?",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.ASSISTANT,
                content = "The null pointer occurs when you access an object that is null.",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession2.id,
                role = MessageRole.USER,
                content = "Please review my code for best practices.",
            ),
        )

        // When: Search for "null pointer"
        val results = searchService.searchSessions(
            query = "null pointer",
            dateFilter = DateFilter.ALL_TIME,
            sortBy = SortBy.DATE_DESC,
        )

        // Then: Should find 2 messages containing "null pointer"
        assertEquals(2, results.size)
        assertTrue(results.all { it.messageContent.contains("null pointer", ignoreCase = true) })
        assertEquals(testSession1.id, results[0].sessionId)
        assertEquals(testSession1.title, results[0].sessionTitle)
    }

    @Test
    fun `should return empty results for non-matching query`() = runBlocking {
        // Given: Messages without the search term
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Hello, how are you?",
            ),
        )

        // When: Search for non-existing term
        val results = searchService.searchSessions(
            query = "nonexistent term",
            dateFilter = DateFilter.ALL_TIME,
        )

        // Then: Should return empty list
        assertTrue(results.isEmpty())
    }

    @Test
    fun `should return empty results for blank query`() = runBlocking {
        // Given: Some messages exist
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Test message",
            ),
        )

        // When: Search with blank query
        val results = searchService.searchSessions(
            query = "   ",
            dateFilter = DateFilter.ALL_TIME,
        )

        // Then: Should return empty list
        assertTrue(results.isEmpty())
    }

    @Test
    fun `should filter by date range`() = runBlocking {
        // Given: Messages from different time periods
        val now = Instant.now()
        val recent = now.minus(Duration.ofHours(2)) // Within any reasonable date filter
        val old = now.minus(Duration.ofDays(30)) // Outside most date filters

        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Recent search term",
                createdAt = recent,
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession2.id,
                role = MessageRole.USER,
                content = "Old search term",
                createdAt = old,
            ),
        )

        // When: Search with LAST_7_DAYS filter (testing the mechanism works)
        val results = searchService.searchSessions(
            query = "search term",
            dateFilter = DateFilter.LAST_7_DAYS,
        )

        // Then: Should only find recent message, proving date filtering works
        assertEquals(1, results.size)
        assertEquals("Recent search term", results[0].messageContent)
    }

    @Test
    fun `should filter by project`() = runBlocking {
        // Given: Sessions with and without project
        val sessionWithProject = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Project Session",
                projectId = testProject.id,
            ),
        )
        val sessionWithoutProject = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "General Session",
                projectId = null,
            ),
        )

        try {
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = sessionWithProject.id,
                    role = MessageRole.USER,
                    content = "Project related query",
                ),
            )
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = sessionWithoutProject.id,
                    role = MessageRole.USER,
                    content = "General query",
                ),
            )

            // When: Search with project filter
            val results = searchService.searchSessions(
                query = "query",
                projectId = testProject.id,
            )

            // Then: Should only find messages from the project
            assertEquals(1, results.size)
            assertEquals(sessionWithProject.id, results[0].sessionId)
            assertEquals(testProject.id, results[0].projectId)
            assertEquals("Project related query", results[0].messageContent)
        } finally {
            sessionRepository.deleteSession(sessionWithProject.id)
            sessionRepository.deleteSession(sessionWithoutProject.id)
        }
    }

    @Test
    fun `should sort by DATE_DESC - newest first`() = runBlocking {
        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val older = now.minus(Duration.ofHours(2))
        val newer = now.minus(Duration.ofHours(1))

        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Older test message",
                createdAt = older,
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession2.id,
                role = MessageRole.USER,
                content = "Newer test message",
                createdAt = newer,
            ),
        )

        // When: Search with DATE_DESC sorting
        val results = searchService.searchSessions(
            query = "test message",
            sortBy = SortBy.DATE_DESC,
        )

        // Then: Newer message should come first
        assertEquals(2, results.size)
        assertEquals("Newer test message", results[0].messageContent)
        assertEquals("Older test message", results[1].messageContent)
    }

    @Test
    fun `should sort by DATE_ASC - oldest first`() = runBlocking {
        // Given: Messages at different times
        // Use larger time differences to avoid timing precision issues
        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val older = now.minus(Duration.ofHours(2))
        val newer = now.minus(Duration.ofHours(1))

        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Older test message",
                createdAt = older,
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession2.id,
                role = MessageRole.USER,
                content = "Newer test message",
                createdAt = newer,
            ),
        )

        // When: Search with DATE_ASC sorting
        val results = searchService.searchSessions(
            query = "test message",
            sortBy = SortBy.DATE_ASC,
        )

        // Then: Older message should come first
        assertEquals(2, results.size)
        assertEquals("Older test message", results[0].messageContent)
        assertEquals("Newer test message", results[1].messageContent)
    }

    @Test
    fun `should respect limit parameter`() = runBlocking {
        // Given: More messages than the limit
        repeat(10) { index ->
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession1.id,
                    role = MessageRole.USER,
                    content = "Message $index with searchable content",
                ),
            )
        }

        // When: Search with limit of 5
        val results = searchService.searchSessions(
            query = "searchable",
            limit = 5,
        )

        // Then: Should return exactly 5 results
        assertEquals(5, results.size)
    }

    @Test
    fun `should distinguish between user and assistant messages`() = runBlocking {
        // Given: Both user and assistant messages
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "User asks about testing",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.ASSISTANT,
                content = "Assistant explains testing",
            ),
        )

        // When: Search for "testing"
        val results = searchService.searchSessions(
            query = "testing",
        )

        // Then: Should correctly identify message roles
        assertEquals(2, results.size)
        val userMessage = results.find { it.isUserMessage }
        val assistantMessage = results.find { !it.isUserMessage }

        assertTrue(userMessage?.messageContent?.contains("User") == true)
        assertTrue(assistantMessage?.messageContent?.contains("Assistant") == true)
    }

    @Test
    fun `should search case-insensitively`() = runBlocking {
        // Given: Message with mixed case
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "The QUICK Brown Fox",
            ),
        )

        // When: Search with different case variations
        val resultsLower = searchService.searchSessions(query = "quick")
        val resultsUpper = searchService.searchSessions(query = "BROWN")
        val resultsMixed = searchService.searchSessions(query = "FoX")

        // Then: All should find the message
        assertEquals(1, resultsLower.size)
        assertEquals(1, resultsUpper.size)
        assertEquals(1, resultsMixed.size)
    }

    @Test
    fun `should find partial word matches`() = runBlocking {
        // Given: Message with specific words
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Understanding asynchronous programming",
            ),
        )

        // When: Search for partial word
        val results = searchService.searchSessions(query = "async")

        // Then: Should find the message
        assertEquals(1, results.size)
        assertTrue(results[0].messageContent.contains("asynchronous", ignoreCase = true))
    }

    @Test
    fun `should handle multiple sessions with same search term`() = runBlocking {
        // Given: Multiple sessions with the same keyword
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Error in module A",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession2.id,
                role = MessageRole.USER,
                content = "Error in module B",
            ),
        )

        // When: Search for common term
        val results = searchService.searchSessions(query = "Error")

        // Then: Should find messages from both sessions
        assertEquals(2, results.size)
        val sessionIds = results.map { it.sessionId }.toSet()
        assertEquals(2, sessionIds.size)
        assertTrue(sessionIds.contains(testSession1.id))
        assertTrue(sessionIds.contains(testSession2.id))
    }

    @Test
    fun `should include session metadata in results`() = runBlocking {
        // Given: Message in a session
        val message = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession1.id,
                role = MessageRole.USER,
                content = "Test message for metadata",
            ),
        )

        // When: Search for the message
        val results = searchService.searchSessions(query = "metadata")

        // Then: Result should include all metadata
        assertEquals(1, results.size)
        val result = results[0]
        assertEquals(testSession1.id, result.sessionId)
        assertEquals(testSession1.title, result.sessionTitle)
        assertEquals(message.id, result.messageId)
        assertEquals(message.content, result.messageContent)
        assertTrue(result.isUserMessage)
        assertEquals(null, result.projectId)
    }

    @Test
    fun `should handle empty database gracefully`() = runBlocking {
        // Given: Empty database (no messages added)
        // When: Search for anything
        val results = searchService.searchSessions(query = "anything")

        // Then: Should return empty list without errors
        assertTrue(results.isEmpty())
    }

    @Test
    fun `should combine multiple filters`() = runBlocking {
        // Given: Session with project
        val projectSession = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Project Test Session",
                projectId = testProject.id,
            ),
        )

        try {
            val now = Instant.now()
            val recent = now.minus(Duration.ofHours(2)) // Definitely within last 7 days
            val old = now.minus(Duration.ofDays(10)) // Definitely outside last 7 days

            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = projectSession.id,
                    role = MessageRole.USER,
                    content = "Recent project keyword",
                    createdAt = recent,
                ),
            )
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = projectSession.id,
                    role = MessageRole.USER,
                    content = "Old project keyword",
                    createdAt = old,
                ),
            )
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession1.id,
                    role = MessageRole.USER,
                    content = "Recent non-project keyword",
                    createdAt = recent,
                ),
            )

            // When: Search with both project and date filters
            val results = searchService.searchSessions(
                query = "keyword",
                projectId = testProject.id,
                dateFilter = DateFilter.LAST_7_DAYS,
            )

            // Then: Should only find recent message from the project
            assertEquals(1, results.size)
            assertEquals("Recent project keyword", results[0].messageContent)
            assertEquals(testProject.id, results[0].projectId)
        } finally {
            sessionRepository.deleteSession(projectSession.id)
        }
    }
}
