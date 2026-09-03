/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
 * [arguments]/[result] should be capped to [MAX_FIELD_LENGTH] by callers before constructing
 * this (see [truncated]) — some tools (e.g. file read/write, shell commands) can otherwise
 * produce megabytes of raw text, which would bloat the in-memory timeline, the persisted
 * `content_json` column, and any server sync payload. Unlike [io.askimo.core.agent.ExternalAgent]'s
 * `onToolCall` `detail` string (a short display-only label, capped much more aggressively at
 * [io.askimo.core.agent.ExternalAgent.TOOL_DETAIL_MAX_LENGTH]), these fields are meant to retain
 * enough raw content to still be useful for debugging/audit — just not unbounded.
 *
 * @param toolName        The name of the tool being called (e.g. "weather_lookup", "read_file")
 * @param status          Current execution status: [ToolCallStatus.RUNNING] or [ToolCallStatus.DONE]
 * @param arguments       Raw JSON arguments string passed to the tool (available from the start)
 * @param result          Tool output text; null while still running or if the tool produced no output
 * @param hasFailed       Whether the tool execution ended with an error
 * @param startedAtMillis Wall-clock time this call started, used to render a live "still
 *   running... Ns" elapsed timer while [status] is [ToolCallStatus.RUNNING] — some tools (e.g.
 *   a long shell command) can take a while, and without this the user has no feedback that the
 *   AI is still working (unlike the pre-first-token "Thinking... Ns" indicator). Marked
 *   [Transient] — session-only, never persisted, since it's meaningless for already-completed
 *   historical tool calls loaded back from `content_json`.
 */
@Serializable
data class ToolCallInfo(
    val toolName: String,
    val status: ToolCallStatus,
    val arguments: String? = null,
    val result: String? = null,
    val hasFailed: Boolean = false,
    @Transient val startedAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Max length of [arguments]/[result] before truncation — see [truncated]. */
        const val MAX_FIELD_LENGTH = 1_000

        /**
         * Builds a [ToolCallInfo] with [arguments]/[result] truncated to [MAX_FIELD_LENGTH].
         * All producers (native MCP tool execution, external agent parsers, etc.) should use
         * this instead of the raw constructor to guarantee unbounded tool payloads never reach
         * the in-memory timeline, persisted storage, or sync payloads.
         */
        fun truncated(
            toolName: String,
            status: ToolCallStatus,
            arguments: String? = null,
            result: String? = null,
            hasFailed: Boolean = false,
            startedAtMillis: Long = System.currentTimeMillis(),
        ): ToolCallInfo = ToolCallInfo(
            toolName = toolName,
            status = status,
            arguments = arguments?.truncateField(),
            result = result?.truncateField(),
            hasFailed = hasFailed,
            startedAtMillis = startedAtMillis,
        )

        private fun String.truncateField(): String = if (length > MAX_FIELD_LENGTH) take(MAX_FIELD_LENGTH) + "…" else this
    }
}
