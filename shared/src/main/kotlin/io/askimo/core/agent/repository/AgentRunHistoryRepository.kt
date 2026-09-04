/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.domain.AgentRunHistoryTable
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

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
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Persists a new run record. The [record.id] must already be set (UUID).
     */
    fun save(record: AgentRunRecord) {
        transaction(database) {
            AgentRunHistoryTable.insert {
                it[id] = record.id
                it[workspaceId] = record.workspaceId
                it[conversationId] = record.conversationId
                it[title] = record.title
                it[userInput] = record.userInput
                it[response] = record.response
                it[error] = record.error
                it[agentSessionId] = record.agentSessionId
                it[activityLog] = encodeLog(record.activityLog)
                it[contentJson] = if (record.contentBlocks.isEmpty()) null else json.encodeToString(record.contentBlocks)
                it[inputTokens] = record.inputTokens
                it[outputTokens] = record.outputTokens
                it[totalTokens] = record.totalTokens
                it[durationMs] = record.durationMs
                it[createdAt] = record.createdAt
            }
        }
        log.debug("Saved skill run record '{}' for workspace '{}'", record.id, record.workspaceId)
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
     * Returns up to [limit] run records for the given [workspaceId], newest first.
     */
    fun findByWorkspaceId(workspaceId: String, limit: Int = 200): List<AgentRunRecord> = transaction(database) {
        AgentRunHistoryTable
            .selectAll()
            .where { AgentRunHistoryTable.workspaceId eq workspaceId }
            .orderBy(AgentRunHistoryTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::toRecord)
    }

    /**
     * Returns every turn belonging to [conversationId], oldest first — used to
     * reconstruct the full multi-turn thread when reopening a history entry.
     * Turns are strictly serialized when created (a new turn can't start until the
     * previous one finishes and is saved), so ordering by [AgentRunHistoryTable.createdAt]
     * alone is reliable here.
     */
    fun findByConversationId(conversationId: String): List<AgentRunRecord> = transaction(database) {
        AgentRunHistoryTable
            .selectAll()
            .where { AgentRunHistoryTable.conversationId eq conversationId }
            .orderBy(AgentRunHistoryTable.createdAt, SortOrder.ASC)
            .map(::toRecord)
    }

    /**
     * Updates the [AgentRunRecord.title] on every turn belonging to [conversationId] — call
     * this once the async AI-generated title is ready, so the whole conversation (and the
     * single row representing it in the history list) reflects the new title without any
     * join/lookup needed at read time.
     */
    fun updateTitleForConversation(conversationId: String, newTitle: String) {
        transaction(database) {
            AgentRunHistoryTable.update({ AgentRunHistoryTable.conversationId eq conversationId }) {
                it[title] = newTitle
            }
        }
        log.debug("Updated title for conversation '{}'", conversationId)
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
     * Deletes every turn belonging to [conversationId] — use this instead of [deleteById]
     * when removing a conversation from the history list, so the whole thread is removed
     * rather than just its most recent turn.
     */
    fun deleteByConversationId(conversationId: String) {
        transaction(database) {
            AgentRunHistoryTable.deleteWhere { AgentRunHistoryTable.conversationId eq conversationId }
        }
        log.debug("Deleted all run records for conversation '{}'", conversationId)
    }

    /**
     * Deletes all run records belonging to the given [workspaceId] — call this when a
     * [io.askimo.core.agent.domain.Workspace] is removed, to avoid orphaned run history.
     */
    fun deleteByWorkspaceId(workspaceId: String) {
        transaction(database) {
            AgentRunHistoryTable.deleteWhere { AgentRunHistoryTable.workspaceId eq workspaceId }
        }
        log.debug("Deleted all run records for workspace '{}'", workspaceId)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun toRecord(row: ResultRow): AgentRunRecord = AgentRunRecord(
        id = row[AgentRunHistoryTable.id],
        workspaceId = row[AgentRunHistoryTable.workspaceId],
        conversationId = row[AgentRunHistoryTable.conversationId],
        title = row[AgentRunHistoryTable.title],
        userInput = row[AgentRunHistoryTable.userInput],
        response = row[AgentRunHistoryTable.response],
        error = row[AgentRunHistoryTable.error],
        agentSessionId = row[AgentRunHistoryTable.agentSessionId],
        activityLog = decodeLog(row[AgentRunHistoryTable.activityLog]),
        contentBlocks = decodeContentBlocks(row[AgentRunHistoryTable.contentJson]),
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

    private fun decodeContentBlocks(raw: String?): List<TurnTimelineEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<TurnTimelineEntry>>(raw) }
            .onFailure { e -> log.warn("Failed to decode content_json: {}", e.message) }
            .getOrDefault(emptyList())
    }
}
