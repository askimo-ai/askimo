/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Domain model for persistent user memory.
 *
 * Stores a compact JSON-serialized [io.askimo.core.memory.UserMemorySummary] that accumulates
 * stable facts about the user across all chat sessions. Unlike [SessionMemory], which is
 * scoped to a single session, this record is global for the local user — there is only
 * ever one row (id = "default").
 *
 * @property id Always "default" — single-row per installation.
 * @property memoryJson JSON-serialised [io.askimo.core.memory.UserMemorySummary].
 * @property lastUpdated Timestamp of the last merge.
 * @property createdAt Timestamp of initial row creation.
 */
data class UserMemory(
    val id: String = DEFAULT_ID,
    val memoryJson: String,
    val lastUpdated: Instant = Instant.now(),
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}

/**
 * Exposed table definition for user_memory.
 * Single-row table — one record per local installation.
 */
object UserMemoryTable : Table("user_memory") {
    val id = varchar("id", 36).default(UserMemory.DEFAULT_ID)
    val memoryJson = text("memory_json")
    val lastUpdated = sqliteInstant("last_updated")
    val createdAt = sqliteInstant("created_at")

    override val primaryKey = PrimaryKey(id)
}
