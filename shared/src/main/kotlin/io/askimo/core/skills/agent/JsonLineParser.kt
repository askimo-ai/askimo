/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Parses a single `stream-json` event line into the flat `Map<String, Any>` shape expected by
 * the various agent event parsers ([AntigravityStreamJsonEventParser], [ClaudeStreamJsonEventParser],
 * [CursorStreamJsonEventParser]).
 *
 * Backed by `kotlinx.serialization.json` (already a `shared` dependency) instead of a hand-rolled
 * scanner, so escaping, numbers, and nesting are handled by a real, well-tested JSON parser. Kept
 * as its own object — independent of any single agent parser — so none of them need to depend on
 * one another for basic JSON parsing.
 *
 * Value conversion, to preserve the shape callers already expect:
 * - JSON string  → [String] (unescaped)
 * - JSON boolean → [Boolean]
 * - JSON object  → nested `Map<String, Any>`
 * - JSON array   → raw compact JSON text (`String`) — callers that care about array contents
 *                   re-parse it via [parseArray]
 * - JSON number / null → raw literal text (`String`), matching the original parser's behavior
 */
internal object JsonLineParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a JSON object string `{...}` into a `Map<String, Any>`.
     * Returns `null` on parse failure or if the input is not an object.
     */
    fun parseObject(source: String): Map<String, Any>? {
        val s = source.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        val element = runCatching { json.parseToJsonElement(s) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null
        return obj.entries.associate { (key, value) -> key to convert(value) }
    }

    /**
     * Parses a JSON array string `[...]` into a `List<Any>` (see [convert] for element shapes).
     * Returns an empty list on parse failure or if the input is not an array.
     */
    fun parseArray(source: String): List<Any> {
        val s = source.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return emptyList()
        val element = runCatching { json.parseToJsonElement(s) }.getOrNull() ?: return emptyList()
        val arr = element as? JsonArray ?: return emptyList()
        return arr.map { convert(it) }
    }

    private fun convert(value: JsonElement): Any = when (value) {
        is JsonNull -> "null"
        is JsonArray -> value.toString()
        is JsonObject -> value.entries.associate { (key, v) -> key to convert(v) }
        is JsonPrimitive -> value.booleanOrNull ?: value.content
    }
}
