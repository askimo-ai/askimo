/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.dto

import java.time.Instant

/**
 * UI-layer transcript entry for one turn (user input or AI response) of an agentic run —
 * the agent-domain equivalent of [io.askimo.core.chat.dto.ChatMessageDTO] for regular chat,
 * but slimmer: agent conversations never support inline edit, retry, bookmarking, forking, or
 * attachments, so this type only carries the fields an agent turn actually produces.
 *
 * Rendered via the shared `messageBubble`/`turnTimelineView` blocks by `agentMessageList`,
 * which adapts instances of this type to a `ChatMessageDTO` at the rendering boundary.
 */
data class AgentTurnMessageDTO(
    val id: String?,
    val content: String,
    val isUser: Boolean,
    val timestamp: Instant?,
    val isFailed: Boolean = false,
    // True when this AI turn was stopped mid-flight via ExternalAgent.cancel() rather than
    // failing or completing normally — rendered distinctly from isFailed (no retry action, a
    // neutral "Cancelled" label instead of an error state).
    val isCancelled: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
)
