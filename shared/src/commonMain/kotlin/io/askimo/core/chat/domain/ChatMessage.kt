/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.context.MessageRole
import kotlin.time.Clock
import kotlin.time.Instant

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant = Clock.System.now(),
    val isOutdated: Boolean = false,
    val editParentId: String? = null,
    val isEdited: Boolean = false,
    val attachments: List<FileAttachment> = emptyList(),
    val isFailed: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
    val isBookmarked: Boolean = false,
    // Ordered tool-call + response-text blocks (Tool + Token only) — see
    // ChatMessageDTO.contentBlocks for rationale.
    val contentBlocks: List<TurnTimelineEntry> = emptyList(),
)
