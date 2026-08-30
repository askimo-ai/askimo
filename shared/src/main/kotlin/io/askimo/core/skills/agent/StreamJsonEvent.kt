/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

/**
 * Parsed representation of a single `stream-json` event line, shared by the
 * [AntigravityStreamJsonEventParser] and [ClaudeStreamJsonEventParser] parsers
 * (both agents' envelopes flatten down to the same shape).
 *
 * @property type   The event name — from `type` (Claude-style envelopes) or `event`
 *                   (agy's envelope), e.g. `"init"`, `"step_update"`, `"result"`.
 * @property fields All fields for this event, with any nested `<type>` payload object
 *                   flattened together with top-level extras (e.g. `conversation_id`),
 *                   excluding fields the parser chooses to omit.
 */
data class StreamJsonEvent(
    val type: String,
    val fields: Map<String, Any>, // String | Boolean | Map<String,Any> (nested object)
)
