/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.domain

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
 * @param activityLog Ordered list of agent status/tool events emitted during the run.
 * @param createdAt   When this run was recorded.
 */
data class SkillRunRecord(
    val id: String = UUID.randomUUID().toString(),
    val skillPath: String,
    val userInput: String,
    val response: String,
    val error: String?,
    val activityLog: List<String>,
    val createdAt: Instant = Instant.now(),
)

/**
 * Exposed table definition for skill_run_history.
 *
 * [activityLog] is stored as a newline-delimited text block — no JSON dependency needed.
 */
object SkillRunHistoryTable : Table("skill_run_history") {
    val id = varchar("id", 36)
    val skillPath = text("skill_path")
    val userInput = text("user_input").default("")
    val response = text("response").default("")
    val error = text("error").nullable()

    /** Newline-delimited activity log entries. */
    val activityLog = text("activity_log").default("")

    val createdAt = sqliteInstant("created_at")

    override val primaryKey = PrimaryKey(id)
}
