/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.repository

import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Plan_executions
import io.askimo.core.logging.logger
import io.askimo.core.plan.domain.PlanExecution
import io.askimo.core.plan.domain.PlanExecutionStatus
import io.askimo.core.plan.domain.PlanStepOutput
import io.askimo.core.util.JsonUtils.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import kotlin.time.Clock

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

private fun encodeInputs(inputs: Map<String, String>): String = inputs.entries.joinToString("\n") { (k, v) -> "$k=${v.replace("\n", "\\n")}" }

private fun decodeInputs(raw: String): Map<String, String> {
    if (raw.isBlank() || raw == "{}") return emptyMap()
    return raw.lines()
        .filter { it.contains('=') }
        .associate { line ->
            val idx = line.indexOf('=')
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1).replace("\\n", "\n")
            key to value
        }
}

/** Encodes structured step outputs as a JSON array. */
private fun encodeStepOutputs(steps: List<PlanStepOutput>): String? {
    if (steps.isEmpty()) return null
    return json.encodeToString(steps)
}

private fun decodeStepOutputs(raw: String?): List<PlanStepOutput> {
    if (raw.isNullOrBlank()) return emptyList()

    // New format: JSON array of PlanStepOutput.
    runCatching {
        json.decodeFromString<List<PlanStepOutput>>(raw)
    }.getOrNull()?.let { return it }

    // Compatibility format: JSON with legacy keys {"step":"...","output":"..."}.
    val legacySteps = runCatching {
        val element = json.parseToJsonElement(raw)
        if (element !is JsonArray) return@runCatching null
        element.jsonArray.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val stepName = obj["stepName"]?.jsonPrimitive?.contentOrNull
                ?: obj["step"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val output = obj["output"]?.jsonPrimitive?.contentOrNull ?: ""
            PlanStepOutput(
                stepName = stepName,
                output = output,
                inputTokens = obj["inputTokens"]?.jsonPrimitive?.intOrNull,
                outputTokens = obj["outputTokens"]?.jsonPrimitive?.intOrNull,
                totalTokens = obj["totalTokens"]?.jsonPrimitive?.intOrNull,
                durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull,
            )
        }
    }.getOrNull()
    if (legacySteps != null) return legacySteps

    // Legacy line format: stepName|output
    return raw.lines().mapNotNull { line ->
        val sep = line.indexOf('|')
        if (sep < 0) return@mapNotNull null
        PlanStepOutput(
            stepName = line.substring(0, sep),
            output = line.substring(sep + 1).replace("\\n", "\n"),
        )
    }
}

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
    }

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
}
