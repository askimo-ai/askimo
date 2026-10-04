/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

/**
 * Best-effort token usage / duration metadata captured from an external CLI agent's
 * own stream-json output for a single run.
 *
 * All fields are nullable: agents that don't expose structured usage (e.g. Codex today)
 * simply never report it, and the UI hides the metadata row entirely when [totalTokens]
 * is null or zero.
 */
data class AgentUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
) {
    /** True if there is nothing meaningful to show. */
    fun isEmpty(): Boolean = inputTokens == null && outputTokens == null && totalTokens == null && durationMs == null
}

/**
 * Generic, defensive extractor for token usage / duration fields out of a stream-json
 * event's parsed field map.
 *
 * Different CLI agents (Claude Code, Cursor, Antigravity, Codex, ...) each use slightly
 * different JSON shapes for reporting usage — some nest it under a `"usage"` map, some
 * put duration at the top level, and key names vary (`input_tokens` vs `prompt_tokens`,
 * `duration_ms` vs `duration_seconds`, etc). Rather than hard-coding one exact shape per
 * agent (which would silently break the moment a CLI's output format shifts), this walks
 * a list of known key aliases and returns whatever it can confidently find, leaving the
 * rest `null`.
 *
 * Call with the top-level parsed event fields (e.g. a `"result"` event's fields) and,
 * if this agent nests usage under a sub-key (e.g. `fields["usage"]`), that nested map too.
 */
object AgentUsageExtractor {

    private val INPUT_TOKEN_KEYS = listOf(
        "input_tokens",
        "inputTokens",
        "prompt_tokens",
        "promptTokens",
        "cache_read_input_tokens",
    )
    private val OUTPUT_TOKEN_KEYS = listOf(
        "output_tokens",
        "outputTokens",
        "completion_tokens",
        "completionTokens",
    )
    private val TOTAL_TOKEN_KEYS = listOf(
        "total_tokens",
        "totalTokens",
    )
    private val DURATION_MS_KEYS = listOf(
        "duration_ms",
        "durationMs",
        "duration_api_ms",
    )
    private val DURATION_SECONDS_KEYS = listOf(
        "duration_seconds",
        "durationSeconds",
    )

    fun extract(fields: Map<String, Any>, usageMap: Map<String, Any>? = null): AgentUsage {
        // Prefer the nested usage map for token fields, falling back to the top-level fields.
        val input = firstNumber(usageMap, INPUT_TOKEN_KEYS) ?: firstNumber(fields, INPUT_TOKEN_KEYS)
        val output = firstNumber(usageMap, OUTPUT_TOKEN_KEYS) ?: firstNumber(fields, OUTPUT_TOKEN_KEYS)
        var total = firstNumber(usageMap, TOTAL_TOKEN_KEYS) ?: firstNumber(fields, TOTAL_TOKEN_KEYS)
        if (total == null && (input != null || output != null)) {
            total = (input ?: 0.0) + (output ?: 0.0)
        }

        val durationMs = firstNumber(fields, DURATION_MS_KEYS)?.toLong()
            ?: firstNumber(usageMap, DURATION_MS_KEYS)?.toLong()
            ?: firstNumber(fields, DURATION_SECONDS_KEYS)?.let { (it * 1000.0).toLong() }
            ?: firstNumber(usageMap, DURATION_SECONDS_KEYS)?.let { (it * 1000.0).toLong() }

        return AgentUsage(
            inputTokens = input?.toInt(),
            outputTokens = output?.toInt(),
            totalTokens = total?.toInt(),
            durationMs = durationMs,
        )
    }

    private fun firstNumber(map: Map<String, Any>?, keys: List<String>): Double? {
        if (map == null) return null
        for (key in keys) {
            val raw = map[key] ?: continue
            val num = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            if (num != null) return num
        }
        return null
    }
}
