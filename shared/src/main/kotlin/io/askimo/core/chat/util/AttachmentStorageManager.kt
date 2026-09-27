/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.config.AppConfig
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Manages persistent storage of chat message attachments.
 * Files are stored under ${user.home}/.askimo/attachments/{attachmentId}/{filename}
 * Multiple messages can share the same attachment file via reference counting.
 * This allows users to download or rerun requests with original attachments.
 */
object AttachmentStorageManager {
    private val log = logger<AttachmentStorageManager>()

    private val maxFileSizeBytes: Long = AppConfig.indexing.maxFileBytes

    /**
     * Directory for a specific attachment (shared across all messages/sessions that reference it).
     * @param attachmentId The attachment ID
     * @return The attachment directory
     */
    private fun getAttachmentDir(attachmentId: String): File = File(AskimoHome.attachmentsDir().toFile(), attachmentId)

    /**
     * Save an attachment file to persistent storage.
     * Copies the file from the source location to the permanent storage directory.
     * If the attachment file already exists, it's not re-saved (assumed to be the same).
     *
     * @param attachmentId The attachment ID
     * @param sourceFile The source file to copy
     * @return The path to the stored file, or null if storage failed
     * @throws FileSizeExceededException if the file exceeds the maximum allowed size
     */
    fun saveAttachmentFile(attachmentId: String, sourceFile: File): String? {
        try {
            // Validate file size
            val fileSize = sourceFile.length()
            if (fileSize > maxFileSizeBytes) {
                throw FileSizeExceededException(fileSize, maxFileSizeBytes)
            }

            if (!sourceFile.exists()) {
                log.error("Source file does not exist: ${sourceFile.absolutePath}")
                return null
            }

            // Create attachment directory
            val attachmentDir = getAttachmentDir(attachmentId)
            attachmentDir.toPath().createDirectories()

            // Store file with original name in the attachment directory
            val storagePath = File(attachmentDir, sourceFile.name)

            // Skip if file already exists (assume it's the same, shared storage model)
            if (storagePath.exists()) {
                log.debug("Attachment file already exists (shared): attachmentId=$attachmentId, path=${storagePath.absolutePath}")
                return storagePath.absolutePath
            }

            // Copy file
            Files.copy(sourceFile.toPath(), storagePath.toPath())

            log.debug("Attachment stored: attachmentId=$attachmentId, path=${storagePath.absolutePath}")
            return storagePath.absolutePath
        } catch (e: FileSizeExceededException) {
            log.error("File too large: ${sourceFile.name} (${e.fileSize} bytes, max: ${e.maxAllowedSize} bytes)")
            throw e
        } catch (e: Exception) {
            log.error("Failed to save attachment file: ${e.message}", e)
            return null
        }
    }

    /**
     * Save all attachments for a message to persistent storage.
     * Generates IDs for attachments with empty IDs BEFORE saving files to avoid
     * file location mismatches between storage and database.
     * Processes multiple attachments and returns them with storagePath and ID populated.
     * Only attachments with filePath are saved; others are returned unchanged.
     *
     * @param attachments List of attachments to save (may have temporary filePath and empty ID)
     * @return List of attachments with ID and storagePath populated for saved files
     * @throws FileSizeExceededException if any file exceeds the maximum allowed size
     */
    fun saveAttachments(attachments: List<FileAttachmentDTO>): List<FileAttachmentDTO> = attachments.map { attachment ->
        // Generate ID FIRST if empty (before saving file) to avoid mismatch
        // between storage path (attachments/{id}/{filename}) and database key
        val attachmentWithId = if (attachment.id.isEmpty()) {
            attachment.copy(id = UUID.randomUUID().toString())
        } else {
            attachment
        }

        if (attachmentWithId.filePath != null) {
            try {
                val sourceFile = File(attachmentWithId.filePath)
                if (!sourceFile.exists()) {
                    log.warn("Attachment file not found: ${attachmentWithId.filePath}")
                    attachmentWithId
                } else {
                    // Now save using the generated/existing ID
                    val storagePath = saveAttachmentFile(attachmentWithId.id, sourceFile)
                    attachmentWithId.copy(storagePath = storagePath)
                }
            } catch (e: FileSizeExceededException) {
                log.error("Attachment file too large: ${attachmentWithId.fileName}")
                throw e // Re-throw to be handled by the UI
            } catch (e: Exception) {
                log.error("Failed to save attachment to storage: ${e.message}", e)
                attachmentWithId
            }
        } else {
            attachmentWithId
        }
    }

    /**
     * Retrieve a stored attachment file.
     *
     * @param attachmentId The attachment ID
     * @return The attachment file, or null if not found
     */
    fun getAttachmentFile(attachmentId: String): File? {
        try {
            val attachmentDir = getAttachmentDir(attachmentId)
            if (!attachmentDir.exists()) {
                log.debug("Attachment directory not found: ${attachmentDir.absolutePath}")
                return null
            }

            // List files in the attachment directory (should be one file)
            val files = attachmentDir.listFiles()
            if (files.isNullOrEmpty()) {
                log.debug("No files found in attachment directory: ${attachmentDir.absolutePath}")
                return null
            }

            // Return the first file (there should only be one)
            return files.firstOrNull { it.isFile }
        } catch (e: Exception) {
            log.error("Failed to retrieve attachment file: ${e.message}", e)
            return null
        }
    }

    /**
     * Delete an attachment file from storage.
     * This is called when an attachment's reference count reaches 0.
     * Only deletes if no other messages reference this attachment (via reference counting).
     *
     * @param attachmentId The attachment ID
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun deleteAttachmentFile(attachmentId: String) {
        try {
            val attachmentDir = getAttachmentDir(attachmentId)
            val path = attachmentDir.toPath()
            if (path.exists()) {
                path.deleteRecursively()
                log.debug("Deleted attachment: attachmentId=$attachmentId")
            }
        } catch (e: Exception) {
            log.error("Failed to delete attachment file: ${e.message}", e)
        }
    }
}
