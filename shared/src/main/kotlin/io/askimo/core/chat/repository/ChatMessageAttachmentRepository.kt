/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatMessageAttachmentsTable
import io.askimo.core.chat.domain.FileAttachment
import io.askimo.core.chat.util.AttachmentStorageManager
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Extension function to map an Exposed ResultRow to a FileAttachment object.
 */
private fun ResultRow.toFileAttachment(): FileAttachment = FileAttachment(
    id = this[ChatMessageAttachmentsTable.id],
    messageId = this[ChatMessageAttachmentsTable.messageId],
    sessionId = this[ChatMessageAttachmentsTable.sessionId],
    fileName = this[ChatMessageAttachmentsTable.fileName],
    mimeType = this[ChatMessageAttachmentsTable.mimeType],
    size = this[ChatMessageAttachmentsTable.size],
    createdAt = this[ChatMessageAttachmentsTable.createdAt],
    storagePath = this[ChatMessageAttachmentsTable.storagePath],
    content = null, // Content is not stored in DB
)

/**
 * Repository for managing chat message attachments in SQLite database.
 * Stores attachment metadata only; file content is stored on filesystem.
 */
class ChatMessageAttachmentRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {
    private val log = logger<ChatMessageAttachmentRepository>()

    /**
     * Add multiple attachments in a batch.
     * NOTE: Must be called within a transaction context.
     *
     * @param attachments List of attachments to add
     * @return List of attachments with generated IDs
     */
    fun addAttachments(attachments: List<FileAttachment>): List<FileAttachment> = attachments.map { attachment ->
        val attachmentWithId = if (attachment.id.isEmpty()) {
            attachment.copy(id = UUID.randomUUID().toString())
        } else {
            attachment
        }

        ChatMessageAttachmentsTable.insert {
            it[id] = attachmentWithId.id
            it[messageId] = attachmentWithId.messageId
            it[sessionId] = attachmentWithId.sessionId
            it[fileName] = attachmentWithId.fileName
            it[mimeType] = attachmentWithId.mimeType
            it[size] = attachmentWithId.size
            it[createdAt] = attachmentWithId.createdAt
            it[storagePath] = attachmentWithId.storagePath
        }

        attachmentWithId
    }

    /**
     * Get all attachments for a session.
     *
     * @param sessionId The session ID
     * @return List of attachments
     */
    fun getAttachmentsBySessionId(sessionId: String): List<FileAttachment> = transaction(database) {
        ChatMessageAttachmentsTable
            .selectAll()
            .where { ChatMessageAttachmentsTable.sessionId eq sessionId }
            .map { it.toFileAttachment() }
    }

    /**
     * Delete attachments for a specific message.
     * Also deletes the associated physical files from storage.
     *
     * @param messageId The message ID
     * @return Number of attachments deleted
     */
    fun deleteAttachmentsByMessageId(messageId: String): Int = transaction(database) {
        // Get attachments before deleting them from DB
        val attachments = ChatMessageAttachmentsTable
            .selectAll()
            .where { ChatMessageAttachmentsTable.messageId eq messageId }
            .map { it.toFileAttachment() }

        // Delete from database
        val deletedCount = ChatMessageAttachmentsTable.deleteWhere {
            ChatMessageAttachmentsTable.messageId eq messageId
        }

        // Delete physical files from storage
        attachments.forEach { attachment ->
            if (attachment.storagePath != null) {
                AttachmentStorageManager.deleteAttachmentFile(attachment.sessionId, attachment.id)
                log.debug("Deleted attachment file: sessionId=${attachment.sessionId}, attachmentId=${attachment.id}")
            }
        }

        deletedCount
    }

    /**
     * Get attachments for a specific message.
     *
     * @param messageId The message ID
     * @return List of attachments
     */
    fun getAttachmentsByMessageId(messageId: String): List<FileAttachment> = transaction(database) {
        ChatMessageAttachmentsTable
            .selectAll()
            .where { ChatMessageAttachmentsTable.messageId eq messageId }
            .map { it.toFileAttachment() }
    }

    /**
     * Get total attachment size for a session (in bytes).
     * This queries the database metadata only.
     *
     * @param sessionId The session ID
     * @return Total size in bytes
     */
    fun getSessionAttachmentMetadataSize(sessionId: String): Long = transaction(database) {
        ChatMessageAttachmentsTable
            .selectAll()
            .where { ChatMessageAttachmentsTable.sessionId eq sessionId }
            .sumOf { it[ChatMessageAttachmentsTable.size] }
    }

    /**
     * Get attachment count for a session.
     *
     * @param sessionId The session ID
     * @return Number of attachments
     */
    fun getSessionAttachmentCount(sessionId: String): Int = transaction(database) {
        ChatMessageAttachmentsTable
            .selectAll()
            .where { ChatMessageAttachmentsTable.sessionId eq sessionId }
            .count()
            .toInt()
    }
}
