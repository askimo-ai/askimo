/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.SessionMemory
import io.askimo.core.chat.domain.SessionMemoryTable
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Extension function to map an Exposed ResultRow to a SessionMemory object.
 */
private fun ResultRow.toSessionMemory(): SessionMemory = SessionMemory(
    sessionId = this[SessionMemoryTable.sessionId],
    memorySummary = this[SessionMemoryTable.memorySummary],
    memoryMessages = this[SessionMemoryTable.memoryMessages],
    lastUpdated = this[SessionMemoryTable.lastUpdated],
    createdAt = this[SessionMemoryTable.createdAt],
)

/**
 * Repository for managing session memory persistence.
 * Handles saving and loading of TokenAwareSummarizingMemory state for chat sessions.
 */
class SessionMemoryRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    /**
     * Save or update session memory.
     * If memory for the session already exists, it will be updated (override).
     *
     * @param sessionMemory The session memory to save
     * @return The saved session memory
     */
    fun saveMemory(sessionMemory: SessionMemory): SessionMemory = transaction(database) {
        val existing = SessionMemoryTable
            .selectAll()
            .where { SessionMemoryTable.sessionId eq sessionMemory.sessionId }
            .singleOrNull()

        if (existing != null) {
            // Update existing memory (override)
            SessionMemoryTable.update({ SessionMemoryTable.sessionId eq sessionMemory.sessionId }) {
                it[memorySummary] = sessionMemory.memorySummary
                it[memoryMessages] = sessionMemory.memoryMessages
                it[lastUpdated] = sessionMemory.lastUpdated
            }
        } else {
            // Insert new memory
            SessionMemoryTable.insert {
                it[sessionId] = sessionMemory.sessionId
                it[memorySummary] = sessionMemory.memorySummary
                it[memoryMessages] = sessionMemory.memoryMessages
                it[lastUpdated] = sessionMemory.lastUpdated
                it[createdAt] = sessionMemory.createdAt
            }
        }

        sessionMemory
    }

    /**
     * Load session memory by session ID.
     *
     * @param sessionId The session ID to load memory for
     * @return The session memory, or null if not found
     */
    fun getBySessionId(sessionId: String): SessionMemory? = transaction(database) {
        SessionMemoryTable
            .selectAll()
            .where { SessionMemoryTable.sessionId eq sessionId }
            .singleOrNull()
            ?.toSessionMemory()
    }

    /**
     * Delete session memory by session ID.
     *
     * @param sessionId The session ID to delete memory for
     * @return Number of records deleted (0 or 1)
     */
    fun deleteBySessionId(sessionId: String): Int = transaction(database) {
        SessionMemoryTable.deleteWhere { SessionMemoryTable.sessionId eq sessionId }
    }

    /**
     * Delete all session memories older than the specified timestamp.
     * Useful for cleanup maintenance tasks.
     *
     * @param olderThan Timestamp threshold - memories last updated before this will be deleted
     * @return Number of records deleted
     */
    fun cleanupOldMemories(olderThan: Instant): Int = transaction(database) {
        SessionMemoryTable.deleteWhere { SessionMemoryTable.lastUpdated less olderThan }
    }
}
