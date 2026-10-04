/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.telemetry

import java.time.Instant

/**
 * Persisted record of a single LLM call (success or error).
 *
 * @param id            Auto-generated primary key (AUTOINCREMENT, 0 = not yet persisted).
 * @param timestamp     When the call completed.
 * @param provider      Provider identifier (e.g. "openai", "anthropic").
 * @param model         Model name as reported by the provider.
 * @param instanceId    Optional instance key — "$instanceId:$model" composite used by TelemetryCollector.
 * @param promptTokens  Input token count (0 when unknown).
 * @param outputTokens  Output/completion token count (0 when unknown).
 * @param totalTokens   Combined token count (0 when unknown).
 * @param durationMs    Wall-clock duration of the call in milliseconds.
 * @param isError       true when the call ended with an error instead of a response.
 */
data class LlmUsageRecord(
    val id: Long = 0,
    val timestamp: Instant = Instant.now(),
    val provider: String,
    val model: String,
    val instanceId: String? = null,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val durationMs: Long = 0,
    val isError: Boolean = false,
)

/**
 * Aggregated LLM usage stats for a single instance+model combination within a time range.
 *
 * Returned by [LlmUsageRepository.queryGroupedByInstance] — one row per unique
 * `(COALESCE(instance_id, provider), model)` pair.
 *
 * @param instanceKey  The grouping key — `instanceId` when present, otherwise `provider`.
 * @param provider     Raw provider identifier (e.g. "openai").
 * @param model        Model name as reported by the provider.
 * @param calls        Total number of calls in the period (success + error).
 * @param tokens       Sum of [LlmUsageRecord.totalTokens] for all calls.
 * @param avgDurationMs  Average wall-clock duration across all calls (0 if no calls).
 * @param errors       Number of calls where [LlmUsageRecord.isError] = true.
 */
data class LlmInstanceStats(
    val instanceKey: String,
    val provider: String,
    val model: String,
    val calls: Int,
    val tokens: Long,
    val avgDurationMs: Long,
    val errors: Int,
)
