/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant
import java.util.UUID

/**
 * Persisted record of one execution run of a skill via an external agent.
 *
 * @param id          Unique record identifier (UUID).
 * @param skillPath   The [SkillDefinition.relativePath] used as the grouping key.
 * @param userInput   The context/prompt entered by the user before executing.
 * @param response    The full AI-generated response text; empty if the run failed.
 * @param error       Error message if the run failed; null on success.
 * @param agentSessionId Optional external agent session identifier (if the runtime exposes one).
 * @param workspaceDir Optional runtime workspace directory used by the agent process.
 * @param activityLog Ordered list of agent status/tool events emitted during the run.
 * @param inputTokens  Best-effort input token count reported by the agent, if any.
 * @param outputTokens Best-effort output token count reported by the agent, if any.
 * @param totalTokens  Best-effort total token count reported by the agent, if any.
 * @param durationMs   Best-effort run duration (ms) reported by the agent itself, if any.
 * @param createdAt   When this run was recorded.
 */
data class SkillRunRecord(
    val id: String = UUID.randomUUID().toString(),
    val skillPath: String,
    val userInput: String,
    val response: String,
    val error: String?,
    val agentSessionId: String? = null,
    val workspaceDir: String? = null,
    val activityLog: List<String>,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
    val createdAt: Instant = Instant.now(),
)

/**
 * Exposed table definition for skill_run_history.
 *
 * [activityLog] is stored as a newline-delimited text block — no JSON dependency needed.
 * Token usage columns are nullable — older rows and agents that don't expose structured
 * usage (e.g. Codex today) simply have `null` here.
 */
object SkillRunHistoryTable : Table("skill_run_history") {
    val id = varchar("id", 36)
    val skillPath = text("skill_path")
    val userInput = text("user_input").default("")
    val response = text("response").default("")
    val error = text("error").nullable()
    val agentSessionId = text("agent_session_id").nullable()
    val workspaceDir = text("workspace_dir").nullable()

    /** Newline-delimited activity log entries. */
    val activityLog = text("activity_log").default("")

    val inputTokens = integer("input_tokens").nullable()
    val outputTokens = integer("output_tokens").nullable()
    val totalTokens = integer("total_tokens").nullable()
    val durationMs = long("duration_ms").nullable()

    val createdAt = sqliteInstant("created_at")

    override val primaryKey = PrimaryKey(id)
}
