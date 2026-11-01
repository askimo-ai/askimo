/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Label
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Integration coverage for [LabelRepository]: CRUD, the many-to-many session assignment helpers,
 * cascade-delete behavior, and the sync push/pull helpers — mirroring the style of
 * [ChatSessionRepositoryIT].
 */
class LabelRepositoryIT {

    @AfterEach
    fun tearDown() {
        sessionRepository.deleteAll()
        labelRepository.list().forEach { labelRepository.delete(it.id) }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var labelRepository: LabelRepository
        private lateinit var sessionRepository: ChatSessionRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            labelRepository = databaseManager.getLabelRepository()
            sessionRepository = databaseManager.getChatSessionRepository()
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

    private fun newSession(id: String = "session-${Clock.System.now().toEpochMilliseconds()}-${(0..9999).random()}"): ChatSession = sessionRepository.createSession(ChatSession(id = id, title = "Test session $id"))

    private fun newLabel(name: String, color: String = "#FF0000"): Label = labelRepository.create(Label(name = name, color = color))

    @Test
    fun `create persists a label retrievable by id`() {
        val created = newLabel("Billing")
        val fetched = labelRepository.get(created.id)
        assertEquals(created.id, fetched?.id)
        assertEquals("Billing", fetched?.name)
        assertEquals("#FF0000", fetched?.color)
    }

    @Test
    fun `create rejects a blank name`() {
        assertTrue(
            runCatching { labelRepository.create(Label(name = "   ", color = "#FFFFFF")) }.isFailure,
        )
    }

    @Test
    fun `list returns labels ordered by name`() {
        newLabel("Zebra")
        newLabel("Apple")
        newLabel("Mango")
        val names = labelRepository.list().map { it.name }
        assertEquals(listOf("Apple", "Mango", "Zebra"), names)
    }

    @Test
    fun `update renames and recolors an existing label`() {
        val created = newLabel("Original")
        val updated = labelRepository.update(created.copy(name = "Renamed", color = "#00FF00"))
        assertTrue(updated)

        val fetched = labelRepository.get(created.id)
        assertEquals("Renamed", fetched?.name)
        assertEquals("#00FF00", fetched?.color)
    }

    @Test
    fun `update returns false for a non-existent label`() {
        val updated = labelRepository.update(Label(id = "does-not-exist", name = "X", color = "#000000"))
        assertFalse(updated)
    }

    @Test
    fun `delete removes a label`() {
        val created = newLabel("ToDelete")
        assertTrue(labelRepository.delete(created.id))
        assertNull(labelRepository.get(created.id))
    }

    @Test
    fun `assign and labelsForSession round-trip`() {
        val session = newSession()
        val billing = newLabel("Billing")
        val urgent = newLabel("Urgent")

        labelRepository.assign(session.id, billing.id)
        labelRepository.assign(session.id, urgent.id)

        val assigned = labelRepository.labelsForSession(session.id).map { it.name }
        assertEquals(listOf("Billing", "Urgent"), assigned)
    }

    @Test
    fun `assign is idempotent when the label is already assigned`() {
        val session = newSession()
        val billing = newLabel("Billing")

        labelRepository.assign(session.id, billing.id)
        labelRepository.assign(session.id, billing.id)

        assertEquals(1, labelRepository.labelsForSession(session.id).size)
    }

    @Test
    fun `unassign removes only the targeted label`() {
        val session = newSession()
        val billing = newLabel("Billing")
        val urgent = newLabel("Urgent")
        labelRepository.assign(session.id, billing.id)
        labelRepository.assign(session.id, urgent.id)

        labelRepository.unassign(session.id, billing.id)

        assertEquals(listOf("Urgent"), labelRepository.labelsForSession(session.id).map { it.name })
    }

    @Test
    fun `setLabelsForSession replaces the full assignment set`() {
        val session = newSession()
        val billing = newLabel("Billing")
        val urgent = newLabel("Urgent")
        val archived = newLabel("Archived")
        labelRepository.assign(session.id, billing.id)

        labelRepository.setLabelsForSession(session.id, listOf(urgent.id, archived.id))

        val assigned = labelRepository.labelsForSession(session.id).map { it.name }.toSet()
        assertEquals(setOf("Urgent", "Archived"), assigned)
    }

    @Test
    fun `labelsForSessions bulk-loads labels for multiple sessions, omitting unlabeled sessions`() {
        val sessionA = newSession()
        val sessionB = newSession()
        val sessionC = newSession()
        val billing = newLabel("Billing")
        labelRepository.assign(sessionA.id, billing.id)
        labelRepository.assign(sessionB.id, billing.id)
        // sessionC has no labels

        val bySession = labelRepository.labelsForSessions(listOf(sessionA.id, sessionB.id, sessionC.id))

        assertEquals(setOf(sessionA.id, sessionB.id), bySession.keys)
        assertEquals(listOf("Billing"), bySession[sessionA.id]?.map { it.name })
    }

    @Test
    fun `sessionIdsForLabel returns every session carrying that label`() {
        val sessionA = newSession()
        val sessionB = newSession()
        val billing = newLabel("Billing")
        labelRepository.assign(sessionA.id, billing.id)
        labelRepository.assign(sessionB.id, billing.id)

        val ids = labelRepository.sessionIdsForLabel(billing.id).toSet()
        assertEquals(setOf(sessionA.id, sessionB.id), ids)
    }

    @Test
    fun `deleting a label cascades its removal from every session's assignment`() {
        val session = newSession()
        val billing = newLabel("Billing")
        labelRepository.assign(session.id, billing.id)

        labelRepository.delete(billing.id)

        assertTrue(labelRepository.labelsForSession(session.id).isEmpty())
    }

    @Test
    fun `getUnsyncedLabels returns labels never synced or updated after their last sync`() {
        val created = newLabel("NeedsSync")
        val unsynced = labelRepository.getUnsyncedLabels().map { it.id }
        assertTrue(created.id in unsynced)

        labelRepository.markSynced(created.id)
        assertFalse(created.id in labelRepository.getUnsyncedLabels().map { it.id })
    }

    @Test
    fun `upsertFromServer inserts new labels and updates only strictly newer ones`() {
        val existing = newLabel("Existing")
        labelRepository.markSynced(existing.id)

        val serverNewLabel = Label(id = "server-new", name = "FromServer", color = "#123456")
        val staleUpdate = existing.copy(name = "StaleName", updatedAt = existing.updatedAt.minus(kotlin.time.Duration.parse("PT1S")))
        val freshUpdate = existing.copy(name = "FreshName", updatedAt = Instant.fromEpochMilliseconds(existing.updatedAt.toEpochMilliseconds() + 10_000))

        labelRepository.upsertFromServer(listOf(serverNewLabel, staleUpdate))
        assertEquals("FromServer", labelRepository.get("server-new")?.name)
        assertEquals("Existing", labelRepository.get(existing.id)?.name) // stale update ignored

        labelRepository.upsertFromServer(listOf(freshUpdate))
        assertEquals("FreshName", labelRepository.get(existing.id)?.name)
    }

    @Test
    fun `hardDelete removes the label locally`() {
        val created = newLabel("ToHardDelete")
        assertTrue(labelRepository.hardDelete(created.id))
        assertNull(labelRepository.get(created.id))
    }
}
