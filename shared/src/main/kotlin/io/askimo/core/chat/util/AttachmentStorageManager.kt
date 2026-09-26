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
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Manages persistent storage of chat message attachments.
 * Files are stored under ${user.home}/.askimo/attachments/{sessionId}/{attachmentId}/{filename}
 * This allows users to download or rerun requests with original attachments.
 */
object AttachmentStorageManager {
    private val log = logger<AttachmentStorageManager>()

    private val maxFileSizeBytes: Long = AppConfig.indexing.maxFileBytes

    /**
     * Directory for a specific session's attachments.
     * @param sessionId The session ID
     * @return The session attachments directory
     */
    private fun getSessionAttachmentsDir(sessionId: String): File = File(AskimoHome.attachmentsDir().toFile(), sessionId)

    /**
     * Directory for a specific attachment within a session.
     * @param sessionId The session ID
     * @param attachmentId The attachment ID
     * @return The attachment directory
     */
    private fun getAttachmentDir(sessionId: String, attachmentId: String): File = File(getSessionAttachmentsDir(sessionId), attachmentId)

    /**
     * Save an attachment file to persistent storage.
     * Copies the file from the source location to the permanent storage directory.
     *
     * @param sessionId The session ID
     * @param attachmentId The attachment ID
     * @param sourceFile The source file to copy
     * @return The path to the stored file, or null if storage failed
     * @throws FileSizeExceededException if the file exceeds the maximum allowed size
     */
    fun saveAttachmentFile(sessionId: String, attachmentId: String, sourceFile: File): String? {
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
            val attachmentDir = getAttachmentDir(sessionId, attachmentId)
            attachmentDir.toPath().createDirectories()

            // Store file with original name in the attachment directory
            val storagePath = File(attachmentDir, sourceFile.name)

            // Copy file
            Files.copy(sourceFile.toPath(), storagePath.toPath())

            log.debug("Attachment stored: sessionId=$sessionId, attachmentId=$attachmentId, path=${storagePath.absolutePath}")
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
     * Processes multiple attachments and returns them with storagePath populated.
     * Only attachments with filePath are saved; others are returned unchanged.
     *
     * @param sessionId The session ID
     * @param attachments List of attachments to save (may have temporary filePath)
     * @return List of attachments with storagePath populated for saved files
     * @throws FileSizeExceededException if any file exceeds the maximum allowed size
     */
    fun saveAttachments(sessionId: String, attachments: List<FileAttachmentDTO>): List<FileAttachmentDTO> = attachments.map { attachment ->
        if (attachment.filePath != null) {
            try {
                val sourceFile = File(attachment.filePath)
                if (!sourceFile.exists()) {
                    log.warn("Attachment file not found: ${attachment.filePath}")
                    attachment
                } else {
                    val storagePath = saveAttachmentFile(sessionId, attachment.id, sourceFile)
                    attachment.copy(storagePath = storagePath)
                }
            } catch (e: FileSizeExceededException) {
                log.error("Attachment file too large: ${attachment.fileName}")
                throw e // Re-throw to be handled by the UI
            } catch (e: Exception) {
                log.error("Failed to save attachment to storage: ${e.message}", e)
                attachment
            }
        } else {
            attachment
        }
    }

    /**
     * Retrieve a stored attachment file.
     *
     * @param sessionId The session ID
     * @param attachmentId The attachment ID
     * @return The attachment file, or null if not found
     */
    fun getAttachmentFile(sessionId: String, attachmentId: String): File? {
        try {
            val attachmentDir = getAttachmentDir(sessionId, attachmentId)
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
     * Delete all attachments for a session.
     * This is called when a session is deleted.
     *
     * @param sessionId The session ID
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun deleteAttachmentFiles(sessionId: String) {
        try {
            val sessionDir = getSessionAttachmentsDir(sessionId)
            val path = sessionDir.toPath()
            if (path.exists()) {
                path.deleteRecursively()
                log.debug("Deleted attachment directory for session: $sessionId")
            }
        } catch (e: Exception) {
            log.error("Failed to delete attachment directory for session $sessionId: ${e.message}", e)
        }
    }

    /**
     * Delete a single attachment file.
     * This is called when a message with attachments is deleted.
     *
     * @param sessionId The session ID
     * @param attachmentId The attachment ID
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun deleteAttachmentFile(sessionId: String, attachmentId: String) {
        try {
            val attachmentDir = getAttachmentDir(sessionId, attachmentId)
            val path = attachmentDir.toPath()
            if (path.exists()) {
                path.deleteRecursively()
                log.debug("Deleted attachment: sessionId=$sessionId, attachmentId=$attachmentId")
            }
        } catch (e: Exception) {
            log.error("Failed to delete attachment file: ${e.message}", e)
        }
    }
}
