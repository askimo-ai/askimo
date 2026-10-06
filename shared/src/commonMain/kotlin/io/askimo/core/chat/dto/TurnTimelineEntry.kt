/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import kotlinx.serialization.Serializable

/**
 * A single chronologically-ordered event captured during one AI response turn — a real tool
 * invocation, a chunk of visible reasoning ("thinking"), a chunk of the final response text,
 * or a non-tool lifecycle status update.
 *
 * Used by both agentic runs and regular chat turns — not agent-specific despite this file's
 * historical name, since both flows stream the same kind of ordered tool/thinking/text events.
 *
 * Kept in arrival order so the UI can render exactly what happened, when it happened, instead
 * of bucketing everything into fixed thinking/tools/text sections (e.g. tool call → some text
 * → another tool call → more text).
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
 * Appends [entry] to this list, collapsing consecutive duplicate [TurnTimelineEntry.Status]
 * updates into one — an agent's stream can legitimately emit the same ambient "still working"
 * status repeatedly with no new information (e.g. a burst of empty "thinking" events before
 * any real reasoning text arrives). Without this, every repeat would grow the timeline and
 * re-key the derived render group, restarting any elapsed-time UI tied to what is, from the
 * user's perspective, a single unbroken phase.
 *
 * Deliberately generic — keyed only on [TurnTimelineEntry.Status.text] equality, nothing
 * agent-specific — so it applies uniformly to every `ExternalAgent` without any needing its
 * own dedup logic. Non-Status entries (and a Status whose text changed) are always appended.
 */
fun List<TurnTimelineEntry>.appendDeduped(entry: TurnTimelineEntry): List<TurnTimelineEntry> {
    val last = lastOrNull()
    if (entry is TurnTimelineEntry.Status && last is TurnTimelineEntry.Status && last.text == entry.text) {
        return this
    }
    return this + entry
}

/**
 * Caps every [TurnTimelineEntry.Tool]'s [ToolCallInfo.arguments]/[ToolCallInfo.result] to
 * [ToolCallInfo.MAX_FIELD_LENGTH] — for use **only** right before persisting a turn (e.g.
 * [io.askimo.core.agent.repository.AgentRunHistoryRepository.save] /
 * [io.askimo.core.chat.repository.ChatMessageRepository]'s `encodeChatContentBlocks`).
 *
 * The live, in-session timeline deliberately keeps the full, untruncated content (a
 * `Write`/`Edit` tool's generated file content is often the AI's actual "response" for that
 * turn) — only what gets written to `content_json` needs a cap, to keep the database and any
 * future sync payload bounded. Status/Thinking/Token entries pass through unchanged (thinking
 * is never persisted at all — see [TurnTimelineEntry] doc — and token/status text isn't
 * expected to reach megabytes).
 */
fun List<TurnTimelineEntry>.truncatedForStorage(): List<TurnTimelineEntry> = map { entry ->
    if (entry is TurnTimelineEntry.Tool) {
        TurnTimelineEntry.Tool(
            ToolCallInfo.truncated(
                toolName = entry.toolCall.toolName,
                status = entry.toolCall.status,
                arguments = entry.toolCall.arguments,
                result = entry.toolCall.result,
                hasFailed = entry.toolCall.hasFailed,
                startedAtMillis = entry.toolCall.startedAtMillis,
            ),
        )
    } else {
        entry
    }
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
 * before a kept attempt is preserved.
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
                // Buffered — only kept if it precedes a *kept* tool call.
                pendingThinking.add(entry)
            }

            is TurnTimelineEntry.Tool -> {
                val isLastForTool = lastToolIndexByName[entry.toolCall.toolName] == index
                // Keep every successful call, plus the last attempt per tool regardless of
                // outcome. Drop a call only when it failed AND a later attempt exists — see
                // the function doc for why this (not arguments) is the correct dedup signal.
                if (isLastForTool || !entry.toolCall.hasFailed) {
                    result.addAll(pendingThinking)
                    pendingThinking.clear()
                    result.add(entry)
                } else {
                    // A failed, superseded attempt — discard the reasoning that led here too.
                    pendingThinking.clear()
                }
            }

            else -> {
                // Status/Token entries aren't part of a retry loop — flush any pending
                // reasoning first (it wasn't followed by a tool call), then pass through.
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
