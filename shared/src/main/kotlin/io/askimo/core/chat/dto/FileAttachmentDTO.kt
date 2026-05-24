/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import java.time.Instant

/**
 * Data Transfer Object for file attachments.
 * Used to transfer attachment data between layers (service -> UI).
 */
data class FileAttachmentDTO(
    val id: String,
    val messageId: String,
    val sessionId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Instant,
    val content: String? = null, // Lazy-loaded content, read just before sending to AI
    val filePath: String? = null, // File path for lazy loading
)
