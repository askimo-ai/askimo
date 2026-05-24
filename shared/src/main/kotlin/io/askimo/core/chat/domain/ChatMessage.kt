/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.context.MessageRole
import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant = Instant.now(),
    val isOutdated: Boolean = false,
    val editParentId: String? = null,
    val isEdited: Boolean = false,
    val attachments: List<FileAttachment> = emptyList(),
    val isFailed: Boolean = false,
)

/**
 * Exposed table definition for chat_messages.
 * Co-located with domain class for easier maintenance and foreign key references.
 */
object ChatMessagesTable : Table("chat_messages") {
    val id = varchar("id", 36)

    // Foreign key to chat_sessions with CASCADE delete
    val sessionId = varchar("session_id", 36)

    val role = varchar("role", 50)
    val content = text("content")
    val createdAt = sqliteInstant("created_at")
    val isOutdated = integer("is_outdated").default(0)

    // Column retained for data compatibility; no FK enforced —
    // edit-parent feature is not active.
    val editParentId = varchar("edit_parent_id", 36).nullable()

    val isEdited = integer("is_edited").default(0)
    val isFailed = integer("is_failed").default(0)

    val syncedAt = varchar("synced_at", 32).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // When a session is deleted, all its messages are automatically deleted.
        foreignKey(sessionId to ChatSessionsTable.id, onDelete = ReferenceOption.CASCADE)
    }
}
