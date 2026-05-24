/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.repository

import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import io.askimo.core.plan.domain.PlanExecution
import io.askimo.core.plan.domain.PlanExecutionStatus
import io.askimo.core.plan.domain.PlanExecutionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * Repository for persisting and querying [PlanExecution] records.
 *
 * Inputs are stored as a simple `key=value` text (one per line) to avoid a JSON
 * dependency at this layer. The format is intentionally human-readable so the
 * SQLite file can be inspected without tooling.
 */
class PlanExecutionRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    private val log = logger<PlanExecutionRepository>()

    /**
     * Persists a new [PlanExecution]. Assigns a UUID if [execution.id] is blank.
     */
    fun create(execution: PlanExecution): PlanExecution {
        val record = execution.copy(
            id = execution.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = execution.createdAt,
            updatedAt = Instant.now(),
        )

        transaction(database) {
            PlanExecutionsTable.insert {
                it[id] = record.id
                it[planId] = record.planId
                it[planName] = record.planName
                it[inputs] = encodeInputs(record.inputs)
                it[status] = record.status.name
                it[runCount] = record.runCount
                it[sessionId] = record.sessionId
                it[output] = record.output
                it[stepOutputs] = encodeStepOutputs(record.stepOutputs)
                it[errorMessage] = record.errorMessage
                it[createdAt] = record.createdAt
                it[updatedAt] = record.updatedAt
            }
        }

        log.debug("Created plan execution '{}' for plan '{}'", record.id, record.planId)
        return record
    }

    /**
     * Updates the mutable fields of an existing execution.
     * Only [status], [sessionId], [output], [errorMessage], [runCount], and [updatedAt] are written.
     */
    fun update(execution: PlanExecution): PlanExecution {
        val record = execution.copy(updatedAt = Instant.now())

        transaction(database) {
            PlanExecutionsTable.update({ PlanExecutionsTable.id eq record.id }) {
                it[status] = record.status.name
                it[sessionId] = record.sessionId
                it[output] = record.output
                it[stepOutputs] = encodeStepOutputs(record.stepOutputs)
                it[errorMessage] = record.errorMessage
                it[runCount] = record.runCount
                it[updatedAt] = record.updatedAt
            }
        }

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
        transaction(database) {
            PlanExecutionsTable.update({ PlanExecutionsTable.id eq id }) {
                it[PlanExecutionsTable.status] = status.name
                it[PlanExecutionsTable.errorMessage] = errorMessage
                it[updatedAt] = Instant.now()
            }
        }
    }

    fun delete(id: String) {
        transaction(database) {
            PlanExecutionsTable.deleteWhere { PlanExecutionsTable.id eq id }
        }
    }

    fun findById(id: String): PlanExecution? = transaction(database) {
        PlanExecutionsTable
            .selectAll()
            .where { PlanExecutionsTable.id eq id }
            .singleOrNull()
            ?.toPlanExecution()
    }

    /**
     * Returns all executions for a given plan, newest first.
     */
    fun findByPlanId(planId: String): List<PlanExecution> = transaction(database) {
        PlanExecutionsTable
            .selectAll()
            .where { PlanExecutionsTable.planId eq planId }
            .orderBy(PlanExecutionsTable.createdAt, SortOrder.DESC)
            .map { it.toPlanExecution() }
    }

    /**
     * Returns all executions, newest first.
     */
    fun findAll(): List<PlanExecution> = transaction(database) {
        PlanExecutionsTable
            .selectAll()
            .orderBy(PlanExecutionsTable.createdAt, SortOrder.DESC)
            .map { it.toPlanExecution() }
    }

    private fun ResultRow.toPlanExecution() = PlanExecution(
        id = this[PlanExecutionsTable.id],
        planId = this[PlanExecutionsTable.planId],
        planName = this[PlanExecutionsTable.planName],
        inputs = decodeInputs(this[PlanExecutionsTable.inputs]),
        status = PlanExecutionStatus.valueOf(this[PlanExecutionsTable.status]),
        runCount = this[PlanExecutionsTable.runCount],
        sessionId = this[PlanExecutionsTable.sessionId],
        output = this[PlanExecutionsTable.output],
        stepOutputs = decodeStepOutputs(this[PlanExecutionsTable.stepOutputs]),
        errorMessage = this[PlanExecutionsTable.errorMessage],
        createdAt = this[PlanExecutionsTable.createdAt],
        updatedAt = this[PlanExecutionsTable.updatedAt],
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

    /**
     * Encodes step outputs as `stepName|output` lines (one per step).
     * Step names are kebab-case identifiers and cannot contain `|` or newlines.
     */
    private fun encodeStepOutputs(steps: List<Pair<String, String>>): String? {
        if (steps.isEmpty()) return null
        return steps.joinToString("\n") { (name, output) ->
            "$name|${output.replace("\n", "\\n")}"
        }
    }

    private fun decodeStepOutputs(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val sep = line.indexOf('|')
            if (sep < 0) return@mapNotNull null
            line.substring(0, sep) to line.substring(sep + 1).replace("\\n", "\n")
        }
    }
}
