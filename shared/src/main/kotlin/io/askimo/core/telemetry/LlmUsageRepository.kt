/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.telemetry

import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.SQLiteInstantColumnType
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/**
 * Repository for persisting and querying individual [LlmUsageRecord] rows.
 *
 * All timestamp comparisons are performed in UTC. The [LlmUsageRecordTable.timestamp]
 * column is indexed to keep range-query latency low even as the table grows.
 *
 * Range queries (anything involving "FROM … TO …") use JDBC string comparison directly,
 * because the [io.askimo.core.db.SQLiteInstantColumnType] stores instants as ISO-8601 UTC
 * text which is lexicographically ordered — string `>=` / `<` gives correct temporal order.
 */
class LlmUsageRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    private val log = logger<LlmUsageRepository>()

    fun insert(record: LlmUsageRecord) {
        transaction(database) {
            LlmUsageRecordTable.insert {
                it[timestamp] = record.timestamp
                it[provider] = record.provider
                it[model] = record.model
                it[instanceId] = record.instanceId
                it[promptTokens] = record.promptTokens
                it[outputTokens] = record.outputTokens
                it[totalTokens] = record.totalTokens
                it[durationMs] = record.durationMs
                it[isError] = if (record.isError) 1 else 0
            }
        }
        log.debug(
            "Inserted LLM usage record: provider={}, model={}, tokens={}, error={}",
            record.provider,
            record.model,
            record.totalTokens,
            record.isError,
        )
    }

    /**
     * Counts the number of calls (including errors) within [[from], [to]).
     */
    fun countByPeriod(from: Instant, to: Instant): Int {
        val fromStr = fmt(from)
        val toStr = fmt(to)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM llm_usage_records " +
                    "WHERE timestamp >= ? AND timestamp < ?",
            ).use { stmt ->
                stmt.setString(1, fromStr)
                stmt.setString(2, toStr)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        }
        return 0
    }

    /**
     * Returns per-instance aggregated stats within [[from], [to]).
     *
     * Groups by `COALESCE(instance_id, provider), model` and orders by total tokens descending,
     * so the highest-usage model appears first. One [LlmInstanceStats] row per unique combination.
     *
     * Uses JDBC directly for the same reason as other range queries — ISO-8601 string
     * comparison is correct and avoids Exposed custom-column-type limitations.
     */
    fun queryGroupedByInstance(from: Instant, to: Instant): List<LlmInstanceStats> {
        val fromStr = fmt(from)
        val toStr = fmt(to)
        val result = mutableListOf<LlmInstanceStats>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT " +
                    "  COALESCE(instance_id, provider) AS instance_key, " +
                    "  provider, " +
                    "  model, " +
                    "  COUNT(*) AS calls, " +
                    "  COALESCE(SUM(total_tokens), 0) AS tokens, " +
                    "  COALESCE(AVG(duration_ms), 0) AS avg_duration_ms, " +
                    "  SUM(CASE WHEN is_error = 1 THEN 1 ELSE 0 END) AS errors " +
                    "FROM llm_usage_records " +
                    "WHERE timestamp >= ? AND timestamp < ? " +
                    "GROUP BY COALESCE(instance_id, provider), model " +
                    "ORDER BY tokens DESC",
            ).use { stmt ->
                stmt.setString(1, fromStr)
                stmt.setString(2, toStr)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        result += LlmInstanceStats(
                            instanceKey = rs.getString("instance_key"),
                            provider = rs.getString("provider"),
                            model = rs.getString("model"),
                            calls = rs.getInt("calls"),
                            tokens = rs.getLong("tokens"),
                            avgDurationMs = rs.getLong("avg_duration_ms"),
                            errors = rs.getInt("errors"),
                        )
                    }
                }
            }
        }
        return result
    }

    /** Format an [Instant] to the ISO-8601 UTC string used by [io.askimo.core.db.SQLiteInstantColumnType]. */
    private fun fmt(instant: Instant): String = SQLiteInstantColumnType.FORMATTER.format(instant)

    /** Parse an ISO-8601 UTC string back to [Instant] (tolerant, delegates to [SQLiteInstantColumnType]). */
    private fun parseInstant(raw: String): Instant = Instant.parse(if (raw.endsWith('Z') || raw.contains('+')) raw else "${raw}Z")

    private fun ResultRow.toRecord() = LlmUsageRecord(
        id = this[LlmUsageRecordTable.id],
        timestamp = this[LlmUsageRecordTable.timestamp],
        provider = this[LlmUsageRecordTable.provider],
        model = this[LlmUsageRecordTable.model],
        instanceId = this[LlmUsageRecordTable.instanceId],
        promptTokens = this[LlmUsageRecordTable.promptTokens],
        outputTokens = this[LlmUsageRecordTable.outputTokens],
        totalTokens = this[LlmUsageRecordTable.totalTokens],
        durationMs = this[LlmUsageRecordTable.durationMs],
        isError = this[LlmUsageRecordTable.isError] != 0,
    )
}
