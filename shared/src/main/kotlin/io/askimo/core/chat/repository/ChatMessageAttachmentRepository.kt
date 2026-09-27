/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.AttachmentReference
import io.askimo.core.chat.domain.AttachmentReferencesTable
import io.askimo.core.chat.domain.FileAttachment
import io.askimo.core.chat.domain.FileAttachmentsTable
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
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Extension function to map an Exposed ResultRow to a FileAttachment object.
 */
private fun ResultRow.toFileAttachment(): FileAttachment = FileAttachment(
    id = this[FileAttachmentsTable.id],
    fileName = this[FileAttachmentsTable.fileName],
    mimeType = this[FileAttachmentsTable.mimeType],
    size = this[FileAttachmentsTable.size],
    createdAt = this[FileAttachmentsTable.createdAt],
    storagePath = this[FileAttachmentsTable.storagePath],
    referenceCount = this[FileAttachmentsTable.referenceCount],
    content = null, // Content is not stored in DB
)

/**
 * Extension function to map an Exposed ResultRow to an AttachmentReference object.
 */
private fun ResultRow.toAttachmentReference(): AttachmentReference = AttachmentReference(
    attachmentId = this[AttachmentReferencesTable.attachmentId],
    messageId = this[AttachmentReferencesTable.messageId],
    sessionId = this[AttachmentReferencesTable.sessionId],
)

/**
 * Repository for managing chat message attachments with reference counting.
 * Implements shared storage model: one physical file can be referenced by multiple messages.
 * Files are only deleted when reference count reaches 0.
 */
class ChatMessageAttachmentRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {
    private val log = logger<ChatMessageAttachmentRepository>()

    /**
     * Save an attachment and create a reference from a message.
     * If attachment already exists, increments its reference count.
     * NOTE: Must be called within a transaction context.
     *
     * @param messageId The message ID
     * @param sessionId The session ID
     * @param attachment The attachment to save
     * @return The saved attachment
     */
    fun saveAttachment(messageId: String, sessionId: String, attachment: FileAttachment): FileAttachment {
        val attachmentWithId = if (attachment.id.isEmpty()) {
            attachment.copy(id = UUID.randomUUID().toString())
        } else {
            attachment
        }

        // Check if attachment already exists
        val existingAttachment = FileAttachmentsTable
            .selectAll()
            .where { FileAttachmentsTable.id eq attachmentWithId.id }
            .firstOrNull()

        if (existingAttachment != null) {
            // Attachment already exists, increment reference count
            FileAttachmentsTable.update({ FileAttachmentsTable.id eq attachmentWithId.id }) {
                it[referenceCount] = existingAttachment[FileAttachmentsTable.referenceCount] + 1
            }
            log.debug("Incremented reference count for attachment: ${attachmentWithId.id}")
        } else {
            // New attachment, insert it
            FileAttachmentsTable.insert {
                it[id] = attachmentWithId.id
                it[fileName] = attachmentWithId.fileName
                it[mimeType] = attachmentWithId.mimeType
                it[size] = attachmentWithId.size
                it[createdAt] = attachmentWithId.createdAt
                it[storagePath] = attachmentWithId.storagePath
                it[referenceCount] = 1
            }
            log.debug("Saved new attachment: ${attachmentWithId.id}")
        }

        // Create reference from message
        AttachmentReferencesTable.insert {
            it[attachmentId] = attachmentWithId.id
            it[this.messageId] = messageId
            it[this.sessionId] = sessionId
        }
        log.debug("Created attachment reference: attachmentId=${attachmentWithId.id}, messageId=$messageId")

        return attachmentWithId.copy(referenceCount = existingAttachment?.get(FileAttachmentsTable.referenceCount)?.plus(1) ?: 1)
    }

    /**
     * Add multiple attachments in a batch.
     * NOTE: Must be called within a transaction context.
     *
     * @param messageId The message ID
     * @param sessionId The session ID
     * @param attachments List of attachments to add
     * @return List of attachments with generated IDs
     */
    fun addAttachments(messageId: String, sessionId: String, attachments: List<FileAttachment>): List<FileAttachment> = attachments.map { attachment -> saveAttachment(messageId, sessionId, attachment) }

    /**
     * Get all references for an attachment (which messages use it).
     *
     * @param attachmentId The attachment ID
     * @return List of references
     */
    fun getReferencesForAttachment(attachmentId: String): List<AttachmentReference> = transaction(database) {
        AttachmentReferencesTable
            .selectAll()
            .where { AttachmentReferencesTable.attachmentId eq attachmentId }
            .map { it.toAttachmentReference() }
    }

    /**
     * Delete attachments for a specific message (internal, assumes transaction context).
     * Decrements reference count for each attachment.
     * Deletes physical file only if reference count reaches 0.
     * NOTE: Must be called within a transaction context.
     *
     * @param messageId The message ID
     * @return Number of references deleted
     */
    internal fun deleteAttachmentsByMessageIdInternal(messageId: String): Int {
        // Get attachment references for this message
        val references = AttachmentReferencesTable
            .selectAll()
            .where { AttachmentReferencesTable.messageId eq messageId }
            .map { it.toAttachmentReference() }

        // Delete references from database
        val deletedCount = AttachmentReferencesTable.deleteWhere {
            AttachmentReferencesTable.messageId eq messageId
        }

        // Decrement reference count for each attachment
        references.forEach { reference ->
            val attachment = FileAttachmentsTable
                .selectAll()
                .where { FileAttachmentsTable.id eq reference.attachmentId }
                .firstOrNull()?.toFileAttachment()

            if (attachment != null) {
                val newRefCount = attachment.referenceCount - 1
                if (newRefCount <= 0) {
                    // Delete attachment and file if no more references
                    FileAttachmentsTable.deleteWhere {
                        FileAttachmentsTable.id eq reference.attachmentId
                    }
                    AttachmentStorageManager.deleteAttachmentFile(reference.attachmentId)
                    log.debug("Deleted attachment (ref count 0): ${reference.attachmentId}")
                } else {
                    // Just decrement the counter
                    FileAttachmentsTable.update({ FileAttachmentsTable.id eq reference.attachmentId }) {
                        it[referenceCount] = newRefCount
                    }
                    log.debug("Decremented reference count for attachment ${reference.attachmentId}: $newRefCount -> $newRefCount")
                }
            }
        }

        return deletedCount
    }
}
