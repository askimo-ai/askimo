/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Represents a file attachment associated with a chat message.
 * File content is stored separately on the filesystem, this model contains only metadata.
 *
 * @property id Unique identifier for the attachment
 * @property messageId ID of the message this attachment belongs to
 * @property sessionId ID of the session (for easier cleanup)
 * @property fileName Original name of the file
 * @property mimeType MIME type/file extension
 * @property size File size in bytes
 * @property createdAt Timestamp when the attachment was created
 * @property content File content (lazy-loaded, null when loaded from DB)
 */
data class FileAttachment(
    val id: String,
    val messageId: String,
    val sessionId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Instant = Instant.now(),
    val content: String? = null,
)

/**
 * Exposed table definition for chat_message_attachments.
 */
object ChatMessageAttachmentsTable : Table("chat_message_attachments") {
    val id = varchar("id", 36)
    val messageId = varchar("message_id", 36).references(ChatMessagesTable.id)
    val sessionId = varchar("session_id", 36).references(ChatSessionsTable.id)
    val fileName = varchar("file_name", 255)
    val mimeType = varchar("mime_type", 100)
    val size = long("size")
    val createdAt = sqliteInstant("created_at")

    override val primaryKey = PrimaryKey(id)
}
