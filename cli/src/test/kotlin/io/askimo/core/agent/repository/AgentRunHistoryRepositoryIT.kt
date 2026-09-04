/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class AgentRunHistoryRepositoryIT {

    private lateinit var testWorkspace: Workspace

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        testWorkspace = workspaceRepository.upsertByPath(
            tempDir.resolve("ws-${System.nanoTime()}").toFile().apply { mkdirs() },
        )
    }

    @AfterEach
    fun tearDown() {
        if (::testWorkspace.isInitialized) {
            historyRepository.deleteByWorkspaceId(testWorkspace.id)
            workspaceRepository.delete(testWorkspace.id)
        }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var historyRepository: AgentRunHistoryRepository
        private lateinit var workspaceRepository: WorkspaceRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)

            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            historyRepository = databaseManager.getAgentRunHistoryRepository()
            workspaceRepository = databaseManager.getWorkspaceRepository()
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

    private fun newRecord(
        conversationId: String = "conv-${System.nanoTime()}",
        title: String = "Do something",
        userInput: String = "Do something",
        response: String = "Done",
        error: String? = null,
        agentSessionId: String? = null,
        activityLog: List<String> = listOf("started", "finished"),
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        totalTokens: Int? = null,
        durationMs: Long? = null,
        createdAt: Instant = Instant.now(),
    ) = AgentRunRecord(
        workspaceId = testWorkspace.id,
        conversationId = conversationId,
        title = title,
        userInput = userInput,
        response = response,
        error = error,
        agentSessionId = agentSessionId,
        activityLog = activityLog,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        durationMs = durationMs,
        createdAt = createdAt,
    )

    @Test
    fun `should save and retrieve a run record`() {
        val record = newRecord()

        historyRepository.save(record)

        val all = historyRepository.findByWorkspaceId(testWorkspace.id)
        assertEquals(1, all.size)
        assertEquals(record.id, all[0].id)
        assertEquals(record.userInput, all[0].userInput)
        assertEquals(record.response, all[0].response)
    }

    @Test
    fun `should round-trip activity log entries in order`() {
        val record = newRecord(activityLog = listOf("step one", "step two", "step three"))

        historyRepository.save(record)

        val saved = historyRepository.findByWorkspaceId(testWorkspace.id).first { it.id == record.id }
        assertEquals(listOf("step one", "step two", "step three"), saved.activityLog)
    }

    @Test
    fun `should preserve embedded newlines within a single activity log entry`() {
        val record = newRecord(activityLog = listOf("line one\nline two", "another entry"))

        historyRepository.save(record)

        val saved = historyRepository.findByWorkspaceId(testWorkspace.id).first { it.id == record.id }
        assertEquals(listOf("line one\nline two", "another entry"), saved.activityLog)
    }

    @Test
    fun `should return empty activity log when none recorded`() {
        val record = newRecord(activityLog = emptyList())

        historyRepository.save(record)

        val saved = historyRepository.findByWorkspaceId(testWorkspace.id).first { it.id == record.id }
        assertTrue(saved.activityLog.isEmpty())
    }

    @Test
    fun `should persist error and nullable fields correctly`() {
        val record = newRecord(
            response = "",
            error = "Something went wrong",
            agentSessionId = "agent-session-1",
            inputTokens = 10,
            outputTokens = 20,
            totalTokens = 30,
            durationMs = 1234L,
        )

        historyRepository.save(record)

        val saved = historyRepository.findByWorkspaceId(testWorkspace.id).first { it.id == record.id }
        assertEquals("Something went wrong", saved.error)
        assertEquals("agent-session-1", saved.agentSessionId)
        assertEquals(10, saved.inputTokens)
        assertEquals(20, saved.outputTokens)
        assertEquals(30, saved.totalTokens)
        assertEquals(1234L, saved.durationMs)
    }

    @Test
    fun `should treat null optional fields as null after round-trip`() {
        val record = newRecord()

        historyRepository.save(record)

        val saved = historyRepository.findByWorkspaceId(testWorkspace.id).first { it.id == record.id }
        assertNull(saved.error)
        assertNull(saved.agentSessionId)
        assertNull(saved.inputTokens)
        assertNull(saved.outputTokens)
        assertNull(saved.totalTokens)
        assertNull(saved.durationMs)
    }

    @Test
    fun `should find all records across workspaces newest first`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        val first = newRecord(createdAt = baseTime)
        val second = newRecord(createdAt = baseTime.plusSeconds(10))
        historyRepository.save(first)
        historyRepository.save(second)

        val all = historyRepository.findAll(limit = 200)
        val ids = all.map { it.id }

        assertTrue(ids.indexOf(second.id) < ids.indexOf(first.id))
    }

    @Test
    fun `should find records by workspace id newest first`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        val older = newRecord(createdAt = baseTime)
        val newer = newRecord(createdAt = baseTime.plusSeconds(10))
        historyRepository.save(older)
        historyRepository.save(newer)

        val results = historyRepository.findByWorkspaceId(testWorkspace.id)

        assertEquals(listOf(newer.id, older.id), results.map { it.id })
    }

    @Test
    fun `should not return records from other workspaces`(@TempDir tempDir: Path) {
        val otherWorkspace = workspaceRepository.upsertByPath(
            tempDir.resolve("other-ws-${System.nanoTime()}").toFile().apply { mkdirs() },
        )
        try {
            val mine = newRecord()
            val other = AgentRunRecord(
                workspaceId = otherWorkspace.id,
                conversationId = "other-conv",
                title = "other input",
                userInput = "other input",
                response = "other response",
                error = null,
                activityLog = emptyList(),
            )
            historyRepository.save(mine)
            historyRepository.save(other)

            val results = historyRepository.findByWorkspaceId(testWorkspace.id)

            assertEquals(1, results.size)
            assertEquals(mine.id, results[0].id)
        } finally {
            historyRepository.deleteByWorkspaceId(otherWorkspace.id)
            workspaceRepository.delete(otherWorkspace.id)
        }
    }

    @Test
    fun `should find all turns for a conversation oldest first`() {
        val conversationId = "conv-thread-${System.nanoTime()}"
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        val turn1 = newRecord(conversationId = conversationId, userInput = "turn 1", createdAt = baseTime)
        val turn2 = newRecord(conversationId = conversationId, userInput = "turn 2", createdAt = baseTime.plusSeconds(5))
        val turn3 = newRecord(conversationId = conversationId, userInput = "turn 3", createdAt = baseTime.plusSeconds(10))
        // Save out of order to make sure ordering is driven by createdAt, not insertion order.
        historyRepository.save(turn2)
        historyRepository.save(turn3)
        historyRepository.save(turn1)

        val thread = historyRepository.findByConversationId(conversationId)

        assertEquals(listOf(turn1.id, turn2.id, turn3.id), thread.map { it.id })
    }

    @Test
    fun `should return empty list for unknown conversation id`() {
        val thread = historyRepository.findByConversationId("does-not-exist")

        assertTrue(thread.isEmpty())
    }

    @Test
    fun `should delete a single record by id`() {
        val record = newRecord()
        historyRepository.save(record)

        historyRepository.deleteById(record.id)

        assertTrue(historyRepository.findByWorkspaceId(testWorkspace.id).none { it.id == record.id })
    }

    @Test
    fun `should delete all turns for a conversation`() {
        val conversationId = "conv-delete-${System.nanoTime()}"
        val turn1 = newRecord(conversationId = conversationId)
        val turn2 = newRecord(conversationId = conversationId)
        historyRepository.save(turn1)
        historyRepository.save(turn2)

        historyRepository.deleteByConversationId(conversationId)

        assertTrue(historyRepository.findByConversationId(conversationId).isEmpty())
    }

    @Test
    fun `should not delete turns from other conversations when deleting by conversation id`() {
        val conversationId = "conv-target-${System.nanoTime()}"
        val otherConversationId = "conv-other-${System.nanoTime()}"
        val target = newRecord(conversationId = conversationId)
        val other = newRecord(conversationId = otherConversationId)
        historyRepository.save(target)
        historyRepository.save(other)

        historyRepository.deleteByConversationId(conversationId)

        assertTrue(historyRepository.findByConversationId(conversationId).isEmpty())
        assertEquals(1, historyRepository.findByConversationId(otherConversationId).size)
    }

    @Test
    fun `should delete all records for a workspace`() {
        val record1 = newRecord()
        val record2 = newRecord()
        historyRepository.save(record1)
        historyRepository.save(record2)

        historyRepository.deleteByWorkspaceId(testWorkspace.id)

        assertTrue(historyRepository.findByWorkspaceId(testWorkspace.id).isEmpty())
    }
}
