/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

import io.askimo.core.db.sqliteInstant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

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
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

/**
 * Exposed table definition for plan_executions.
 *
 * Inputs are stored as a JSON string (simple key=value pairs, no external deps needed).
 * Status is stored as its enum name string for readability in the database.
 */
object PlanExecutionsTable : Table("plan_executions") {
    val id = varchar("id", 36)
    val planId = varchar("plan_id", 255)
    val planName = varchar("plan_name", 512)

    /** JSON-encoded Map<String, String> of user-provided inputs. */
    val inputs = text("inputs").default("{}")
    val status = varchar("status", 32).default(PlanExecutionStatus.IDLE.name)
    val runCount = integer("run_count").default(1)
    val sessionId = varchar("session_id", 36).nullable()

    /** Final AI-generated output text; null until the run COMPLETES successfully. */
    val output = text("output").nullable()

    /**
     * JSON-encoded list of step outputs:
     * `[{"stepName":"step-a","output":"...","inputTokens":12,"outputTokens":34,"totalTokens":46,"durationMs":1000}, ...]`.
     * Null for executions created before this column was added.
     */
    val stepOutputs = text("step_outputs").nullable()

    /** Sum of input tokens across all steps; null for old rows or when no token data available. */
    val totalInputTokens = integer("total_input_tokens").nullable()

    /** Sum of output tokens across all steps; null for old rows or when no token data available. */
    val totalOutputTokens = integer("total_output_tokens").nullable()

    /** Sum of total tokens across all steps; null for old rows or when no token data available. */
    val totalTokens = integer("total_tokens").nullable()

    /** Sum of wall-clock duration across all steps in milliseconds; null for old rows. */
    val totalDurationMs = long("total_duration_ms").nullable()

    val errorMessage = text("error_message").nullable()
    val createdAt = sqliteInstant("created_at")
    val updatedAt = sqliteInstant("updated_at")

    override val primaryKey = PrimaryKey(id)
}
