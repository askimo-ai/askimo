/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

/**
 * Parses a single stream-json event line emitted by the Cursor CLI and converts it
 * into structured data.
 *
 * Cursor emits JSON objects with the following structure:
 * ```json
 * {
 *   "type": "assistant",
 *   "message": {
 *     "role": "assistant",
 *     "content": [
 *       {
 *         "type": "text",
 *         "text": " subject"
 *       }
 *     ]
 *   },
 *   "session_id": "...",
 *   "timestamp_ms": 1234567890
 * }
 * ```
 *
 * The parser extracts the text content from nested `message.content[].text` fields
 * and event metadata like `type` and `status`.
 */
object CursorStreamJsonEventParser {

    /**
     * Parsed representation of a Cursor stream-json event.
     *
     * @property type       The event `type` field (e.g. `"thinking"`, `"assistant"`, `"result"`).
     * @property content    The text content to stream (empty string if no text in this event).
     * @property subtype    The optional `subtype` field (e.g. `"delta"`, `"success"`).
     * @property isError    Whether the result contains an error (from `is_error` field).
     * @property fields     All parsed fields for future extensibility.
     */
    data class StreamJsonEvent(
        val type: String,
        val content: String,
        val subtype: String? = null,
        val isError: Boolean = false,
        val fields: Map<String, Any>,
    )

    /**
     * Parses [line] into a [StreamJsonEvent].
     * Returns `null` if the line is blank or cannot be parsed as a JSON object with a `type` field.
     *
     * Extracts:
     * - Text from `message.content[0].text` (for assistant events) or `text` field (for thinking events)
     * - The `subtype` field if present
     * - The `is_error` boolean flag (for result events)
     */
    fun parse(line: String): StreamJsonEvent? {
        if (line.isBlank() || !line.trimStart().startsWith("{")) return null
        val fields = GeminiStreamJsonEventParser.parseObject(line.trim()) ?: return null
        val type = fields["type"] as? String ?: return null

        // Extract subtype if present
        val subtype = fields["subtype"] as? String

        // Extract is_error flag for result events
        val isError = fields["is_error"] as? Boolean ?: false

        // Extract text content — varies by event type
        var content = ""
        when (type) {
            "thinking" -> {
                // Thinking events have text at top level
                content = (fields["text"] as? String) ?: ""
            }

            "assistant" -> {
                // Assistant events have text nested in message.content[].text
                @Suppress("UNCHECKED_CAST")
                val message = fields["message"] as? Map<String, Any>
                if (message != null) {
                    content = extractAssistantText(message)
                }
            }

            "result" -> {
                // Result events may have a result field, but we ignore it since content already streamed
                // (unless is_error=true, then show the error)
                if (isError) {
                    content = (fields["error"] as? String) ?: (fields["result"] as? String) ?: ""
                }
            }
        }

        return StreamJsonEvent(
            type = type,
            content = content,
            subtype = subtype,
            isError = isError,
            fields = fields,
        )
    }

    private fun extractAssistantText(message: Map<String, Any>): String {
        val rawContent = message["content"] ?: return ""

        // If content is already decoded as a list of maps, collect text blocks.
        if (rawContent is List<*>) {
            return rawContent.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val type = map["type"] as? String ?: return@mapNotNull null
                if (type == "text") map["text"] as? String else null
            }.joinToString(separator = "")
        }

        // Our shared minimal parser can return arrays as raw JSON strings.
        val rawArray = rawContent as? String ?: return ""
        return extractTextFromRawContentArray(rawArray)
    }

    private fun extractTextFromRawContentArray(rawArray: String): String {
        val source = rawArray.trim()
        if (!source.startsWith("[") || !source.endsWith("]")) return ""

        val parts = mutableListOf<String>()
        var i = 0
        while (i < source.length) {
            if (source[i] == '{') {
                val end = findMatchingBrace(source, i) ?: break
                val obj = GeminiStreamJsonEventParser.parseObject(source.substring(i, end + 1))
                if (obj != null && (obj["type"] as? String) == "text") {
                    val text = obj["text"] as? String
                    if (!text.isNullOrEmpty()) parts += text
                }
                i = end + 1
            } else {
                i++
            }
        }
        return parts.joinToString(separator = "")
    }

    private fun findMatchingBrace(source: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var i = start
        while (i < source.length) {
            val ch = source[i]
            if (inString) {
                if (ch == '\\') {
                    i++
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true

                    '{' -> depth++

                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Renders [event] as a human-readable status string for non-content events.
     *
     * - For `thinking` events: shows the thinking text
     * - For `result` events: shows success/error status with duration
     * - For others: shows event type and metadata
     */
    fun renderStatus(event: StreamJsonEvent): String = buildString {
        when (event.type) {
            "thinking" -> {
                append("💭 Thinking: ")
                if (event.content.isNotBlank()) {
                    append(event.content.take(60))
                    if (event.content.length > 60) append("…")
                } else {
                    append("processing")
                }
            }

            "result" -> {
                if (event.isError) {
                    append("❌ Error: ")
                    if (event.content.isNotBlank()) {
                        append(event.content.take(80))
                    } else {
                        append("Unknown error")
                    }
                } else {
                    append("✓ Complete")
                    val durationMs = event.fields["duration_ms"]
                    if (durationMs != null) {
                        val secs = (durationMs.toString().toDoubleOrNull() ?: 0.0) / 1000.0
                        append(" | ${"%.1f".format(secs)}s")
                    }
                }
            }

            else -> {
                append("cursor: ")
                append(event.type)
                if (event.subtype != null) {
                    append(" (${event.subtype})")
                }
                val sessionId = event.fields["session_id"] as? String
                if (sessionId != null) {
                    append(" | session: ${sessionId.take(8)}")
                }
            }
        }
    }
}
