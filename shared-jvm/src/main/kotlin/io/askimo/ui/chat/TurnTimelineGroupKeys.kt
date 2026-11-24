/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import io.askimo.core.chat.dto.TurnTimelineGroup

/**
 * A content-derived identity for a [TurnTimelineGroup]:
 *
 * - [TurnTimelineGroup.StatusGroup] — `"status:"` + entry texts joined with `|`.
 * - [TurnTimelineGroup.ToolGroup] — `"tool:"` + entry tool names joined with `|` (arguments/
 *   result/status excluded since those mutate as a call goes from running to done).
 * - [TurnTimelineGroup.ThinkingGroup] / [TurnTimelineGroup.TokenGroup] — `"thinking:"`/`"token:"`
 *   + a compact digest of the accumulated text (see [textDigest]).
 *
 * Used as the base for a list-rendering `key` via [renderKey], which substitutes a fixed marker
 * for the streaming group and appends an occurrence suffix to break ties.
 *
 * Shared between desktop (`turnTimelineView` in `desktop-shared`) and Android's own turn-timeline
 * rendering, since both need stable keys for list-item identity/state preservation.
 */
fun TurnTimelineGroup.stableKey(): String = when (this) {
    is TurnTimelineGroup.StatusGroup -> "status:" + entries.joinToString("|") { it.text }
    is TurnTimelineGroup.ToolGroup -> "tool:" + entries.joinToString("|") { it.toolCall.toolName }
    is TurnTimelineGroup.ThinkingGroup -> "thinking:" + text.textDigest()
    is TurnTimelineGroup.TokenGroup -> "token:" + text.textDigest()
}

/**
 * Compact, non-copying identity for a (potentially large) accumulated text, used by
 * [stableKey] instead of the full text: length + [String.hashCode], as `"<length>:<hashCode>"`.
 */
private fun String.textDigest(): String = "$length:${hashCode()}"

/**
 * Computes the rendering key to use for a group in a turn-timeline list.
 *
 * - If [isStreamingTail], uses a fixed content-independent marker (`"live:thinking"`/
 *   `"live:token"`/`"live:status"`/`"live:tool"`) instead of [stableKey]. This matters most for
 *   [TurnTimelineGroup.ToolGroup]: its [stableKey] grows with each new tool call, so using it
 *   directly while streaming would remount the composable (losing expand/scroll state) on every
 *   new call. Only one group is ever the streaming tail, so this marker never collides.
 * - Otherwise, the base key is [stableKey].
 * - [occurrence] (count of prior groups with the same base key) is appended as `"#<occurrence>"`
 *   when non-zero, so duplicate content still gets unique keys.
 */
fun TurnTimelineGroup.renderKey(isStreamingTail: Boolean, occurrence: Int): String {
    val base = if (isStreamingTail) {
        when (this) {
            is TurnTimelineGroup.ThinkingGroup -> "live:thinking"
            is TurnTimelineGroup.TokenGroup -> "live:token"
            is TurnTimelineGroup.StatusGroup -> "live:status"
            is TurnTimelineGroup.ToolGroup -> "live:tool"
        }
    } else {
        stableKey()
    }
    return if (occurrence > 0) "$base#$occurrence" else base
}
