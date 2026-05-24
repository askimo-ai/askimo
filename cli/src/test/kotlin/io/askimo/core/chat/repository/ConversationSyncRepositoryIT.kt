/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.context.MessageRole
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class ConversationSyncRepositoryIT {

    @BeforeEach
    fun cleanUp() {
        // Clean messages before sessions (FK order)
        sessionRepository.deleteAll()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var messageRepository: ChatMessageRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            sessionRepository = databaseManager.getChatSessionRepository()
            messageRepository = databaseManager.getChatMessageRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val older = now.minus(Duration.ofHours(2))
    private val newer = now.plus(Duration.ofHours(2))

    private fun createSession(
        title: String = "Test session",
        updatedAt: Instant = now,
    ): ChatSession = sessionRepository.createSession(
        ChatSession(id = "", title = title, createdAt = older, updatedAt = updatedAt),
    )

    private fun sessionFromServer(
        id: String,
        title: String = "Server session",
        updatedAt: Instant = now,
    ) = ChatSession(id = id, title = title, createdAt = older, updatedAt = updatedAt)

    private fun messageFromServer(
        id: String,
        sessionId: String,
        content: String = "Hello",
        role: MessageRole = MessageRole.USER,
    ) = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        createdAt = now,
    )

    @Nested
    inner class UpsertFromServer {

        @Test
        fun `inserts session when it does not exist locally`() {
            val id = "sess-new"
            sessionRepository.upsertFromServer(listOf(sessionFromServer(id, title = "Brand new")))

            val stored = sessionRepository.getSession(id)
            assertNotNull(stored)
            assertEquals("Brand new", stored!!.title)
        }

        @Test
        fun `sets syncedAt on fresh insert`() {
            val id = "sess-sync-insert"
            sessionRepository.upsertFromServer(listOf(sessionFromServer(id)))

            // syncedAt is a raw TEXT column - verify via a second upsert with an older
            // updatedAt: if syncedAt was set, the row should NOT be overwritten.
            sessionRepository.upsertFromServer(
                listOf(sessionFromServer(id, title = "Older title", updatedAt = older)),
            )
            assertEquals("Server session", sessionRepository.getSession(id)!!.title)
        }

        @Test
        fun `updates title when server updatedAt is strictly newer`() {
            val local = createSession(title = "Old title", updatedAt = older)
            sessionRepository.upsertFromServer(
                listOf(sessionFromServer(local.id, title = "New title", updatedAt = newer)),
            )

            assertEquals("New title", sessionRepository.getSession(local.id)!!.title)
        }

        @Test
        fun `does NOT overwrite when server updatedAt equals local updatedAt`() {
            val local = createSession(title = "Original", updatedAt = now)
            sessionRepository.upsertFromServer(
                listOf(sessionFromServer(local.id, title = "Attempted overwrite", updatedAt = now)),
            )

            assertEquals("Original", sessionRepository.getSession(local.id)!!.title)
        }

        @Test
        fun `does NOT overwrite when server updatedAt is older than local`() {
            val local = createSession(title = "Local newer", updatedAt = newer)
            sessionRepository.upsertFromServer(
                listOf(sessionFromServer(local.id, title = "Server older", updatedAt = older)),
            )

            assertEquals("Local newer", sessionRepository.getSession(local.id)!!.title)
        }

        @Test
        fun `is idempotent - calling twice with same data produces one row`() {
            val id = "sess-idempotent"
            val dto = sessionFromServer(id, title = "Same")
            sessionRepository.upsertFromServer(listOf(dto))
            sessionRepository.upsertFromServer(listOf(dto))

            assertEquals(1, sessionRepository.getSessionsByIds(listOf(id)).size)
        }

        @Test
        fun `processes a batch of sessions in one call`() {
            val ids = listOf("sess-batch-1", "sess-batch-2", "sess-batch-3")
            sessionRepository.upsertFromServer(ids.map { sessionFromServer(it, title = it) })

            ids.forEach { id ->
                assertEquals(id, sessionRepository.getSession(id)!!.title)
            }
        }
    }

    // ── ChatSessionRepository.markSynced ──────────────────────────────────────

    @Nested
    inner class MarkSessionSynced {

        @Test
        fun `returns true and session is then excluded from getUnsyncedSessions`() {
            val session = createSession()
            assertTrue(sessionRepository.getUnsyncedSessions().any { it.id == session.id })

            val result = sessionRepository.markSynced(session.id)
            assertTrue(result)

            assertFalse(sessionRepository.getUnsyncedSessions().any { it.id == session.id })
        }

        @Test
        fun `returns false for a non-existent session id`() {
            assertFalse(sessionRepository.markSynced("does-not-exist"))
        }
    }

    // ── ChatSessionRepository.getUnsyncedSessions ─────────────────────────────

    @Nested
    inner class GetUnsyncedSessions {

        @Test
        fun `returns session that was never synced (syncedAt is null)`() {
            val session = createSession()
            val unsynced = sessionRepository.getUnsyncedSessions()
            assertTrue(unsynced.any { it.id == session.id })
        }

        @Test
        fun `does not return session after markSynced is called`() {
            val session = createSession()
            sessionRepository.markSynced(session.id)

            assertFalse(sessionRepository.getUnsyncedSessions().any { it.id == session.id })
        }

        @Test
        fun `respects limit parameter`() {
            repeat(5) { createSession(title = "Session $it") }
            val result = sessionRepository.getUnsyncedSessions(limit = 2)
            assertTrue(result.size <= 2)
        }
    }

    @Nested
    inner class InsertIfAbsent {

        @Test
        fun `inserts message when it does not exist`() {
            val session = createSession()
            val msg = messageFromServer(id = "msg-new", sessionId = session.id)

            messageRepository.bulkUpsert(listOf(msg))

            val messages = messageRepository.getMessages(session.id)
            assertTrue(messages.any { it.id == "msg-new" && it.content == "Hello" })
        }

        @Test
        fun `calling twice with identical data produces one row`() {
            val session = createSession()
            val msg = messageFromServer(id = "msg-dup", sessionId = session.id, content = "First")
            messageRepository.bulkUpsert(listOf(msg, msg))

            assertEquals(1, messageRepository.getMessages(session.id).count { it.id == "msg-dup" })
        }

        @Test
        fun `inserted message has syncedAt set (not returned in getUnsyncedMessages)`() {
            val session = createSession()
            val msg = messageFromServer(id = "msg-already-synced", sessionId = session.id)
            messageRepository.bulkUpsert(listOf(msg))

            // Server-originated rows are already synced - must not appear in the push queue
            val unsynced = messageRepository.getUnsyncedMessages(session.id)
            assertFalse(unsynced.any { it.id == "msg-already-synced" })
        }
    }

    @Nested
    inner class MarkMessageSynced {

        @Test
        fun `returns true and message is excluded from getUnsyncedMessages`() {
            val session = createSession()
            val msg = sessionRepository
                .run { session } // keep session alive
                .let {
                    messageRepository.addMessage(
                        ChatMessage(
                            id = "",
                            sessionId = session.id,
                            role = MessageRole.USER,
                            content = "Local message",
                        ),
                    )
                }

            assertTrue(messageRepository.getUnsyncedMessages(session.id).any { it.id == msg.id })

            val result = messageRepository.markSynced(msg.id)
            assertTrue(result)

            assertFalse(messageRepository.getUnsyncedMessages(session.id).any { it.id == msg.id })
        }

        @Test
        fun `returns false for a non-existent message id`() {
            assertFalse(messageRepository.markSynced("ghost-id"))
        }
    }

    @Nested
    inner class GetUnsyncedMessages {

        @Test
        fun `returns locally created messages that have never been pushed`() {
            val session = createSession()
            messageRepository.addMessage(
                ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Hi"),
            )

            val unsynced = messageRepository.getUnsyncedMessages(session.id)
            assertTrue(unsynced.isNotEmpty())
            assertTrue(unsynced.all { it.sessionId == session.id })
        }

        @Test
        fun `does not return outdated messages`() {
            val session = createSession()
            // addMessage leaves syncedAt null, then we mark it outdated
            val msg = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Outdated"),
            )
            messageRepository.markMessageAsOutdated(msg.id)

            val unsynced = messageRepository.getUnsyncedMessages(session.id)
            assertFalse(unsynced.any { it.id == msg.id })
        }

        @Test
        fun `respects limit parameter`() {
            val session = createSession()
            repeat(5) { i ->
                messageRepository.addMessage(
                    ChatMessage(id = "", sessionId = session.id, role = MessageRole.USER, content = "Msg $i"),
                )
            }
            val result = messageRepository.getUnsyncedMessages(session.id, limit = 2)
            assertTrue(result.size <= 2)
        }
    }
}
