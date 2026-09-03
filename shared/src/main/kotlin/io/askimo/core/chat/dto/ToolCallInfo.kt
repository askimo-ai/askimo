/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import kotlinx.serialization.Serializable

/**
 * Status of a single tool call made by the AI during a streaming response.
 */
@Serializable
enum class ToolCallStatus {
    /** The tool has been requested and is currently executing. */
    RUNNING,

    /** The tool has finished executing successfully. */
    DONE,
}

/**
 * Represents a single tool call made by the AI during a streaming response.
 *
 * Persisted as part of a turn's ordered content blocks — see [TurnTimelineEntry] — for both
 * agentic runs ([io.askimo.core.agent.domain.AgentRunRecord]) and regular chat
 * ([io.askimo.core.chat.domain.ChatMessage]), since knowing which tools were used — in order —
 * is a useful audit trail. Raw "thinking"/reasoning content is deliberately NOT persisted
 * alongside it (session-only).
 *
 * @param toolName  The name of the tool being called (e.g. "weather_lookup", "read_file")
 * @param status    Current execution status: [ToolCallStatus.RUNNING] or [ToolCallStatus.DONE]
 * @param arguments Raw JSON arguments string passed to the tool (available from the start)
 * @param result    Tool output text; null while still running or if the tool produced no output
 * @param hasFailed Whether the tool execution ended with an error
 */
@Serializable
data class ToolCallInfo(
    val toolName: String,
    val status: ToolCallStatus,
    val arguments: String? = null,
    val result: String? = null,
    val hasFailed: Boolean = false,
)
