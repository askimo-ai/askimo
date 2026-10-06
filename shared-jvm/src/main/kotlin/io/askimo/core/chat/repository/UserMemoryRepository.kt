/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.UserMemory
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.User_memory
import kotlin.time.Clock

/**
 * Maps a generated [User_memory] row to the shared [UserMemory] domain object.
 */
private fun User_memory.toUserMemory(): UserMemory = UserMemory(
    id = id,
    memoryJson = memory_json,
    lastUpdated = last_updated,
    createdAt = created_at,
)

/**
 * Repository for managing the single-row user memory record.
 * There is always at most one row keyed by [UserMemory.DEFAULT_ID].
 */
class UserMemoryRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val queries get() = db.userMemoryQueries

    /**
     * Load the user memory record, or null if it has never been written.
     */
    fun get(): UserMemory? = queries.selectById(UserMemory.DEFAULT_ID).executeAsOneOrNull()?.toUserMemory()

    /**
     * Upsert user memory. Creates the row on first call, updates on subsequent calls.
     */
    fun save(memoryJson: String): UserMemory {
        val now = Clock.System.now()

        return db.transactionWithResult {
            val existing = queries.selectById(UserMemory.DEFAULT_ID).executeAsOneOrNull()

            if (existing != null) {
                queries.updateMemory(
                    memoryJson = memoryJson,
                    lastUpdated = now,
                    id = UserMemory.DEFAULT_ID,
                )
                UserMemory(memoryJson = memoryJson, lastUpdated = now, createdAt = existing.created_at)
            } else {
                queries.insertMemory(
                    id = UserMemory.DEFAULT_ID,
                    memoryJson = memoryJson,
                    lastUpdated = now,
                    createdAt = now,
                )
                UserMemory(memoryJson = memoryJson, lastUpdated = now, createdAt = now)
            }
        }
    }

    /**
     * Delete the user memory record (reset).
     */
    fun clear(): Int = queries.deleteById(UserMemory.DEFAULT_ID).value.toInt()
}
