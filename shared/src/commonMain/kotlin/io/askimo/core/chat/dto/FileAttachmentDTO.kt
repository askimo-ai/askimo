/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import kotlin.time.Instant

/**
 * Data Transfer Object for file attachments.
 * Used to transfer attachment data between layers (service -> UI).
 *
 * Note: messageId and sessionId are not part of the domain model (FileAttachment)
 * in the reference-counted shared storage model. They are provided here for compatibility
 * with UI layer that may need this context, but should be managed at the repository layer.
 */
data class FileAttachmentDTO(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Instant,
    val content: String? = null, // Lazy-loaded content, read just before sending to AI
    val filePath: String? = null, // Temporary file path during composition
    val storagePath: String? = null, // Persistent storage path after message is saved
)

