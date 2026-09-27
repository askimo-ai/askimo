/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Represents a shared file attachment.
 * File content is stored separately on the filesystem under {attachmentId}/, this model contains only metadata.
 * Multiple messages can reference the same attachment via AttachmentReference table.
 *
 * @property id Unique identifier for the attachment
 * @property fileName Original name of the file
 * @property mimeType MIME type/file extension
 * @property size File size in bytes
 * @property createdAt Timestamp when the attachment was created
 * @property storagePath Path to the persistent file storage (null if not yet persisted)
 * @property content File content (lazy-loaded, null when loaded from DB)
 */
data class FileAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Instant = Instant.now(),
    val storagePath: String? = null,
    val content: String? = null,
)

/**
 * Represents a reference from a message to an attachment.
 * Allows N:M relationships: one attachment can be used by multiple messages,
 * and one message can have multiple attachments.
 *
 * @property attachmentId ID of the shared attachment
 * @property messageId ID of the message using this attachment
 * @property sessionId ID of the session (for easier querying and debugging)
 */
data class AttachmentReference(
    val attachmentId: String,
    val messageId: String,
    val sessionId: String,
)

/**
 * Exposed table definition for file_attachments.
 * Stores attachment metadata without message/session context (shared storage model).
 */
object FileAttachmentsTable : Table("file_attachments") {
    val id = varchar("id", 36)
    val fileName = varchar("file_name", 255)
    val mimeType = varchar("mime_type", 100)
    val size = long("size")
    val createdAt = sqliteInstant("created_at")
    val storagePath = varchar("storage_path", 1024).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed table definition for attachment_references.
 * Join table linking messages to attachments (N:M relationship).
 */
object AttachmentReferencesTable : Table("attachment_references") {
    val attachmentId = varchar("attachment_id", 36).references(FileAttachmentsTable.id)
    val messageId = varchar("message_id", 36).references(ChatMessagesTable.id)
    val sessionId = varchar("session_id", 36).references(ChatSessionsTable.id)

    override val primaryKey = PrimaryKey(attachmentId, messageId)
}

/**
 * @deprecated Use FileAttachmentsTable and AttachmentReferencesTable instead.
 * Old table definition kept for migration purposes.
 */
object ChatMessageAttachmentsTable : Table("chat_message_attachments") {
    val id = varchar("id", 36)
    val messageId = varchar("message_id", 36).references(ChatMessagesTable.id)
    val sessionId = varchar("session_id", 36).references(ChatSessionsTable.id)
    val fileName = varchar("file_name", 255)
    val mimeType = varchar("mime_type", 100)
    val size = long("size")
    val createdAt = sqliteInstant("created_at")
    val storagePath = varchar("storage_path", 1024).nullable()

    override val primaryKey = PrimaryKey(id)
}
