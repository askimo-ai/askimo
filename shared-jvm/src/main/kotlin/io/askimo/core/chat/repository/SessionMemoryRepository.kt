/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.SessionMemory
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Session_memory
import kotlin.time.Instant

/**
 * Maps a generated [Session_memory] row to the shared [SessionMemory] domain object.
 */
private fun Session_memory.toSessionMemory(): SessionMemory = SessionMemory(
    sessionId = session_id,
    memorySummary = memory_summary,
    memoryMessages = memory_messages,
    lastUpdated = last_updated,
    createdAt = created_at,
)

/**
 * Repository for managing session memory persistence.
 * Handles saving and loading of TokenAwareSummarizingMemory state for chat sessions.
 */
class SessionMemoryRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val queries get() = db.sessionMemoryQueries

    /**
     * Save or update session memory.
     * If memory for the session already exists, it will be updated (override).
     *
     * @param sessionMemory The session memory to save
     * @return The saved session memory
     */
    fun saveMemory(sessionMemory: SessionMemory): SessionMemory {
        db.transaction {
            val existing = queries.selectBySessionId(sessionMemory.sessionId).executeAsOneOrNull()

            if (existing != null) {
                queries.updateMemory(
                    memorySummary = sessionMemory.memorySummary,
                    memoryMessages = sessionMemory.memoryMessages,
                    lastUpdated = sessionMemory.lastUpdated,
                    sessionId = sessionMemory.sessionId,
                )
            } else {
                queries.insertMemory(
                    sessionId = sessionMemory.sessionId,
                    memorySummary = sessionMemory.memorySummary,
                    memoryMessages = sessionMemory.memoryMessages,
                    lastUpdated = sessionMemory.lastUpdated,
                    createdAt = sessionMemory.createdAt,
                )
            }
        }

        return sessionMemory
    }

    /**
     * Load session memory by session ID.
     *
     * @param sessionId The session ID to load memory for
     * @return The session memory, or null if not found
     */
    fun getBySessionId(sessionId: String): SessionMemory? = queries.selectBySessionId(sessionId).executeAsOneOrNull()?.toSessionMemory()

    /**
     * Delete session memory by session ID.
     *
     * @param sessionId The session ID to delete memory for
     * @return Number of records deleted (0 or 1)
     */
    fun deleteBySessionId(sessionId: String): Int = queries.deleteBySessionId(sessionId).value.toInt()

    /**
     * Delete all session memories older than the specified timestamp.
     * Useful for cleanup maintenance tasks.
     *
     * @param olderThan Timestamp threshold - memories last updated before this will be deleted
     * @return Number of records deleted
     */
    fun cleanupOldMemories(olderThan: Instant): Int = queries.deleteOlderThan(olderThan).value.toInt()
}
