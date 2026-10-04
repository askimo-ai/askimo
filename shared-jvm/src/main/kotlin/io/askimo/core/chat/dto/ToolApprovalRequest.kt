/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

/**
 * Represents a pending tool-execution approval request surfaced to the user
 * during an active AI streaming session.
 *
 * The streaming thread is blocked until one of the two callbacks is invoked.
 * Call [approve] to let the tool proceed, or [deny] to cancel it.
 * One of the two **must** be called — failing to do so stalls the streaming thread
 * until the 120-second timeout fires.
 */
data class ToolApprovalRequest(
    /** Name of the tool the AI wants to invoke. */
    val toolName: String,
    /** Raw JSON arguments string, or null if the tool takes no arguments. */
    val arguments: String?,
    /** Unblocks the streaming thread and lets the tool execute. */
    val approve: () -> Unit,
    /** Unblocks the streaming thread and cancels the tool call. */
    val deny: () -> Unit,
)
