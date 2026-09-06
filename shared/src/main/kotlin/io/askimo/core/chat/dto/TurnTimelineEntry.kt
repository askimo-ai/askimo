/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import kotlinx.serialization.Serializable

/**
 * A single chronologically-ordered event captured during one AI response turn — a real
 * tool invocation, a chunk of visible reasoning ("thinking"), a chunk of the final response
 * text, or a non-tool lifecycle status update.
 *
 * Used by BOTH agentic runs ([io.askimo.core.agent.domain.AgentRunRecord]) and regular chat
 * turns ([io.askimo.core.chat.domain.ChatMessage]) — not agent-specific despite this file's
 * historical name, since both flows stream the same kind of ordered tool/thinking/text events.
 *
 * Kept in arrival order so the UI can render exactly what happened, when it happened, instead
 * of bucketing everything into fixed thinking/tools/text sections regardless of when each
 * actually occurred (e.g. tool call → some text → another tool call → more text).
 *
 * `@Serializable` so [Tool]/[Token] entries (never [Thinking]/[Status]) can be persisted as
 * JSON in [io.askimo.core.agent.domain.AgentRunRecord.contentBlocks] and
 * [io.askimo.core.chat.domain.ChatMessage.contentBlocks].
 */
@Serializable
sealed interface TurnTimelineEntry {
    @Serializable
    data class Status(val text: String) : TurnTimelineEntry

    @Serializable
    data class Tool(val toolCall: ToolCallInfo) : TurnTimelineEntry

    @Serializable
    data class Thinking(val text: String) : TurnTimelineEntry

    @Serializable
    data class Token(val text: String) : TurnTimelineEntry
}

/**
 * A run of one or more consecutive [TurnTimelineEntry]s of the same kind, collapsed into a
 * single group for rendering — e.g. three tool calls in a row become one collapsible
 * "3 tool calls" group instead of three separate rows.
 */
sealed interface TurnTimelineGroup {
    data class StatusGroup(val entries: List<TurnTimelineEntry.Status>) : TurnTimelineGroup
    data class ToolGroup(val entries: List<TurnTimelineEntry.Tool>) : TurnTimelineGroup
    data class ThinkingGroup(val text: String) : TurnTimelineGroup
    data class TokenGroup(val text: String) : TurnTimelineGroup
}

/**
 * Collapses a "retry loop" — the AI calling the same tool repeatedly (often with different
 * args) until it succeeds — down to the effective attempts, keyed by [ToolCallInfo.toolName]
 * + [ToolCallInfo.hasFailed]. An earlier call is dropped only if it **failed** and a later
 * call to the same tool exists; successful calls and the last attempt per tool are always
 * kept (so an always-failing tool still surfaces its final error).
 *
 * Not keyed on [ToolCallInfo.arguments]: distinct successful calls to the same tool (e.g.
 * `view_file(fileA)` then `view_file(fileB)`) must both stay visible, while real retries often
 * vary their args on every failed attempt anyway.
 *
 * [TurnTimelineEntry.Thinking] preceding a dropped attempt is discarded with it; thinking
 * before a *kept* attempt is preserved.
 *
 * E.g. `Thinking, ToolA(args1, FAILED), Thinking, ToolA(args2, FAILED), Thinking, ToolA(args3),
 * ToolB` → `Thinking, ToolA(args3), ToolB`.
 *
 * Applied before persisting (so `content_json` only stores effective tool calls) and again in
 * [grouped] when rendering, to also clean up historical rows saved before this existed.
 */
fun List<TurnTimelineEntry>.collapsedEffectiveTools(): List<TurnTimelineEntry> {
    val lastToolIndexByName = HashMap<String, Int>()
    forEachIndexed { index, entry ->
        if (entry is TurnTimelineEntry.Tool) {
            lastToolIndexByName[entry.toolCall.toolName] = index
        }
    }

    val result = ArrayList<TurnTimelineEntry>(size)
    val pendingThinking = mutableListOf<TurnTimelineEntry.Thinking>()

    forEachIndexed { index, entry ->
        when (entry) {
            is TurnTimelineEntry.Thinking -> {
                // Buffered — only kept if it turns out to precede a *kept* tool call.
                pendingThinking.add(entry)
            }

            is TurnTimelineEntry.Tool -> {
                val isLastForTool = lastToolIndexByName[entry.toolCall.toolName] == index
                // Keep every call that succeeded, plus the last attempt for a tool regardless
                // of outcome. Only drop a call when it failed AND a later attempt at the same
                // tool exists — see the function doc for why this (not arguments) is the
                // correct dedup signal.
                if (isLastForTool || !entry.toolCall.hasFailed) {
                    result.addAll(pendingThinking)
                    pendingThinking.clear()
                    result.add(entry)
                } else {
                    // A failed, superseded attempt — discard the reasoning that led to this
                    // dead-end attempt along with the attempt itself.
                    pendingThinking.clear()
                }
            }

            else -> {
                // Status/Token entries are never part of a "retry loop" — flush any pending
                // reasoning first (it wasn't followed by a tool call, e.g. the AI just thought
                // out loud), then pass the entry through untouched.
                result.addAll(pendingThinking)
                pendingThinking.clear()
                result.add(entry)
            }
        }
    }
    result.addAll(pendingThinking)
    return result
}

/**
 * Collapses consecutive same-kind entries into groups, preserving overall chronological order.
 * Repeated tool retries are first collapsed to their last effective call — see
 * [collapsedEffectiveTools].
 */
fun List<TurnTimelineEntry>.grouped(): List<TurnTimelineGroup> {
    val entries = this.collapsedEffectiveTools()
    val groups = mutableListOf<TurnTimelineGroup>()
    var i = 0
    while (i < entries.size) {
        when (entries[i]) {
            is TurnTimelineEntry.Status -> {
                var j = i
                val run = mutableListOf<TurnTimelineEntry.Status>()
                while (j < entries.size) {
                    val e = entries[j] as? TurnTimelineEntry.Status ?: break
                    run.add(e)
                    j++
                }
                groups.add(TurnTimelineGroup.StatusGroup(run))
                i = j
            }

            is TurnTimelineEntry.Tool -> {
                var j = i
                val run = mutableListOf<TurnTimelineEntry.Tool>()
                while (j < entries.size) {
                    val e = entries[j] as? TurnTimelineEntry.Tool ?: break
                    run.add(e)
                    j++
                }
                groups.add(TurnTimelineGroup.ToolGroup(run))
                i = j
            }

            is TurnTimelineEntry.Thinking -> {
                var j = i
                val text = StringBuilder()
                while (j < entries.size) {
                    val e = entries[j] as? TurnTimelineEntry.Thinking ?: break
                    text.append(e.text)
                    j++
                }
                groups.add(TurnTimelineGroup.ThinkingGroup(text.toString()))
                i = j
            }

            is TurnTimelineEntry.Token -> {
                var j = i
                val text = StringBuilder()
                while (j < entries.size) {
                    val e = entries[j] as? TurnTimelineEntry.Token ?: break
                    text.append(e.text)
                    j++
                }
                groups.add(TurnTimelineGroup.TokenGroup(text.toString()))
                i = j
            }
        }
    }
    return groups
}
