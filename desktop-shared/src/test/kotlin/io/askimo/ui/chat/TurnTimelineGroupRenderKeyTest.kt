/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import io.askimo.core.chat.dto.ToolCallInfo
import io.askimo.core.chat.dto.ToolCallStatus
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.TurnTimelineGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression coverage for [renderKey]/[stableKey] — specifically the bug where a live-streaming
 * [TurnTimelineGroup.ToolGroup] changed its Compose `key()` identity every time a new tool call
 * was appended to it, forcing a full remount (resetting expand/scroll state) instead of updating
 * in place. See [renderKey]'s doc for the full explanation.
 */
class TurnTimelineGroupRenderKeyTest {

    private fun toolGroup(vararg names: String) = TurnTimelineGroup.ToolGroup(
        names.map { TurnTimelineEntry.Tool(ToolCallInfo(toolName = it, status = ToolCallStatus.RUNNING)) },
    )

    @Test
    fun `ToolGroup key stays stable while streaming as new tool calls are appended`() {
        val afterFirstCall = toolGroup("read_file")
        val afterSecondCall = toolGroup("read_file", "write_file")
        val afterThirdCall = toolGroup("read_file", "write_file", "run_command")

        // Regression check: while still the live tail, identity must NOT change as the group
        // grows — otherwise Compose remounts the section (resetting expand/scroll state, and
        // re-running the auto-collapse LaunchedEffect) on every single new tool call.
        val keyAfterFirst = afterFirstCall.renderKey(isStreamingTail = true, occurrence = 0)
        val keyAfterSecond = afterSecondCall.renderKey(isStreamingTail = true, occurrence = 0)
        val keyAfterThird = afterThirdCall.renderKey(isStreamingTail = true, occurrence = 0)

        assertEquals(keyAfterFirst, keyAfterSecond)
        assertEquals(keyAfterSecond, keyAfterThird)
        assertEquals("live:tool", keyAfterThird)
    }

    @Test
    fun `ToolGroup key becomes content-based once no longer the streaming tail`() {
        val group = toolGroup("read_file", "write_file")

        val liveKey = group.renderKey(isStreamingTail = true, occurrence = 0)
        val frozenKey = group.renderKey(isStreamingTail = false, occurrence = 0)

        assertEquals("live:tool", liveKey)
        assertEquals(group.stableKey(), frozenKey)
        assertNotEquals(liveKey, frozenKey)
    }

    @Test
    fun `other group types already use a fixed live marker while streaming`() {
        val thinking = TurnTimelineGroup.ThinkingGroup("partial reasoning")
        val token = TurnTimelineGroup.TokenGroup("partial answer")
        val status = TurnTimelineGroup.StatusGroup(listOf(TurnTimelineEntry.Status("connecting")))

        assertEquals("live:thinking", thinking.renderKey(isStreamingTail = true, occurrence = 0))
        assertEquals("live:token", token.renderKey(isStreamingTail = true, occurrence = 0))
        assertEquals("live:status", status.renderKey(isStreamingTail = true, occurrence = 0))
    }

    @Test
    fun `occurrence suffix distinguishes sibling groups sharing the same base key`() {
        val group = toolGroup("read_file")
        val base = group.renderKey(isStreamingTail = false, occurrence = 0)
        val second = group.renderKey(isStreamingTail = false, occurrence = 1)

        assertEquals(group.stableKey(), base)
        assertEquals("${group.stableKey()}#1", second)
    }
}
