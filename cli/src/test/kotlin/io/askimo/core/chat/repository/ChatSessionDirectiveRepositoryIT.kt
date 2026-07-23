/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class ChatSessionDirectiveRepositoryIT {

    @AfterEach
    fun tearDown() {
        sessionRepository.deleteAll()
        directiveRepository.list().forEach { directiveRepository.delete(it.id) }
    }

    @Test
    fun `stores multiple active directives for one session`() {
        val first = directiveRepository.save(ChatDirective(name = "Alpha", content = "First"))
        val second = directiveRepository.save(ChatDirective(name = "Beta", content = "Second"))
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Multiple directives"))

        assertTrue(sessionRepository.replaceSessionDirectives(session.id, setOf(first.id, second.id)))

        val expectedIds = listOf(first.id, second.id).sorted()
        assertEquals(expectedIds, sessionRepository.getActiveDirectiveIds(session.id).toList())
        assertEquals(expectedIds.first(), sessionRepository.getSession(session.id)?.directiveId)
    }

    @Test
    fun `toggling one directive preserves the other active directives`() {
        val first = directiveRepository.save(ChatDirective(name = "Alpha", content = "First"))
        val second = directiveRepository.save(ChatDirective(name = "Beta", content = "Second"))
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Toggle directives"))
        sessionRepository.replaceSessionDirectives(session.id, setOf(first.id, second.id))

        assertTrue(sessionRepository.setSessionDirectiveActive(session.id, first.id, false))

        assertEquals(setOf(second.id), sessionRepository.getActiveDirectiveIds(session.id))
        assertEquals(second.id, sessionRepository.getSession(session.id)?.directiveId)
    }

    @Test
    fun `active directive state is isolated by session`() {
        val first = directiveRepository.save(ChatDirective(name = "Alpha", content = "First"))
        val second = directiveRepository.save(ChatDirective(name = "Beta", content = "Second"))
        val firstSession = sessionRepository.createSession(ChatSession(id = "", title = "First session"))
        val secondSession = sessionRepository.createSession(ChatSession(id = "", title = "Second session"))

        sessionRepository.setSessionDirectiveActive(firstSession.id, first.id, true)
        sessionRepository.setSessionDirectiveActive(secondSession.id, second.id, true)

        assertEquals(setOf(first.id), sessionRepository.getActiveDirectiveIds(firstSession.id))
        assertEquals(setOf(second.id), sessionRepository.getActiveDirectiveIds(secondSession.id))
    }

    @Test
    fun `deleting a directive refreshes every affected session`() {
        val removed = directiveRepository.save(ChatDirective(name = "Temporary", content = "Temporary"))
        val retained = directiveRepository.save(ChatDirective(name = "Retained", content = "Retained"))
        val firstSession = sessionRepository.createSession(ChatSession(id = "", title = "First session"))
        val secondSession = sessionRepository.createSession(ChatSession(id = "", title = "Second session"))
        sessionRepository.replaceSessionDirectives(firstSession.id, setOf(removed.id, retained.id))
        sessionRepository.setSessionDirectiveActive(secondSession.id, removed.id, true)

        assertTrue(directiveRepository.delete(removed.id))

        assertEquals(setOf(retained.id), sessionRepository.getActiveDirectiveIds(firstSession.id))
        assertEquals(retained.id, sessionRepository.getSession(firstSession.id)?.directiveId)
        assertTrue(sessionRepository.getActiveDirectiveIds(secondSession.id).isEmpty())
        assertEquals(null, sessionRepository.getSession(secondSession.id)?.directiveId)
    }

    @Test
    fun `newer legacy server selection replaces the local active set`() {
        val first = directiveRepository.save(ChatDirective(name = "First", content = "First"))
        val second = directiveRepository.save(ChatDirective(name = "Second", content = "Second"))
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Synced session"))
        sessionRepository.replaceSessionDirectives(session.id, setOf(first.id, second.id))

        sessionRepository.upsertFromServer(
            listOf(
                session.copy(
                    directiveId = second.id,
                    updatedAt = Instant.now().plusSeconds(60),
                ),
            ),
        )

        assertEquals(setOf(second.id), sessionRepository.getActiveDirectiveIds(session.id))
    }

    @Test
    fun `activating another directive preserves a delayed legacy selection`() {
        val legacyDirectiveId = "legacy-directive"
        val session = ChatSession(
            id = "legacy-session",
            title = "Delayed directive",
            directiveId = legacyDirectiveId,
        )
        sessionRepository.upsertFromServer(listOf(session))
        val additional = directiveRepository.save(ChatDirective(name = "Additional", content = "Additional"))

        sessionRepository.setSessionDirectiveActive(session.id, additional.id, true)

        assertEquals(setOf(additional.id), sessionRepository.getActiveDirectiveIds(session.id))
        assertEquals(legacyDirectiveId, sessionRepository.getSession(session.id)?.directiveId)

        directiveRepository.upsertFromServer(
            listOf(ChatDirective(id = legacyDirectiveId, name = "Legacy", content = "Legacy")),
        )

        val expectedDirectiveIds = listOf(legacyDirectiveId, additional.id).sorted()
        assertEquals(
            expectedDirectiveIds,
            sessionRepository.getActiveDirectiveIds(session.id).toList(),
        )
        assertEquals(expectedDirectiveIds.first(), sessionRepository.getSession(session.id)?.directiveId)
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var directiveRepository: ChatDirectiveRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            sessionRepository = databaseManager.getChatSessionRepository()
            directiveRepository = databaseManager.getChatDirectiveRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }
}
