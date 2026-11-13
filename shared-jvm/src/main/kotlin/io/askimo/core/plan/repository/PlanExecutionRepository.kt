/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.repository

import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Plan_executions
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import io.askimo.core.plan.domain.PlanExecution
import io.askimo.core.plan.domain.PlanExecutionCodec.decodeInputs
import io.askimo.core.plan.domain.PlanExecutionCodec.decodeStepOutputs
import io.askimo.core.plan.domain.PlanExecutionCodec.encodeInputs
import io.askimo.core.plan.domain.PlanExecutionCodec.encodeStepOutputs
import io.askimo.core.plan.domain.PlanExecutionStatus
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Maps a generated [Plan_executions] row to the shared [PlanExecution] domain object.
 */
private fun Plan_executions.toPlanExecution() = PlanExecution(
    id = id,
    planId = plan_id,
    planName = plan_name,
    inputs = decodeInputs(inputs),
    status = PlanExecutionStatus.valueOf(status),
    runCount = run_count.toInt(),
    sessionId = session_id,
    output = output,
    stepOutputs = decodeStepOutputs(step_outputs),
    totalInputTokens = total_input_tokens?.toInt(),
    totalOutputTokens = total_output_tokens?.toInt(),
    totalTokens = total_tokens?.toInt(),
    totalDurationMs = total_duration_ms,
    errorMessage = error_message,
    createdAt = created_at,
    updatedAt = updated_at,
)

/**
 *
 * Repository for persisting and querying [PlanExecution] records.
 *
 * Inputs are stored as a simple `key=value` text (one per line).
 * Step outputs are stored as JSON for extensibility.
 */
class PlanExecutionRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val log = logger<PlanExecutionRepository>()
    private val queries get() = db.planExecutionsQueries

    /**
     * Persists a new [PlanExecution]. Assigns a UUID if [execution.id] is blank.
     */
    fun create(execution: PlanExecution): PlanExecution {
        val record = execution.copy(
            id = execution.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = execution.createdAt,
            updatedAt = Clock.System.now(),
        )

        queries.insertExecution(
            id = record.id,
            planId = record.planId,
            planName = record.planName,
            inputs = encodeInputs(record.inputs),
            status = record.status.name,
            runCount = record.runCount.toLong(),
            sessionId = record.sessionId,
            output = record.output,
            stepOutputs = encodeStepOutputs(record.stepOutputs),
            totalInputTokens = record.totalInputTokens?.toLong(),
            totalOutputTokens = record.totalOutputTokens?.toLong(),
            totalTokens = record.totalTokens?.toLong(),
            totalDurationMs = record.totalDurationMs,
            errorMessage = record.errorMessage,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )

        log.debug("Created plan execution '{}' for plan '{}'", record.id, record.planId)
        EventBus.post(PushDataToServerEvent(reason = "plan execution created"))
        return record
    }

    /**
     * Updates the mutable fields of an existing execution.
     * Only [status], [sessionId], [output], [errorMessage], [runCount], and [updatedAt] are written.
     */
    fun update(execution: PlanExecution): PlanExecution {
        val record = execution.copy(updatedAt = Clock.System.now())

        queries.updateExecution(
            status = record.status.name,
            sessionId = record.sessionId,
            output = record.output,
            stepOutputs = encodeStepOutputs(record.stepOutputs),
            totalInputTokens = record.totalInputTokens?.toLong(),
            totalOutputTokens = record.totalOutputTokens?.toLong(),
            totalTokens = record.totalTokens?.toLong(),
            totalDurationMs = record.totalDurationMs,
            errorMessage = record.errorMessage,
            runCount = record.runCount.toLong(),
            updatedAt = record.updatedAt,
            id = record.id,
        )

        EventBus.post(PushDataToServerEvent(reason = "plan execution updated"))
        return record
    }

    /**
     * Convenience: update just the status (and optionally errorMessage) of an execution.
     */
    fun updateStatus(
        id: String,
        status: PlanExecutionStatus,
        errorMessage: String? = null,
    ) {
        queries.updateStatus(
            status = status.name,
            errorMessage = errorMessage,
            updatedAt = Clock.System.now(),
            id = id,
        )
        EventBus.post(PushDataToServerEvent(reason = "plan execution status changed"))
    }

    /**
     * Hard-deletes an execution locally. Does **not** propagate the deletion anywhere else:
     * once this call returns the row is gone, so [getUnsynced] can never find it and there is no
     * tombstone left to report. Posting a generic change-notification event here would therefore
     * be misleading — it would trigger a round trip that silently omits this deletion.
     *
     * Callers that need the deletion to propagate elsewhere (e.g. another device or backend) must
     * explicitly notify that destination — with its own dedicated delete call — *before* invoking
     * this method, mirroring how [io.askimo.core.plan.repository.PlanDefRepository.delete] and
     * [io.askimo.core.chat.repository.ChatSessionRepository.deleteSession] are paired with an
     * explicit remote-delete call at their call sites instead of relying on the generic
     * created/updated change event.
     */
    fun delete(id: String) {
        queries.deleteById(id)
    }

    fun findById(id: String): PlanExecution? = queries.selectById(id).executeAsOneOrNull()?.toPlanExecution()

    /**
     * Returns all executions for a given plan, newest first.
     */
    fun findByPlanId(planId: String): List<PlanExecution> = queries.selectByPlanIdOrderedDesc(planId).executeAsList().map { it.toPlanExecution() }

    /**
     * Returns all executions, newest first.
     */
    fun findAll(): List<PlanExecution> = queries.selectAllOrderedDesc().executeAsList().map { it.toPlanExecution() }

    // ── Sync support ─────────────────────────────────────────────────────────

    /**
     * Marks an execution as successfully synced by setting `synced_at` to now — but only if
     * [expectedUpdatedAt] still matches the row's current `updated_at`.
     *
     * Without this check, a local update that lands while a push for an older revision is still
     * in flight would be marked synced once that in-flight push completes, even though the newer
     * update was never sent: `synced_at` would then be later than `updated_at`, and [getUnsynced]
     * would silently omit a revision the remote side never received. Passing the exact revision
     * that was pushed and only committing the mark when it's still current turns this into a
     * compare-and-swap, so a concurrent local edit simply leaves the row unsynced (and therefore
     * still eligible for the next push) instead of being falsely marked synced.
     *
     * @return `true` if the row was marked synced; `false` if it was modified again since
     *   [expectedUpdatedAt] (or no longer exists) and must be retried on the next push.
     */
    fun markSynced(id: String, expectedUpdatedAt: Instant): Boolean = queries.markSynced(Clock.System.now(), id, expectedUpdatedAt).value > 0

    /**
     * Returns executions that have never been synced or were modified locally since
     * their last successful sync, ordered by `updated_at` ascending.
     *
     * @param limit Maximum rows to return in one batch.
     */
    fun getUnsynced(limit: Int = 50): List<PlanExecution> = queries.selectAllOrderedByUpdatedAtAsc().executeAsList()
        .mapNotNull { row ->
            val updatedAt = row.updated_at
            val syncedAt = row.synced_at
            if (syncedAt == null || updatedAt > syncedAt) row.toPlanExecution() else null
        }
        .take(limit)

    /**
     * Upserts a single execution received from a remote pull: inserts if new, otherwise
     * overwrites only when [execution.updatedAt] is strictly newer than what's stored locally,
     * then marks the row synced. Mirrors [io.askimo.core.chat.repository.ChatSessionRepository.upsertFromServer].
     *
     * Wrapped in [db.transaction] so the existence check and the write are atomic — otherwise a
     * racing local [update] could slip in between and get silently overwritten.
     */
    fun upsertFromServer(execution: PlanExecution) {
        db.transaction {
            val now = Clock.System.now()
            val storedUpdatedAt = queries.selectExistingByIds(listOf(execution.id)).executeAsList()
                .firstOrNull()?.updated_at

            if (storedUpdatedAt == null) {
                queries.insertFromServer(
                    id = execution.id,
                    planId = execution.planId,
                    planName = execution.planName,
                    inputs = encodeInputs(execution.inputs),
                    status = execution.status.name,
                    runCount = execution.runCount.toLong(),
                    sessionId = execution.sessionId,
                    output = execution.output,
                    stepOutputs = encodeStepOutputs(execution.stepOutputs),
                    totalInputTokens = execution.totalInputTokens?.toLong(),
                    totalOutputTokens = execution.totalOutputTokens?.toLong(),
                    totalTokens = execution.totalTokens?.toLong(),
                    totalDurationMs = execution.totalDurationMs,
                    errorMessage = execution.errorMessage,
                    createdAt = execution.createdAt,
                    updatedAt = execution.updatedAt,
                    syncedAt = now,
                )
                log.debug("upsertFromServer: inserted plan execution {}", execution.id)
            } else if (execution.updatedAt > storedUpdatedAt) {
                queries.updateFromServer(
                    planId = execution.planId,
                    planName = execution.planName,
                    inputs = encodeInputs(execution.inputs),
                    status = execution.status.name,
                    runCount = execution.runCount.toLong(),
                    sessionId = execution.sessionId,
                    output = execution.output,
                    stepOutputs = encodeStepOutputs(execution.stepOutputs),
                    totalInputTokens = execution.totalInputTokens?.toLong(),
                    totalOutputTokens = execution.totalOutputTokens?.toLong(),
                    totalTokens = execution.totalTokens?.toLong(),
                    totalDurationMs = execution.totalDurationMs,
                    errorMessage = execution.errorMessage,
                    updatedAt = execution.updatedAt,
                    syncedAt = now,
                    id = execution.id,
                )
                log.debug("upsertFromServer: updated plan execution {} (server newer)", execution.id)
            } else {
                log.debug("upsertFromServer: skipped plan execution {} (local is same age or newer)", execution.id)
            }
        }
    }
}
