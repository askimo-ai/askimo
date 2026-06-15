/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import java.time.Instant

/**
 * Data Transfer Object for chat messages.
 * Used to transfer message data between layers (service -> UI).
 */
data class ChatMessageDTO(
    val id: String?,
    val content: String,
    val isUser: Boolean,
    val timestamp: Instant?,
    val isOutdated: Boolean = false,
    val editParentId: String? = null,
    val isEdited: Boolean = false,
    val attachments: List<FileAttachmentDTO> = emptyList(),
    val isFailed: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
)
