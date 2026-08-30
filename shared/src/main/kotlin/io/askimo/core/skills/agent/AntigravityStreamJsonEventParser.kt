/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

/**
 * Parses a single `stream-json` event line emitted by the Antigravity CLI (`agy`) and converts it
 * into structured [StreamJsonEvent] and a human-readable status string.
 *
 * `agy` envelopes each event as `{"event": "<name>", ...top-level extras..., "<name>": {...payload...}}`,
 * e.g.:
 * ```json
 * {"event":"init","conversation_id":"a968...","init":{"cwd":"...","tools":[...],"permission_mode":"always-proceed"}}
 * {"event":"step_update","step_update":{"conversation_id":"a968...","step_index":1,"state":"DONE","step_type":"agent_response","text_delta":"...","usage":{...}}}
 * {"event":"result","result":{"conversation_id":"a968...","status":"SUCCESS","response":"...","duration_seconds":1.13,"usage":{...}}}
 * ```
 * [parse] flattens the nested `<name>` payload (plus any top-level extras like `conversation_id`)
 * into a single [StreamJsonEvent.fields] map keyed by [StreamJsonEvent.type] = `<name>`, so callers
 * don't need to know whether a field came from the envelope or the nested payload.
 *
 * The parser is otherwise generic — it does not hard-code tool names or parameter keys.
 * Any unknown event type is still rendered in the hierarchy format via [render].
 *
 * Fields excluded from the status display:
 * - `timestamp` — noise, not user-relevant
 * - `role` — always "assistant", not useful
 * - `tool_id` / `tool_name` at the top level — `tool_name` is promoted to the header
 */
object AntigravityStreamJsonEventParser {

    /** Fields to skip entirely in the rendered output. */
    private val EXCLUDED_FIELDS = setOf("timestamp", "role", "tool_id", "type", "event")

    /**
     * Parses [line] into a [StreamJsonEvent].
     * Returns `null` if the line is blank or cannot be parsed as a JSON object with a
     * `type` or `event` field.
     */
    fun parse(line: String): StreamJsonEvent? {
        if (line.isBlank() || !line.trimStart().startsWith("{")) return null
        val fields = JsonLineParser.parseObject(line.trim()) ?: return null
        val type = (fields["type"] as? String) ?: (fields["event"] as? String) ?: return null

        // agy-style envelope: the real payload lives nested under a key matching `type`
        // (e.g. {"event":"step_update","step_update":{...}}). Flatten it together with any
        // other top-level extras (e.g. "conversation_id") so callers see one flat field map.
        @Suppress("UNCHECKED_CAST")
        val nestedPayload = fields[type] as? Map<String, Any>
        val merged = if (nestedPayload != null) {
            nestedPayload + fields.filterKeys { it != "type" && it != "event" && it != type }
        } else {
            fields
        }

        val remaining = merged.filterKeys { it !in EXCLUDED_FIELDS }
        return StreamJsonEvent(type = type, fields = remaining)
    }

    /**
     * Renders [event] as a compact human-readable multi-line string.
     *
     * Format example:
     * ```
     * tool_use
     *   write_file:
     *     content: package com.example…
     *     file_path: spring-boot-app/…
     * ```
     */
    fun render(event: StreamJsonEvent): String = buildString {
        // For tool_use: promote tool_name as a sub-header
        val toolName = event.fields["tool_name"] as? String
        if (event.type == "tool_use" && toolName != null) {
            append(event.type)
            append("\n  ")
            append(toolName)
            append(":")
            val params = event.fields["parameters"]
            if (params is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                appendFields(params as Map<String, Any>, indent = "    ")
            } else {
                // No parameters — render remaining fields
                val rest = event.fields.filterKeys { it != "tool_name" && it != "parameters" }
                appendFields(rest, indent = "    ")
            }
        } else {
            append(event.type)
            append(":")
            appendFields(event.fields, indent = "  ")
        }
    }

    private fun StringBuilder.appendFields(fields: Map<String, Any>, indent: String) {
        fields.forEach { (key, value) ->
            append("\n")
            append(indent)
            when (value) {
                is Map<*, *> -> {
                    append(key)
                    append(":")
                    @Suppress("UNCHECKED_CAST")
                    appendFields(value as Map<String, Any>, "$indent  ")
                }

                is String -> {
                    append(key)
                    append(": ")
                    // Truncate long values (e.g. file content) for display
                    val display = value.replace('\n', '↵').let {
                        if (it.length > 120) it.take(117) + "…" else it
                    }
                    append(display)
                }

                else -> {
                    append(key)
                    append(": ")
                    append(value)
                }
            }
        }
    }
}
