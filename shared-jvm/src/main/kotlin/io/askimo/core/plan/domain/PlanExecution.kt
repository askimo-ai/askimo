/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/** Lifecycle state of a single [PlanExecution] run. */
enum class PlanExecutionStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/** Persisted output payload for one completed plan step. */
@Serializable
data class PlanStepOutput(
    val stepName: String,
    val output: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
)

/**
 * Persisted record of one run of a [PlanDef].
 *
 * Owns the structured metadata for a plan run.
 * The actual message history lives in the linked [sessionId] ChatSession.
 *
 * @param id            Unique execution identifier (UUID).
 * @param planId        References the [PlanDef.id] that was executed.
 * @param planName      Snapshot of the plan name at execution time.
 * @param inputs        Variable values the user provided before clicking Run.
 * @param status        Current lifecycle state.
 * @param runCount      How many times this execution has been re-run (starts at 1).
 * @param sessionId     Linked ChatSession id that holds the message history.
 * @param output        The final AI-generated result text; populated on COMPLETED.
 * @param stepOutputs   Ordered list of completed step outputs, including optional
 *                      token usage and duration per step.
 * @param totalInputTokens  Sum of input tokens across all completed steps; null when unavailable.
 * @param totalOutputTokens Sum of output tokens across all completed steps; null when unavailable.
 * @param totalTokens       Sum of total tokens across all completed steps; null when unavailable.
 * @param totalDurationMs   Sum of wall-clock duration across all steps in milliseconds; null when unavailable.
 * @param errorMessage  Populated when [status] is [PlanExecutionStatus.FAILED].
 * @param createdAt     When the execution record was first created.
 * @param updatedAt     When the execution record was last modified.
 */
data class PlanExecution(
    val id: String,
    val planId: String,
    val planName: String,
    val inputs: Map<String, String> = emptyMap(),
    val status: PlanExecutionStatus = PlanExecutionStatus.IDLE,
    val runCount: Int = 1,
    val sessionId: String? = null,
    val output: String? = null,
    val stepOutputs: List<PlanStepOutput> = emptyList(),
    val totalInputTokens: Int? = null,
    val totalOutputTokens: Int? = null,
    val totalTokens: Int? = null,
    val totalDurationMs: Long? = null,
    val errorMessage: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
)
