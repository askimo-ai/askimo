/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.telemetry

import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import io.askimo.core.telemetry.LlmInstanceStats
import io.askimo.core.telemetry.LlmUsageRecord
import kotlin.time.Instant

/**
 *
 * All timestamp comparisons are performed in UTC. Timestamps are stored natively as
 * [kotlin.time.Instant] via [io.askimo.core.db.InstantColumnAdapter] — the `java.time.Instant`
 * values on this repository's public API are converted at the boundary.
 */
class LlmUsageRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val log = logger<LlmUsageRepository>()
    private val queries get() = db.llmUsageRecordsQueries

    fun insert(record: LlmUsageRecord) {
        queries.insertLlmUsageRecord(
            timestamp = record.timestamp,
            provider = record.provider,
            model = record.model,
            instanceId = record.instanceId,
            promptTokens = record.promptTokens.toLong(),
            outputTokens = record.outputTokens.toLong(),
            totalTokens = record.totalTokens.toLong(),
            durationMs = record.durationMs,
            isError = if (record.isError) 1L else 0L,
        )
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
    fun countByPeriod(from: Instant, to: Instant): Int = queries.countLlmUsageByPeriod(from, to).executeAsOne().toInt()

    /**
     * Returns per-instance aggregated stats within [[from], [to]).
     *
     * Groups by `COALESCE(instance_id, provider), model` and orders by total tokens descending,
     * so the highest-usage model appears first. One [LlmInstanceStats] row per unique combination.
     */
    fun queryGroupedByInstance(from: Instant, to: Instant): List<LlmInstanceStats> = queries.queryLlmUsageGroupedByInstance(from, to)
        .executeAsList()
        .map { row ->
            LlmInstanceStats(
                instanceKey = row.instance_key,
                provider = row.provider,
                model = row.model,
                calls = row.calls.toInt(),
                tokens = row.tokens,
                avgDurationMs = row.avg_duration_ms.toLong(),
                errors = row.errors.toInt(),
            )
        }
}
