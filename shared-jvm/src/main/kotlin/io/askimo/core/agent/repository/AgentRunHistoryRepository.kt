/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.truncatedForStorage
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Agent_run_history
import io.askimo.core.logging.Logger
import io.askimo.core.logging.logger
import kotlinx.serialization.json.Json

/**
 * Maps a generated [Agent_run_history] row to the shared [AgentRunRecord] domain object.
 */
private fun Agent_run_history.toAgentRunRecord(json: Json, log: Logger): AgentRunRecord = AgentRunRecord(
    id = id,
    workspaceId = workspace_id,
    conversationId = conversation_id,
    title = title,
    userInput = user_input,
    response = response,
    error = error,
    isCancelled = is_cancelled == 1L,
    agentId = agent_id,
    agentSessionId = agent_session_id,
    activityLog = decodeLog(activity_log),
    contentBlocks = decodeContentBlocks(content_json, json, log),
    inputTokens = input_tokens?.toInt(),
    outputTokens = output_tokens?.toInt(),
    totalTokens = total_tokens?.toInt(),
    durationMs = duration_ms,
    createdAt = created_at,
)

private fun encodeLog(entries: List<String>): String = entries.joinToString("\n") { it.replace("\n", "\\n") }

private fun decodeLog(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.lines().map { it.replace("\\n", "\n") }
}

private fun decodeContentBlocks(raw: String?, json: Json, log: Logger): List<TurnTimelineEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<TurnTimelineEntry>>(raw) }
        .onFailure { e -> log.warn("Failed to decode content_json: {}", e.message) }
        .getOrDefault(emptyList())
}

/**
 * Repository for persisting and querying [AgentRunRecord] entries.
 *
 * [AgentRunRecord.activityLog] is stored as a newline-delimited text block so the
 * SQLite file stays human-readable without requiring a JSON library.
 */
class AgentRunHistoryRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val log = logger<AgentRunHistoryRepository>()
    private val json = Json { ignoreUnknownKeys = true }

    private val queries get() = db.agentRunHistoryQueries

    /**
     * Persists a new run record. The [record.id] must already be set (UUID).
     */
    fun save(record: AgentRunRecord) {
        queries.insertHistory(
            id = record.id,
            workspaceId = record.workspaceId,
            conversationId = record.conversationId,
            title = record.title,
            userInput = record.userInput,
            response = record.response,
            error = record.error,
            isCancelled = if (record.isCancelled) 1L else 0L,
            agentId = record.agentId,
            agentSessionId = record.agentSessionId,
            activityLog = encodeLog(record.activityLog),
            contentJson = if (record.contentBlocks.isEmpty()) {
                null
            } else {
                json.encodeToString(record.contentBlocks.truncatedForStorage())
            },
            inputTokens = record.inputTokens?.toLong(),
            outputTokens = record.outputTokens?.toLong(),
            totalTokens = record.totalTokens?.toLong(),
            durationMs = record.durationMs,
            createdAt = record.createdAt,
        )
        log.debug("Saved skill run record '{}' for workspace '{}'", record.id, record.workspaceId)
    }

    /**
     * Returns the total number of agent run records using a SQL COUNT(*) query.
     */
    fun countAll(): Int = queries.countAll().executeAsOne().toInt()

    /**
     * Returns up to [limit] run records across all skills, newest first.
     */
    fun findAll(limit: Int = 200): List<AgentRunRecord> = queries.selectAll(limit.toLong()).executeAsList().map { it.toAgentRunRecord(json, log) }

    /**
     * Returns up to [limit] run records for the given [workspaceId], newest first.
     */
    fun findByWorkspaceId(workspaceId: String, limit: Int = 200): List<AgentRunRecord> = queries.selectByWorkspaceId(workspaceId, limit.toLong()).executeAsList().map { it.toAgentRunRecord(json, log) }

    /**
     * Returns every turn belonging to [conversationId], oldest first — used to
     * reconstruct the full multi-turn thread when reopening a history entry.
     * Turns are strictly serialized when created (a new turn can't start until the
     * previous one finishes and is saved), so ordering by `created_at`
     * alone is reliable here.
     */
    fun findByConversationId(conversationId: String): List<AgentRunRecord> = queries.selectByConversationId(conversationId).executeAsList().map { it.toAgentRunRecord(json, log) }

    /**
     * Updates the [AgentRunRecord.title] on every turn belonging to [conversationId] — call
     * this once the async AI-generated title is ready, so the whole conversation (and the
     * single row representing it in the history list) reflects the new title without any
     * join/lookup needed at read time.
     */
    fun updateTitleForConversation(conversationId: String, newTitle: String) {
        queries.updateTitleForConversation(title = newTitle, conversationId = conversationId)
        log.debug("Updated title for conversation '{}'", conversationId)
    }

    /**
     * Deletes a single run record by [id].
     */
    fun deleteById(id: String) {
        queries.deleteById(id)
        log.debug("Deleted skill run record '{}'", id)
    }

    /**
     * Deletes every turn belonging to [conversationId] — use this instead of [deleteById]
     * when removing a conversation from the history list, so the whole thread is removed
     * rather than just its most recent turn.
     */
    fun deleteByConversationId(conversationId: String) {
        queries.deleteByConversationId(conversationId)
        log.debug("Deleted all run records for conversation '{}'", conversationId)
    }

    /**
     * Deletes all run records belonging to the given [workspaceId] — call this when a
     * [io.askimo.core.agent.domain.Workspace] is removed, to avoid orphaned run history.
     */
    fun deleteByWorkspaceId(workspaceId: String) {
        queries.deleteByWorkspaceId(workspaceId)
        log.debug("Deleted all run records for workspace '{}'", workspaceId)
    }
}
