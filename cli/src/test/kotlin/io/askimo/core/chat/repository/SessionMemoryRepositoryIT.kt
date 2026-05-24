/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.SessionMemory
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class SessionMemoryRepositoryIT {

    @AfterEach
    fun tearDown() {
        // Clean up test data after each test
        sessionRepository.deleteAll()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var memoryRepository: SessionMemoryRepository
        private lateinit var sessionRepository: ChatSessionRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            memoryRepository = databaseManager.getSessionMemoryRepository()
            sessionRepository = databaseManager.getChatSessionRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    @Test
    fun `should save and retrieve session memory`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        val memory = SessionMemory(
            sessionId = session.id,
            memorySummary = """{"keyFacts":{"language":"Kotlin"},"mainTopics":["testing"],"recentContext":"Writing tests"}""",
            memoryMessages = """[{"role":"user","content":"Hello"}]""",
        )

        val saved = memoryRepository.saveMemory(memory)

        assertNotNull(saved)
        assertEquals(session.id, saved.sessionId)
        assertEquals(memory.memorySummary, saved.memorySummary)
        assertEquals(memory.memoryMessages, saved.memoryMessages)

        val retrieved = memoryRepository.getBySessionId(session.id)
        assertNotNull(retrieved)
        assertEquals(session.id, retrieved.sessionId)
        assertEquals(memory.memorySummary, retrieved.memorySummary)
        assertEquals(memory.memoryMessages, retrieved.memoryMessages)
    }

    @Test
    fun `should return null for non-existent session memory`() {
        val result = memoryRepository.getBySessionId("non-existent-session-id")
        assertNull(result)
    }

    @Test
    fun `should save session memory without summary`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        val memory = SessionMemory(
            sessionId = session.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"First message"}]""",
        )

        val saved = memoryRepository.saveMemory(memory)

        assertNotNull(saved)
        assertNull(saved.memorySummary)
        assertEquals(memory.memoryMessages, saved.memoryMessages)

        val retrieved = memoryRepository.getBySessionId(session.id)
        assertNotNull(retrieved)
        assertNull(retrieved.memorySummary)
    }

    @Test
    fun `should override existing memory when saving again`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        val memory1 = SessionMemory(
            sessionId = session.id,
            memorySummary = """{"keyFacts":{},"mainTopics":[],"recentContext":"First save"}""",
            memoryMessages = """[{"role":"user","content":"Message 1"}]""",
        )

        memoryRepository.saveMemory(memory1)

        val memory2 = SessionMemory(
            sessionId = session.id,
            memorySummary = """{"keyFacts":{"updated":"true"},"mainTopics":["new"],"recentContext":"Second save"}""",
            memoryMessages = """[{"role":"user","content":"Message 1"},{"role":"assistant","content":"Response"}]""",
            lastUpdated = Instant.now(),
        )

        memoryRepository.saveMemory(memory2)

        val retrieved = memoryRepository.getBySessionId(session.id)
        assertNotNull(retrieved)
        assertEquals(memory2.memorySummary, retrieved.memorySummary)
        assertEquals(memory2.memoryMessages, retrieved.memoryMessages)
    }

    @Test
    fun `should update lastUpdated timestamp when overriding memory`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        val memory1 = SessionMemory(
            sessionId = session.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Original"}]""",
        )

        memoryRepository.saveMemory(memory1)
        val firstRetrieved = memoryRepository.getBySessionId(session.id)
        val firstTimestamp = firstRetrieved!!.lastUpdated

        Thread.sleep(10)

        val memory2 = SessionMemory(
            sessionId = session.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Updated"}]""",
            lastUpdated = Instant.now(),
        )

        memoryRepository.saveMemory(memory2)
        val secondRetrieved = memoryRepository.getBySessionId(session.id)
        val secondTimestamp = secondRetrieved!!.lastUpdated

        assertTrue(!secondTimestamp.isBefore(firstTimestamp))
    }

    @Test
    fun `should cascade delete memory when session is deleted`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        val memory = SessionMemory(
            sessionId = session.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Test"}]""",
        )

        memoryRepository.saveMemory(memory)
        assertNotNull(memoryRepository.getBySessionId(session.id))

        // Delete the session - memory should be cascade deleted
        sessionRepository.deleteSession(session.id)

        val retrieved = memoryRepository.getBySessionId(session.id)
        assertNull(retrieved)
    }

    @Test
    fun `should not affect other session memories when deleting one session`() {
        val session1 = sessionRepository.createSession(ChatSession(id = "", title = "Session 1"))
        val session2 = sessionRepository.createSession(ChatSession(id = "", title = "Session 2"))

        val memory1 = SessionMemory(
            sessionId = session1.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Session 1 messages"}]""",
        )
        val memory2 = SessionMemory(
            sessionId = session2.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Session 2 messages"}]""",
        )

        memoryRepository.saveMemory(memory1)
        memoryRepository.saveMemory(memory2)

        sessionRepository.deleteSession(session1.id)

        assertNull(memoryRepository.getBySessionId(session1.id))
        assertNotNull(memoryRepository.getBySessionId(session2.id))
    }

    @Test
    fun `should cleanup old memories older than threshold`() {
        val session1 = sessionRepository.createSession(ChatSession(id = "", title = "Old Session"))
        val session2 = sessionRepository.createSession(ChatSession(id = "", title = "Recent Session"))

        val oldTime = Instant.now().minus(Duration.ofDays(12))
        val recentTime = Instant.now()

        val oldMemory = SessionMemory(
            sessionId = session1.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Old"}]""",
            lastUpdated = oldTime,
            createdAt = oldTime,
        )
        val recentMemory = SessionMemory(
            sessionId = session2.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Recent"}]""",
            lastUpdated = recentTime,
            createdAt = recentTime,
        )

        memoryRepository.saveMemory(oldMemory)
        memoryRepository.saveMemory(recentMemory)

        val threshold = Instant.now().minus(Duration.ofDays(5))
        val deletedCount = memoryRepository.cleanupOldMemories(threshold)

        assertEquals(1, deletedCount)
        assertNull(memoryRepository.getBySessionId(session1.id))
        assertNotNull(memoryRepository.getBySessionId(session2.id))
    }

    @Test
    fun `should return zero when cleaning up with no old memories`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Recent Session"))

        val memory = SessionMemory(
            sessionId = session.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Recent"}]""",
        )

        memoryRepository.saveMemory(memory)

        val threshold = Instant.now().minus(Duration.ofDays(1))
        val deletedCount = memoryRepository.cleanupOldMemories(threshold)

        assertEquals(0, deletedCount)
        assertNotNull(memoryRepository.getBySessionId(session.id))
    }

    @Test
    fun `should preserve createdAt timestamp when updating memory`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        val memory1 = SessionMemory(
            sessionId = session.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Original"}]""",
        )

        memoryRepository.saveMemory(memory1)
        val firstRetrieved = memoryRepository.getBySessionId(session.id)
        val originalCreatedAt = firstRetrieved!!.createdAt

        Thread.sleep(10)

        val memory2 = SessionMemory(
            sessionId = session.id,
            memorySummary = null,
            memoryMessages = """[{"role":"user","content":"Updated"}]""",
            lastUpdated = Instant.now(),
            createdAt = originalCreatedAt, // Should preserve original createdAt
        )

        memoryRepository.saveMemory(memory2)
        val secondRetrieved = memoryRepository.getBySessionId(session.id)

        assertEquals(
            originalCreatedAt.truncatedTo(ChronoUnit.SECONDS),
            secondRetrieved!!.createdAt.truncatedTo(ChronoUnit.SECONDS),
        )
    }

    @Test
    fun `should save memory with large JSON content`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        // Create a large message history
        val largeMessages = buildString {
            append("[")
            repeat(100) { i ->
                if (i > 0) append(",")
                append("""{"role":"user","content":"Message $i with some content"}""")
            }
            append("]")
        }

        val memory = SessionMemory(
            sessionId = session.id,
            memorySummary = """{"keyFacts":{"count":"100"},"mainTopics":["testing","performance"],"recentContext":"Large message history test"}""",
            memoryMessages = largeMessages,
        )

        val saved = memoryRepository.saveMemory(memory)
        assertNotNull(saved)

        val retrieved = memoryRepository.getBySessionId(session.id)
        assertNotNull(retrieved)
        assertEquals(largeMessages, retrieved.memoryMessages)
    }

    @Test
    fun `should handle empty JSON objects in summary`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))

        val memory = SessionMemory(
            sessionId = session.id,
            memorySummary = """{"keyFacts":{},"mainTopics":[],"recentContext":""}""",
            memoryMessages = """[]""",
        )

        val saved = memoryRepository.saveMemory(memory)
        assertNotNull(saved)

        val retrieved = memoryRepository.getBySessionId(session.id)
        assertNotNull(retrieved)
        assertEquals(memory.memorySummary, retrieved.memorySummary)
        assertEquals(memory.memoryMessages, retrieved.memoryMessages)
    }
}
