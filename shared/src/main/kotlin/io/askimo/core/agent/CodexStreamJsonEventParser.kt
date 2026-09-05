/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

/**
 * Parses a single JSONL event line emitted by `codex exec --json` (OpenAI Codex CLI).
 *
 * Codex's `--json` protocol is a flat envelope — `{"type":"<name>", ...fields}` — unlike the
 * nested `msg`-keyed envelope this parser originally assumed. Confirmed shapes seen from a real
 * run (each line below is its own independent JSONL record, not a single JSON document):
 * - `{"type":"thread.started","thread_id":"01a07306-52ef-78d0-9829-6e70e75aca83"}`
 * - `{"type":"turn.started"}`
 * - `{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"..."}}`
 * - `{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"...","aggregated_output":"","exit_code":null,"status":"in_progress"}}`
 * - `{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"...","aggregated_output":"...","exit_code":0,"status":"completed"}}`
 *
 * `item.started`/`item.completed` events wrap an `item` object whose own `type` field
 * (`agent_message`, `command_execution`, and — per Codex's protocol, unconfirmed against a real
 * run — `reasoning`, `mcp_tool_call`) discriminates what happened; see [item] to extract it.
 *
 * `turn.completed`/`turn.failed`/top-level `error` are not yet confirmed against a real run
 * (the captured log ended right after the final `agent_message`) but are handled defensively
 * with the same field names as the rest of Codex's protocol (`usage`, `error.message`).
 */
object CodexStreamJsonEventParser {

    fun parse(line: String): StreamJsonEvent? {
        if (line.isBlank() || !line.trimStart().startsWith("{")) return null
        val fields = JsonLineParser.parseObject(line.trim()) ?: return null
        val type = fields["type"] as? String ?: return null
        return StreamJsonEvent(type = type, fields = fields.filterKeys { it != "type" })
    }

    /**
     * Extracts the nested `item` object from an `item.started`/`item.completed` event's fields.
     * The item's own `type` (e.g. `"agent_message"`, `"command_execution"`) discriminates what
     * kind of item it is — see callers in [CodexAgent.parseStdoutLine].
     */
    @Suppress("UNCHECKED_CAST")
    fun item(fields: Map<String, Any>): Map<String, Any>? = fields["item"] as? Map<String, Any>
}
