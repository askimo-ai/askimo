/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.UserMemory
import io.askimo.core.chat.domain.UserMemoryTable
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

private fun ResultRow.toUserMemory(): UserMemory = UserMemory(
    id = this[UserMemoryTable.id],
    memoryJson = this[UserMemoryTable.memoryJson],
    lastUpdated = this[UserMemoryTable.lastUpdated],
    createdAt = this[UserMemoryTable.createdAt],
)

/**
 * Repository for managing the single-row user memory record.
 * There is always at most one row keyed by [UserMemory.DEFAULT_ID].
 */
class UserMemoryRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    /**
     * Load the user memory record, or null if it has never been written.
     */
    fun get(): UserMemory? = transaction(database) {
        UserMemoryTable.selectAll()
            .where { UserMemoryTable.id eq UserMemory.DEFAULT_ID }
            .singleOrNull()
            ?.toUserMemory()
    }

    /**
     * Upsert user memory. Creates the row on first call, updates on subsequent calls.
     */
    fun save(memoryJson: String): UserMemory {
        val now = Instant.now()
        return transaction(database) {
            val existing = UserMemoryTable.selectAll()
                .where { UserMemoryTable.id eq UserMemory.DEFAULT_ID }
                .singleOrNull()

            if (existing != null) {
                UserMemoryTable.update({ UserMemoryTable.id eq UserMemory.DEFAULT_ID }) {
                    it[UserMemoryTable.memoryJson] = memoryJson
                    it[UserMemoryTable.lastUpdated] = now
                }
                UserMemory(memoryJson = memoryJson, lastUpdated = now, createdAt = existing[UserMemoryTable.createdAt])
            } else {
                UserMemoryTable.insert {
                    it[id] = UserMemory.DEFAULT_ID
                    it[UserMemoryTable.memoryJson] = memoryJson
                    it[lastUpdated] = now
                    it[createdAt] = now
                }
                UserMemory(memoryJson = memoryJson, lastUpdated = now, createdAt = now)
            }
        }
    }

    /**
     * Delete the user memory record (reset).
     */
    fun clear(): Int = transaction(database) {
        UserMemoryTable.deleteWhere { id eq UserMemory.DEFAULT_ID }
    }
}
