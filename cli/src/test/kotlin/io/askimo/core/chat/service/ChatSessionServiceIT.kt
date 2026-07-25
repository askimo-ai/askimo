/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.repository.ChatDirectiveRepository
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.context.MessageRole
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class ChatSessionServiceIT {

    @AfterEach
    fun tearDown() {
        sessionRepository.deleteAll()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var service: ChatSessionService
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var messageRepository: ChatMessageRepository
        private lateinit var directiveRepository: ChatDirectiveRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)

            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            sessionRepository = databaseManager.getChatSessionRepository()
            messageRepository = databaseManager.getChatMessageRepository()
            directiveRepository = databaseManager.getChatDirectiveRepository()
            val sessionMemoryRepository = databaseManager.getSessionMemoryRepository()
            val projectRepository = databaseManager.getProjectRepository()

            service = ChatSessionService(
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
                sessionMemoryRepository = sessionMemoryRepository,
                projectRepository = projectRepository,
                appContext = AppContext.initialize(ExecutionMode.STATELESS_MODE),
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            // Reset the singleton to close any file-based database that might have been created
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    @Test
    fun `should add message and update session timestamp through service`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test"))
        val originalUpdatedAt = session.updatedAt

        Thread.sleep(10)

        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "New message",
            ),
        )

        val updated = sessionRepository.getSession(session.id)
        assertTrue(updated!!.updatedAt.isAfter(originalUpdatedAt))
    }

    @Test
    fun `should get messages through service`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test"))

        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "Message 1",
            ),
        )
        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.ASSISTANT,
                content = "Message 2",
            ),
        )

        val messages = service.getMessages(session.id)

        assertEquals(2, messages.size)
        assertEquals("Message 1", messages[0].content)
        assertEquals("Message 2", messages[1].content)
    }

    @Test
    fun `should manage starred sessions through service`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test"))

        service.updateSessionStarred(session.id, true)

        val starredSessions = service.getStarredSessions()
        assertEquals(1, starredSessions.size)
        assertEquals(session.id, starredSessions[0].id)
    }

    @Test
    fun `should rename session through service`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Old Title"))

        service.renameTitle(session.id, "New Title")

        val updated = service.getSessionById(session.id)
        assertEquals("New Title", updated!!.title)
    }

    @Test
    fun `resumeSession should return all messages for a session`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))
        val baseTime = Instant.now()

        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "First message",
                createdAt = baseTime,
            ),
        )
        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.ASSISTANT,
                content = "Second message",
                createdAt = baseTime.plusSeconds(1),
            ),
        )
        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "Third message",
                createdAt = baseTime.plusSeconds(2),
            ),
        )

        // When
        val result = service.resumeSession(session.id)

        // Then
        assertTrue(result.success)
        assertEquals(session.id, result.sessionId)
        assertEquals(3, result.messages.size)
        assertEquals("First message", result.messages[0].content)
        assertEquals("Second message", result.messages[1].content)
        assertEquals("Third message", result.messages[2].content)
        assertNull(result.errorMessage)
    }

    @Test
    fun `resumeSession should return empty list for session with no messages`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Empty Session"))

        // When
        val result = service.resumeSession(session.id)

        // Then
        assertTrue(result.success)
        assertEquals(session.id, result.sessionId)
        assertEquals(0, result.messages.size)
        assertNull(result.errorMessage)
    }

    @Test
    fun `resumeSession should preserve message order`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Ordered Session"))
        val baseTime = Instant.now()

        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "Message 1",
                createdAt = baseTime,
            ),
        )
        Thread.sleep(10)
        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.ASSISTANT,
                content = "Message 2",
                createdAt = baseTime.plusSeconds(1),
            ),
        )
        Thread.sleep(10)
        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "Message 3",
                createdAt = baseTime.plusSeconds(2),
            ),
        )

        // When
        val result = service.resumeSession(session.id)

        // Then
        assertEquals(3, result.messages.size)
        assertEquals("Message 1", result.messages[0].content)
        assertEquals("Message 2", result.messages[1].content)
        assertEquals("Message 3", result.messages[2].content)
    }

    @Test
    fun `resumeSessionPaginated should return limited messages`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Paginated Session"))

        repeat(20) { i ->
            service.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = Instant.now().plusSeconds(i.toLong()),
                ),
            )
        }

        // When
        val result = service.resumeSessionPaginated(session.id, limit = 10)

        // Then
        assertTrue(result.success)
        assertEquals(session.id, result.sessionId)
        assertEquals(10, result.messages.size)
        assertTrue(result.hasMore)
        assertNotNull(result.cursor)
        assertNull(result.errorMessage)
    }

    @Test
    fun `resumeSessionPaginated should return most recent messages first`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Recent First"))
        val baseTime = Instant.now()

        repeat(15) { i ->
            service.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        // When
        val result = service.resumeSessionPaginated(session.id, limit = 5)

        // Then
        assertEquals(5, result.messages.size)
        // Messages should be in reverse chronological order (most recent first)
        val contents = result.messages.map { it.content }
        assertTrue(contents.contains("Message 14"))
        assertTrue(contents.contains("Message 13"))
        assertTrue(contents.contains("Message 12"))
        assertTrue(contents.contains("Message 11"))
        assertTrue(contents.contains("Message 10"))
    }

    @Test
    fun `resumeSessionPaginated should handle session with no messages`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Empty Paginated"))

        // When
        val result = service.resumeSessionPaginated(session.id, limit = 10)

        // Then
        assertTrue(result.success)
        assertEquals(session.id, result.sessionId)
        assertEquals(0, result.messages.size)
        assertNull(result.cursor)
        assertEquals(false, result.hasMore)
    }

    @Test
    fun `resumeSessionPaginated should handle session with fewer messages than limit`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Few Messages"))
        val baseTime = Instant.now()

        repeat(3) { i ->
            service.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        // When
        val result = service.resumeSessionPaginated(session.id, limit = 10)

        // Then
        assertTrue(result.success)
        assertEquals(3, result.messages.size)
        assertNull(result.cursor)
        assertEquals(false, result.hasMore)
    }

    @Test
    fun `resumeSessionPaginated should handle non-existent session gracefully`() {
        // Given
        val nonExistentId = "non-existent-session-id"

        // When
        val result = service.resumeSessionPaginated(nonExistentId, limit = 10)

        // Then
        assertTrue(result.success)
        assertEquals(nonExistentId, result.sessionId)
        assertEquals(0, result.messages.size)
        assertNull(result.cursor)
        assertEquals(false, result.hasMore)
        assertNull(result.directiveId)
    }

    @Test
    fun `resumeSessionPaginated should return session directive if present`() {
        // Given
        val directive = directiveRepository.save(
            ChatDirective(
                id = "",
                name = "Test Directive",
                content = "Test instruction",
            ),
        )
        val session = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Session with Directive",
                directiveId = directive.id,
            ),
        )
        val baseTime = Instant.now()

        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "Test message",
                createdAt = baseTime,
            ),
        )

        // When
        val result = service.resumeSessionPaginated(session.id, limit = 10)

        // Then
        assertTrue(result.success)
        assertEquals(directive.id, result.directiveId)
    }

    @Test
    fun `resumeSessionPaginated should handle limit of 1`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Single Message"))

        repeat(5) { i ->
            service.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = Instant.now().plusSeconds(i.toLong()),
                ),
            )
        }

        // When
        val result = service.resumeSessionPaginated(session.id, limit = 1)

        // Then
        assertEquals(1, result.messages.size)
        assertTrue(result.hasMore)
        assertNotNull(result.cursor)
    }

    @Test
    fun `resumeSessionPaginated should handle large limit`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Large Limit"))
        val baseTime = Instant.now()

        repeat(10) { i ->
            service.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        // When
        val result = service.resumeSessionPaginated(session.id, limit = 1000)

        // Then
        assertEquals(10, result.messages.size)
        assertNull(result.cursor)
        assertEquals(false, result.hasMore)
    }

    @Test
    fun `resumeSession should handle session with attachments`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "With Attachments"))
        val baseTime = Instant.now()

        service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.USER,
                content = "Message with attachment",
                createdAt = baseTime,
            ),
        )

        // When
        val result = service.resumeSession(session.id)

        // Then
        assertTrue(result.success)
        assertEquals(1, result.messages.size)
        assertNotNull(result.messages[0])
    }

    @Test
    fun `resumeSessionPaginated should maintain consistency with multiple calls`() {
        // Given
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Consistency Test"))

        repeat(15) { i ->
            service.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = Instant.now().plusSeconds(i.toLong()),
                ),
            )
        }

        // When - Load first page
        val firstResult = service.resumeSessionPaginated(session.id, limit = 5)

        // Then - First page should be consistent
        assertEquals(5, firstResult.messages.size)
        assertTrue(firstResult.hasMore)

        // When - Load again (should get same results for first page)
        val secondResult = service.resumeSessionPaginated(session.id, limit = 5)

        // Then - Results should be identical
        assertEquals(firstResult.messages.size, secondResult.messages.size)
        assertEquals(
            firstResult.messages.map { it.content },
            secondResult.messages.map { it.content },
        )
    }

    // -------------------------------------------------------------------------
    // getAllBookmarkGroups
    // -------------------------------------------------------------------------

    @Test
    fun `getAllBookmarkGroups should return empty list when no bookmarks exist`() {
        sessionRepository.createSession(ChatSession(id = "", title = "No Bookmarks"))

        val groups = service.getAllBookmarkGroups()

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `getAllBookmarkGroups should return empty list when messages exist but none are bookmarked`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Unbookmarked"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Not bookmarked"))

        val groups = service.getAllBookmarkGroups()

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `getAllBookmarkGroups should return one group with bookmarked message`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Single Bookmark"))
        val msg = service.addMessage(
            ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Bookmarked"),
        )
        service.toggleBookmark(msg.id)

        val groups = service.getAllBookmarkGroups()

        assertEquals(1, groups.size)
        assertEquals(session.id, groups[0].session.id)
        assertEquals(1, groups[0].messages.size)
        assertEquals("Bookmarked", groups[0].messages[0].content)
    }

    @Test
    fun `getAllBookmarkGroups should exclude non-bookmarked messages from the group`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Mixed"))
        val bookmarked = service.addMessage(
            ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Bookmarked", createdAt = Instant.now()),
        )
        service.addMessage(
            ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "Not bookmarked", createdAt = Instant.now().plusSeconds(1)),
        )
        service.toggleBookmark(bookmarked.id)

        val groups = service.getAllBookmarkGroups()

        assertEquals(1, groups.size)
        assertEquals(1, groups[0].messages.size)
        assertEquals("Bookmarked", groups[0].messages[0].content)
    }

    @Test
    fun `getAllBookmarkGroups should order messages within a group by createdAt ascending`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Message Order"))

        val first = service.addMessage(
            ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "First", createdAt = baseTime),
        )
        val second = service.addMessage(
            ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "Second", createdAt = baseTime.plusSeconds(1)),
        )
        val third = service.addMessage(
            ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Third", createdAt = baseTime.plusSeconds(2)),
        )
        service.toggleBookmark(first.id)
        service.toggleBookmark(second.id)
        service.toggleBookmark(third.id)

        val groups = service.getAllBookmarkGroups()

        assertEquals(1, groups.size)
        val contents = groups[0].messages.map { it.content }
        assertEquals(listOf("First", "Second", "Third"), contents)
    }

    @Test
    fun `getAllBookmarkGroups should return multiple sessions ordered by updatedAt descending`() {
        val baseTime = Instant.now()

        val olderSession = sessionRepository.createSession(ChatSession(id = "", title = "Older Session"))
        val msg1 = service.addMessage(
            ChatMessage(id = "", sessionId = olderSession.id, role = MessageRole.USER, content = "Older bookmark", createdAt = baseTime),
        )
        service.toggleBookmark(msg1.id)

        Thread.sleep(50)

        val newerSession = sessionRepository.createSession(ChatSession(id = "", title = "Newer Session"))
        val msg2 = service.addMessage(
            ChatMessage(id = "", sessionId = newerSession.id, role = MessageRole.USER, content = "Newer bookmark", createdAt = baseTime.plusSeconds(1)),
        )
        service.toggleBookmark(msg2.id)

        val groups = service.getAllBookmarkGroups()

        assertEquals(2, groups.size)
        // Newer session (more recently updated) must come first
        assertEquals(newerSession.id, groups[0].session.id)
        assertEquals(olderSession.id, groups[1].session.id)
    }

    @Test
    fun `getAllBookmarkGroups should group bookmarks correctly across multiple sessions`() {
        val session1 = sessionRepository.createSession(ChatSession(id = "", title = "Session 1"))
        val session2 = sessionRepository.createSession(ChatSession(id = "", title = "Session 2"))

        val a = service.addMessage(ChatMessage(id = "", sessionId = session1.id, role = MessageRole.USER, content = "S1-A"))
        val b = service.addMessage(ChatMessage(id = "", sessionId = session1.id, role = MessageRole.ASSISTANT, content = "S1-B"))
        val c = service.addMessage(ChatMessage(id = "", sessionId = session2.id, role = MessageRole.USER, content = "S2-A"))
        service.addMessage(ChatMessage(id = "", sessionId = session2.id, role = MessageRole.ASSISTANT, content = "S2-B-not-bookmarked"))

        service.toggleBookmark(a.id)
        service.toggleBookmark(b.id)
        service.toggleBookmark(c.id)

        val groups = service.getAllBookmarkGroups()

        assertEquals(2, groups.size)
        val groupBySessionId = groups.associateBy { it.session.id }

        assertEquals(2, groupBySessionId[session1.id]!!.messages.size)
        assertEquals(1, groupBySessionId[session2.id]!!.messages.size)
        assertEquals("S2-A", groupBySessionId[session2.id]!!.messages[0].content)
    }

    @Test
    fun `getAllBookmarkGroups should reflect toggle — removed bookmark disappears from group`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Toggle Test"))
        val msg = service.addMessage(
            ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Toggle me"),
        )

        service.toggleBookmark(msg.id) // bookmark on
        assertEquals(1, service.getAllBookmarkGroups().size)

        service.toggleBookmark(msg.id) // bookmark off
        assertTrue(service.getAllBookmarkGroups().isEmpty())
    }

    // -------------------------------------------------------------------------
    // forkSession
    // -------------------------------------------------------------------------

    @Test
    fun `forkSession should create a new session with 'Fork of' prefix in title`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "My Conversation"))
        val baseTime = Instant.now()
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Hello", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "World", createdAt = baseTime.plusSeconds(1)))

        val forked = service.forkSession(session.id, aiMsg.id)

        assertEquals("Fork of: My Conversation", forked.title)
    }

    @Test
    fun `forkSession should inherit directiveId and projectId from source session`() {
        val directive = directiveRepository.save(ChatDirective(id = "", name = "Directive", content = "Instructions"))
        val session = sessionRepository.createSession(ChatSession(id = "", title = "With Meta", directiveId = directive.id))
        val baseTime = Instant.now()
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A", createdAt = baseTime.plusSeconds(1)))

        val forked = service.forkSession(session.id, aiMsg.id)

        assertEquals(directive.id, forked.directiveId)
        assertNull(forked.projectId)
    }

    @Test
    fun `forkSession should copy all active messages up to and including the target AI message`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Copy Test"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q1", createdAt = baseTime))
        val aiMsg1 = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A1", createdAt = baseTime.plusSeconds(1)))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q2", createdAt = baseTime.plusSeconds(2)))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A2", createdAt = baseTime.plusSeconds(3)))

        val forked = service.forkSession(session.id, aiMsg1.id)
        val forkedMessages = service.getMessages(forked.id)

        assertEquals(2, forkedMessages.size)
        assertEquals("Q1", forkedMessages[0].content)
        assertEquals("A1", forkedMessages[1].content)
    }

    @Test
    fun `forkSession on the last AI message copies all messages in the session`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Full Fork"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q1", createdAt = baseTime))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A1", createdAt = baseTime.plusSeconds(1)))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q2", createdAt = baseTime.plusSeconds(2)))
        val lastAiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A2", createdAt = baseTime.plusSeconds(3)))

        val forked = service.forkSession(session.id, lastAiMsg.id)
        val forkedMessages = service.getMessages(forked.id)

        assertEquals(4, forkedMessages.size)
        assertEquals(listOf("Q1", "A1", "Q2", "A2"), forkedMessages.map { it.content })
    }

    @Test
    fun `forkSession on the first AI message copies only first two messages`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Shallow Fork"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q1", createdAt = baseTime))
        val firstAiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A1", createdAt = baseTime.plusSeconds(1)))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q2", createdAt = baseTime.plusSeconds(2)))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A2", createdAt = baseTime.plusSeconds(3)))

        val forked = service.forkSession(session.id, firstAiMsg.id)
        val forkedMessages = service.getMessages(forked.id)

        assertEquals(2, forkedMessages.size)
        assertEquals("Q1", forkedMessages[0].content)
        assertEquals("A1", forkedMessages[1].content)
    }

    @Test
    fun `forkSession should not copy outdated messages`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Outdated Test"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q1", createdAt = baseTime))
        val outdatedAi = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "Old answer", createdAt = baseTime.plusSeconds(1)))
        // Mark the outdated branch
        service.markMessagesAsOutdatedAfter(session.id, outdatedAi.id)
        messageRepository.markMessageAsOutdated(outdatedAi.id)
        // New active branch
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q2", createdAt = baseTime.plusSeconds(2)))
        val activeAi = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "New answer", createdAt = baseTime.plusSeconds(3)))

        val forked = service.forkSession(session.id, activeAi.id)
        val forkedMessages = service.getMessages(forked.id)

        // Only the two active messages should be copied
        assertEquals(2, forkedMessages.size)
        assertFalse(forkedMessages.any { it.content == "Old answer" })
        assertTrue(forkedMessages.any { it.content == "Q2" })
        assertTrue(forkedMessages.any { it.content == "New answer" })
    }

    @Test
    fun `forkSession should assign new unique IDs to all copied messages`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "ID Test"))
        val userMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A", createdAt = baseTime.plusSeconds(1)))

        val forked = service.forkSession(session.id, aiMsg.id)
        val forkedMessages = service.getMessages(forked.id)

        val originalIds = setOf(userMsg.id, aiMsg.id)
        forkedMessages.forEach { msg ->
            assertNotEquals(null, msg.id)
            assertFalse(originalIds.contains(msg.id), "Forked message should have a new ID, not ${msg.id}")
        }
    }

    @Test
    fun `forkSession should not transfer bookmarks to forked messages`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Bookmark Test"))
        val userMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A", createdAt = baseTime.plusSeconds(1)))
        service.toggleBookmark(userMsg.id)
        service.toggleBookmark(aiMsg.id)

        val forked = service.forkSession(session.id, aiMsg.id)
        val forkedMessages = service.getMessages(forked.id)

        forkedMessages.forEach { msg ->
            assertFalse(msg.isBookmarked, "Forked message '${msg.content}' should not be bookmarked")
        }
    }

    @Test
    fun `forkSession should not transfer editParentId to forked messages`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "EditParent Test"))
        val parentMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Original Q", createdAt = baseTime))
        val aiMsg = service.addMessage(
            ChatMessage(
                id = "",
                sessionId = session.id,
                role = MessageRole.ASSISTANT,
                content = "A",
                createdAt = baseTime.plusSeconds(1),
                editParentId = parentMsg.id,
            ),
        )

        val forked = service.forkSession(session.id, aiMsg.id)
        val forkedMessages = service.getMessages(forked.id)

        forkedMessages.forEach { msg ->
            assertNull(msg.editParentId, "Forked message should not carry editParentId")
        }
    }

    @Test
    fun `forkSession should preserve message content and roles`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Content Test"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "User question", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "AI answer", createdAt = baseTime.plusSeconds(1)))

        val forked = service.forkSession(session.id, aiMsg.id)
        val forkedMessages = service.getMessages(forked.id)

        assertEquals(2, forkedMessages.size)
        assertEquals("User question", forkedMessages[0].content)
        assertTrue(forkedMessages[0].isUser)
        assertEquals("AI answer", forkedMessages[1].content)
        assertFalse(forkedMessages[1].isUser)
    }

    @Test
    fun `forkSession should leave the source session completely untouched`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Source Untouched"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q1", createdAt = baseTime))
        val aiMsg1 = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A1", createdAt = baseTime.plusSeconds(1)))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q2", createdAt = baseTime.plusSeconds(2)))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A2", createdAt = baseTime.plusSeconds(3)))

        service.forkSession(session.id, aiMsg1.id)

        val sourceMessages = service.getMessages(session.id)
        assertEquals(4, sourceMessages.size)
        assertEquals(listOf("Q1", "A1", "Q2", "A2"), sourceMessages.map { it.content })
    }

    @Test
    fun `forkSession should produce a new session independent from the source`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Independence"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A", createdAt = baseTime.plusSeconds(1)))

        val forked = service.forkSession(session.id, aiMsg.id)

        assertNotEquals(session.id, forked.id)

        // Adding a message to the fork must not appear in the source
        service.addMessage(ChatMessage(id = "", sessionId = forked.id, role = MessageRole.USER, content = "Fork follow-up", createdAt = baseTime.plusSeconds(2)))

        val sourceMessages = service.getMessages(session.id)
        assertFalse(sourceMessages.any { it.content == "Fork follow-up" })

        val forkedMessages = service.getMessages(forked.id)
        assertTrue(forkedMessages.any { it.content == "Fork follow-up" })
    }

    @Test
    fun `forkSession throws IllegalArgumentException for unknown source session`() {
        assertThrows<IllegalArgumentException> {
            service.forkSession("non-existent-session-id", "any-message-id")
        }
    }

    @Test
    fun `forkSession throws IllegalArgumentException when message ID is not in active messages`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Bad Message ID"))
        val baseTime = Instant.now()
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q", createdAt = baseTime))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A", createdAt = baseTime.plusSeconds(1)))

        assertThrows<IllegalArgumentException> {
            service.forkSession(session.id, "message-id-that-does-not-exist")
        }
    }

    @Test
    fun `forkSession throws IllegalArgumentException when target message ID belongs to an outdated message`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Outdated Target"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q", createdAt = baseTime))
        val outdatedAi = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "Old A", createdAt = baseTime.plusSeconds(1)))
        // Mark the AI message itself as outdated
        messageRepository.markMessageAsOutdated(outdatedAi.id)

        // The outdated message ID must not be found in the active message list
        assertThrows<IllegalArgumentException> {
            service.forkSession(session.id, outdatedAi.id)
        }
    }

    @Test
    fun `forkSession forked messages all belong to the new session`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Session Ownership"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Q1", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "A1", createdAt = baseTime.plusSeconds(1)))

        val forked = service.forkSession(session.id, aiMsg.id)
        // Use the domain-level repository to access sessionId on the raw ChatMessage
        val forkedDomainMessages = messageRepository.getMessages(forked.id)

        forkedDomainMessages.forEach { msg ->
            assertEquals(forked.id, msg.sessionId, "Every copied message must belong to the forked session")
        }
    }

    @Test
    fun `forkSession forked session is retrievable via resumeSession`() {
        val baseTime = Instant.now()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Resumable Fork"))
        service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Hello", createdAt = baseTime))
        val aiMsg = service.addMessage(ChatMessage(id = "", sessionId = session.id, role = MessageRole.ASSISTANT, content = "Hi there", createdAt = baseTime.plusSeconds(1)))

        val forked = service.forkSession(session.id, aiMsg.id)
        val result = service.resumeSession(forked.id)

        assertTrue(result.success)
        assertEquals(forked.id, result.sessionId)
        assertEquals(2, result.messages.size)
    }
}
