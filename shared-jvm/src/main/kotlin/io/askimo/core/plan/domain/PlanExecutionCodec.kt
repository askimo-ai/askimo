/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

import io.askimo.core.util.JsonUtils.json

/**
 * Shared TEXT encoding for [PlanExecution.inputs] and [PlanExecution.stepOutputs].
 *
 * Any code that persists or transmits a [PlanExecution] as opaque TEXT — such as
 * [io.askimo.core.plan.repository.PlanExecutionRepository]'s local SQLDelight storage — must use
 * this exact same encoding. Keeping a single shared implementation avoids independent call sites
 * drifting out of sync with each other.
 */
object PlanExecutionCodec {
    /** Encodes [inputs] as a JSON object. */
    fun encodeInputs(inputs: Map<String, String>): String = json.encodeToString(inputs)

    /** Decodes the JSON object produced by [encodeInputs], tolerating the legacy `key=value` format. */
    fun decodeInputs(raw: String): Map<String, String> {
        if (raw.isBlank() || raw == "{}") return emptyMap()

        // New format: JSON object.
        runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrNull()?.let { return it }

        // Legacy format: one "key=value" entry per line, predating the switch to JSON. Rows
        // written by the old encoder still exist in local databases that have only run
        // migration 69 (which adds synced_at but does not rewrite inputs), so this must stay
        // until those rows are rewritten.
        return raw.lines()
            .filter { it.contains('=') }
            .associate { line ->
                val idx = line.indexOf('=')
                val key = line.substring(0, idx)
                val value = line.substring(idx + 1).replace("\\n", "\n")
                key to value
            }
    }

    /** Encodes structured step outputs as a JSON array; `null` when empty. */
    fun encodeStepOutputs(steps: List<PlanStepOutput>): String? {
        if (steps.isEmpty()) return null
        return json.encodeToString(steps)
    }

    /** Decodes the JSON array produced by [encodeStepOutputs]. */
    fun decodeStepOutputs(raw: String?): List<PlanStepOutput> {
        if (raw.isNullOrBlank()) return emptyList()
        return json.decodeFromString(raw)
    }
}
