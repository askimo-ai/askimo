/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

/**
 * Status of a single tool call made by the AI during a streaming response.
 */
enum class ToolCallStatus {
    /** The tool has been requested and is currently executing. */
    RUNNING,

    /** The tool has finished executing successfully. */
    DONE,
}

/**
 * Represents a single tool call made by the AI during a streaming response.
 *
 * This is ephemeral state — it exists only for the duration of the active
 * streaming session and is not persisted to the database.
 *
 * @param toolName  The name of the tool being called (e.g. "weather_lookup", "read_file")
 * @param status    Current execution status: [ToolCallStatus.RUNNING] or [ToolCallStatus.DONE]
 * @param arguments Raw JSON arguments string passed to the tool (available from the start)
 * @param result    Tool output text; null while still running or if the tool produced no output
 * @param hasFailed Whether the tool execution ended with an error
 */
data class ToolCallInfo(
    val toolName: String,
    val status: ToolCallStatus,
    val arguments: String? = null,
    val result: String? = null,
    val hasFailed: Boolean = false,
)
