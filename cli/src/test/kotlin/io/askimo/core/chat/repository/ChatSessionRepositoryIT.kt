/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.SESSION_TITLE_MAX_LENGTH
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

class ChatSessionRepositoryIT {

    @AfterEach
    fun tearDown() {
        sessionRepository.deleteAll()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var sessionRepository: ChatSessionRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            sessionRepository = databaseManager.getChatSessionRepository()
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
    fun `should create and retrieve a chat session`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        assertNotNull(session.id)
        assertEquals("Test Session", session.title)
        assertNotNull(session.createdAt)
        assertNotNull(session.updatedAt)

        val retrieved = sessionRepository.getSession(session.id)
        assertNotNull(retrieved)
        assertEquals(session.id, retrieved.id)
        assertEquals(session.title, retrieved.title)
        assertEquals(session.createdAt.truncatedTo(ChronoUnit.SECONDS), retrieved.createdAt.truncatedTo(ChronoUnit.SECONDS))
        assertEquals(session.updatedAt.truncatedTo(ChronoUnit.SECONDS), retrieved.updatedAt.truncatedTo(ChronoUnit.SECONDS))
    }

    @Test
    fun `should return null for non-existent session`() {
        val result = sessionRepository.getSession("non-existent-id")
        assertNull(result)
    }

    @Test
    fun `should retrieve all sessions ordered by updated_at desc`() {
        val baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val session1 = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "First Session",
                createdAt = baseTime.plusSeconds(1L),
                updatedAt = baseTime.plusSeconds(1),
            ),
        )
        val session2 = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Second Session",
                createdAt = baseTime.plusSeconds(2),
                updatedAt = baseTime.plusSeconds(2),
            ),
        )
        val session3 = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Third Session",
                createdAt = baseTime.plusSeconds(3),
                updatedAt = baseTime.plusSeconds(3),
            ),
        )

        val sessions = sessionRepository.getSessions(10)

        assertEquals(3, sessions.size)
        assertEquals(session3.id, sessions[0].id)
        assertEquals(session2.id, sessions[1].id)
        assertEquals(session1.id, sessions[2].id)
    }

    @Test
    fun `should delete session successfully`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Session to Delete"))

        val deleted = sessionRepository.deleteSession(session.id)

        assertTrue(deleted)
        assertNull(sessionRepository.getSession(session.id))
    }

    @Test
    fun `should return false when deleting non-existent session`() {
        val deleted = sessionRepository.deleteSession("non-existent-id")
        assertFalse(deleted)
    }

    @Test
    fun `should not affect other sessions when deleting one`() {
        val session1 = sessionRepository.createSession(ChatSession(id = "", title = "Session 1"))
        val session2 = sessionRepository.createSession(ChatSession(id = "", title = "Session 2"))

        sessionRepository.deleteSession(session1.id)

        assertNull(sessionRepository.getSession(session1.id))
        assertNotNull(sessionRepository.getSession(session2.id))
    }

    @Test
    fun `should create session with starred flag`() {
        val session = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred Session", isStarred = true),
        )

        val retrieved = sessionRepository.getSession(session.id)
        assertNotNull(retrieved)
        assertTrue(retrieved.isStarred)
    }

    @Test
    fun `should create session without starred flag by default`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Regular Session"))

        val retrieved = sessionRepository.getSession(session.id)
        assertNotNull(retrieved)
        assertFalse(retrieved.isStarred)
    }

    @Test
    fun `should star a session`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Session to Star"))

        val updated = sessionRepository.updateSessionStarred(session.id, true)

        assertTrue(updated)
        val retrieved = sessionRepository.getSession(session.id)
        assertTrue(retrieved!!.isStarred)
    }

    @Test
    fun `should unstar a session`() {
        val session = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred Session", isStarred = true),
        )

        val updated = sessionRepository.updateSessionStarred(session.id, false)

        assertTrue(updated)
        val retrieved = sessionRepository.getSession(session.id)
        assertFalse(retrieved!!.isStarred)
    }

    @Test
    fun `should get all starred sessions`() {
        val starred1 = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred 1", isStarred = true),
        )
        sessionRepository.createSession(ChatSession(id = "", title = "Not Starred"))
        val starred2 = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred 2", isStarred = true),
        )

        val starredSessions = sessionRepository.getStarredSessions()

        assertEquals(2, starredSessions.size)
        assertTrue(starredSessions.any { it.id == starred1.id })
        assertTrue(starredSessions.any { it.id == starred2.id })
    }

    @Test
    fun `should return empty list when no starred sessions`() {
        sessionRepository.createSession(ChatSession(id = "", title = "Regular Session"))

        val starredSessions = sessionRepository.getStarredSessions()

        assertTrue(starredSessions.isEmpty())
    }

    @Test
    fun `should get starred sessions ordered by sort order and updated time`() {
        val starred1 = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred 1", isStarred = true, sortOrder = 2),
        )
        val starred2 = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred 2", isStarred = true, sortOrder = 1),
        )

        val starredSessions = sessionRepository.getStarredSessions()

        assertEquals(2, starredSessions.size)
        assertEquals(starred2.id, starredSessions[0].id)
        assertEquals(starred1.id, starredSessions[1].id)
    }

    @Test
    fun `should create session with sort order`() {
        val session = sessionRepository.createSession(
            ChatSession(id = "", title = "Ordered Session", sortOrder = 5),
        )

        val retrieved = sessionRepository.getSession(session.id)
        assertEquals(5, retrieved!!.sortOrder)
    }

    @Test
    fun `should create session with default sort order`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Default Order"))

        val retrieved = sessionRepository.getSession(session.id)
        assertEquals(0, retrieved!!.sortOrder)
    }

    @Test
    fun `should update session sort order`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Session"))

        val updated = sessionRepository.updateSessionSortOrder(session.id, 10)

        assertTrue(updated)
        val retrieved = sessionRepository.getSession(session.id)
        assertEquals(10, retrieved!!.sortOrder)
    }

    @Test
    fun `should update session title`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Old Title"))

        val updated = sessionRepository.updateSessionTitle(session.id, "New Title")

        assertTrue(updated)
        val retrieved = sessionRepository.getSession(session.id)
        assertEquals("New Title", retrieved!!.title)
    }

    @Test
    fun `should trim whitespace when updating session title`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Old Title"))

        sessionRepository.updateSessionTitle(session.id, "  New Title  ")

        val retrieved = sessionRepository.getSession(session.id)
        assertEquals("New Title", retrieved!!.title)
    }

    @Test
    fun `should return false when updating title with empty string`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Old Title"))

        val updated = sessionRepository.updateSessionTitle(session.id, "")

        assertFalse(updated)
    }

    @Test
    fun `should return false when updating title with only whitespace`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Old Title"))

        val updated = sessionRepository.updateSessionTitle(session.id, "   ")

        assertFalse(updated)
    }

    @Test
    fun `should truncate title to maximum length when updating`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Old Title"))
        val longTitle = "a".repeat(300)

        sessionRepository.updateSessionTitle(session.id, longTitle)

        val retrieved = sessionRepository.getSession(session.id)
        assertEquals(SESSION_TITLE_MAX_LENGTH, retrieved!!.title.length)
    }

    @Test
    fun `should update session updated_at when changing title`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Old Title"))
        val originalUpdatedAt = session.updatedAt

        Thread.sleep(10)
        sessionRepository.updateSessionTitle(session.id, "New Title")

        val retrieved = sessionRepository.getSession(session.id)
        assertNotEquals(originalUpdatedAt, retrieved!!.updatedAt)
    }

    @Test
    fun `should order sessions by starred, sort order, and updated time`() {
        val session1 = sessionRepository.createSession(
            ChatSession(id = "", title = "Normal", sortOrder = 1),
        )
        val session2 = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred", isStarred = true, sortOrder = 2),
        )
        val session3 = sessionRepository.createSession(
            ChatSession(id = "", title = "Starred First", isStarred = true, sortOrder = 1),
        )

        val sessions = sessionRepository.getSessions(10)

        assertEquals(3, sessions.size)
        assertEquals(session3.id, sessions[0].id) // Starred, sort order 1
        assertEquals(session2.id, sessions[1].id) // Starred, sort order 2
        assertEquals(session1.id, sessions[2].id) // Not starred
    }

    @Test
    fun `should touch session to update timestamp`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test"))
        val originalUpdatedAt = session.updatedAt.truncatedTo(ChronoUnit.SECONDS)

        Thread.sleep(1001) // Sleep for just over 1 second to ensure timestamp difference
        sessionRepository.touchSession(session.id)

        val retrieved = sessionRepository.getSession(session.id)
        val newUpdatedAt = retrieved!!.updatedAt.truncatedTo(ChronoUnit.SECONDS)
        assertTrue(newUpdatedAt.isAfter(originalUpdatedAt))
    }
}
