/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import kotlin.time.Clock
import kotlin.time.Instant

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
    val createdAt: Instant = Clock.System.now(),
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
