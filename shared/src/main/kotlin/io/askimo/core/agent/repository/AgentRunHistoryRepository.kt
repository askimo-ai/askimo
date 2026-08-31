/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.domain.AgentRunHistoryTable
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Repository for persisting and querying [AgentRunRecord] entries.
 *
 * [AgentRunRecord.activityLog] is stored as a newline-delimited text block so the
 * SQLite file stays human-readable without requiring a JSON library.
 */
class AgentRunHistoryRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    private val log = logger<AgentRunHistoryRepository>()

    /**
     * Persists a new run record. The [record.id] must already be set (UUID).
     */
    fun save(record: AgentRunRecord) {
        transaction(database) {
            AgentRunHistoryTable.insert {
                it[id] = record.id
                it[skillPath] = record.skillPath
                it[userInput] = record.userInput
                it[response] = record.response
                it[error] = record.error
                it[agentSessionId] = record.agentSessionId
                it[workspaceDir] = record.workspaceDir
                it[activityLog] = encodeLog(record.activityLog)
                it[inputTokens] = record.inputTokens
                it[outputTokens] = record.outputTokens
                it[totalTokens] = record.totalTokens
                it[durationMs] = record.durationMs
                it[createdAt] = record.createdAt
            }
        }
        log.debug("Saved skill run record '{}' for skill '{}'", record.id, record.skillPath)
    }

    /**
     * Returns up to [limit] run records across all skills, newest first.
     */
    fun findAll(limit: Int = 200): List<AgentRunRecord> = transaction(database) {
        AgentRunHistoryTable
            .selectAll()
            .orderBy(AgentRunHistoryTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::toRecord)
    }

    /**
     * Returns up to [limit] run records for the given [skillPath], newest first.
     */
    fun findBySkillPath(skillPath: String, limit: Int = 50): List<AgentRunRecord> = transaction(database) {
        AgentRunHistoryTable
            .selectAll()
            .where { AgentRunHistoryTable.skillPath eq skillPath }
            .orderBy(AgentRunHistoryTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::toRecord)
    }

    /**
     * Deletes a single run record by [id].
     */
    fun deleteById(id: String) {
        transaction(database) {
            AgentRunHistoryTable.deleteWhere { AgentRunHistoryTable.id eq id }
        }
        log.debug("Deleted skill run record '{}'", id)
    }

    /**
     * Deletes all run records for the given [skillPath].
     */
    fun deleteBySkillPath(skillPath: String) {
        transaction(database) {
            AgentRunHistoryTable.deleteWhere { AgentRunHistoryTable.skillPath eq skillPath }
        }
        log.debug("Deleted all run records for skill '{}'", skillPath)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun toRecord(row: ResultRow): AgentRunRecord = AgentRunRecord(
        id = row[AgentRunHistoryTable.id],
        skillPath = row[AgentRunHistoryTable.skillPath],
        userInput = row[AgentRunHistoryTable.userInput],
        response = row[AgentRunHistoryTable.response],
        error = row[AgentRunHistoryTable.error],
        agentSessionId = row[AgentRunHistoryTable.agentSessionId],
        workspaceDir = row[AgentRunHistoryTable.workspaceDir],
        activityLog = decodeLog(row[AgentRunHistoryTable.activityLog]),
        inputTokens = row[AgentRunHistoryTable.inputTokens],
        outputTokens = row[AgentRunHistoryTable.outputTokens],
        totalTokens = row[AgentRunHistoryTable.totalTokens],
        durationMs = row[AgentRunHistoryTable.durationMs],
        createdAt = row[AgentRunHistoryTable.createdAt],
    )

    private fun encodeLog(entries: List<String>): String = entries.joinToString("\n") { it.replace("\n", "\\n") }

    private fun decodeLog(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().map { it.replace("\\n", "\n") }
    }
}
