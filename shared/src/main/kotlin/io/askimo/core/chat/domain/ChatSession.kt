/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val projectId: String? = null, // null = no project (general chat)
    val directiveId: String? = null,
    val isStarred: Boolean = false,
)

const val SESSION_TITLE_MAX_LENGTH = 256

/**
 * Exposed table definition for chat_sessions.
 * Co-located with domain class for easier maintenance and foreign key references.
 */
object ChatSessionsTable : Table("chat_sessions") {
    val id = varchar("id", 36)
    val title = varchar("title", SESSION_TITLE_MAX_LENGTH)
    val createdAt = sqliteInstant("created_at")
    val updatedAt = sqliteInstant("updated_at")

    val projectId = varchar("project_id", 36).nullable()

    val directiveId = varchar("directive_id", 36).nullable()
    val isStarred = integer("is_starred").default(0)

    val syncedAt = varchar("synced_at", 32).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(projectId to ProjectsTable.id, onDelete = ReferenceOption.CASCADE)
    }
}

/**
 * Many-to-many association between chat sessions and their active directives.
 *
 * [ChatSessionsTable.directiveId] remains as a compatibility projection for older
 * databases and sync payloads, while this table is the source of truth for the
 * complete active directive set.
 */
object ChatSessionDirectivesTable : Table("chat_session_directives") {
    val sessionId = varchar("session_id", 36)
    val directiveId = varchar("directive_id", 36)

    override val primaryKey = PrimaryKey(sessionId, directiveId)

    init {
        foreignKey(sessionId to ChatSessionsTable.id, onDelete = ReferenceOption.CASCADE)
        foreignKey(directiveId to ChatDirectivesTable.id, onDelete = ReferenceOption.CASCADE)
    }
}
