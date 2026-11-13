/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

import io.askimo.core.util.JsonUtils.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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

    /** Decodes the JSON object produced by [encodeInputs]. */
    fun decodeInputs(raw: String): Map<String, String> {
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return json.decodeFromString(raw)
    }

    /** Encodes structured step outputs as a JSON array; `null` when empty. */
    fun encodeStepOutputs(steps: List<PlanStepOutput>): String? {
        if (steps.isEmpty()) return null
        return json.encodeToString(steps)
    }

    /** Decodes the JSON array produced by [encodeStepOutputs], tolerating legacy formats. */
    fun decodeStepOutputs(raw: String?): List<PlanStepOutput> {
        if (raw.isNullOrBlank()) return emptyList()

        // New format: JSON array of PlanStepOutput.
        runCatching {
            json.decodeFromString<List<PlanStepOutput>>(raw)
        }.getOrNull()?.let { return it }

        // Compatibility format: JSON with legacy keys {"step":"...","output":"..."}.
        val legacySteps = runCatching {
            val element = json.parseToJsonElement(raw)
            if (element !is JsonArray) return@runCatching null
            element.jsonArray.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val stepName = obj["stepName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["step"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val output = obj["output"]?.jsonPrimitive?.contentOrNull ?: ""
                PlanStepOutput(
                    stepName = stepName,
                    output = output,
                    inputTokens = obj["inputTokens"]?.jsonPrimitive?.intOrNull,
                    outputTokens = obj["outputTokens"]?.jsonPrimitive?.intOrNull,
                    totalTokens = obj["totalTokens"]?.jsonPrimitive?.intOrNull,
                    durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull,
                )
            }
        }.getOrNull()
        if (legacySteps != null) return legacySteps

        // Legacy line format: stepName|output
        return raw.lines().mapNotNull { line ->
            val sep = line.indexOf('|')
            if (sep < 0) return@mapNotNull null
            PlanStepOutput(
                stepName = line.substring(0, sep),
                output = line.substring(sep + 1).replace("\\n", "\n"),
            )
        }
    }
}
