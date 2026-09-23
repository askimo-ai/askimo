/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.db.DatabaseManager
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

@AskimoTestHome
class AgentRunHistoryRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: DatabaseManager
    private lateinit var repo: AgentRunHistoryRepository
    private lateinit var workspaceId: String

    @BeforeEach
    fun setup() {
        db = DatabaseManager.getInMemoryTestInstance(this)
        repo = db.getAgentRunHistoryRepository()

        // AgentRunHistoryTable.workspaceId has a FK to workspaces — a real workspace row
        // must exist before any run record referencing it can be inserted.
        workspaceId = db.getWorkspaceRepository()
            .upsertByPath(tempDir.resolve("workspace").toFile().apply { mkdirs() })
            .id
    }

    @AfterEach
    fun tearDown() {
        db.close()
        DatabaseManager.reset()
    }

    private fun record(conversationId: String = "conv-${Instant.now().toEpochMilli()}-${(0..9999).random()}"): AgentRunRecord = AgentRunRecord(
        workspaceId = workspaceId,
        conversationId = conversationId,
        title = "Test run",
        userInput = "do something",
        response = "done",
        error = null,
        activityLog = emptyList(),
    )

    @Nested
    inner class CountAll {

        @Test
        fun `returns 0 for an empty history table`() {
            assertEquals(0, repo.countAll())
        }

        @Test
        fun `returns the number of saved run records`() {
            repo.save(record())
            repo.save(record())
            repo.save(record())

            assertEquals(3, repo.countAll())
        }

        @Test
        fun `counts every turn of a multi-turn conversation individually`() {
            // countAll() is a row count, not a distinct-conversation count — multiple turns
            // sharing the same conversationId must each be counted separately.
            val conversationId = "conv-multi-turn"
            repo.save(record(conversationId))
            repo.save(record(conversationId))

            assertEquals(2, repo.countAll())
        }

        @Test
        fun `reflects deletions`() {
            val toDelete = record()
            repo.save(toDelete)
            repo.save(record())
            assertEquals(2, repo.countAll())

            repo.deleteById(toDelete.id)

            assertEquals(1, repo.countAll())
        }
    }
}
