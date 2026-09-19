/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for [truncatedForStorage] — the *only* place [ToolCallInfo.arguments]/
 * [ToolCallInfo.result] get capped to [ToolCallInfo.MAX_FIELD_LENGTH]. The live, in-session
 * timeline (built via the plain [ToolCallInfo] constructor in `SessionManager`/
 * `AgentRunViewModel`) is expected to keep the full content; only what's about to be persisted
 * goes through this function.
 */
class TurnTimelineEntryTruncatedForStorageTest {

    @Test
    fun `long tool arguments and result are truncated`() {
        val longArgs = "a".repeat(ToolCallInfo.MAX_FIELD_LENGTH + 500)
        val longResult = "b".repeat(ToolCallInfo.MAX_FIELD_LENGTH + 500)
        val timeline = listOf(
            TurnTimelineEntry.Tool(
                ToolCallInfo(toolName = "Write", status = ToolCallStatus.DONE, arguments = longArgs, result = longResult),
            ),
        )

        val stored = timeline.truncatedForStorage()

        val tool = (stored.single() as TurnTimelineEntry.Tool).toolCall
        assertEquals(ToolCallInfo.MAX_FIELD_LENGTH + 1, tool.arguments?.length) // +1 for the "…" ellipsis
        assertEquals(ToolCallInfo.MAX_FIELD_LENGTH + 1, tool.result?.length)
        assertTrue(tool.arguments!!.endsWith("…"))
        assertTrue(tool.result!!.endsWith("…"))
    }

    @Test
    fun `short tool arguments are left untouched`() {
        val timeline = listOf(
            TurnTimelineEntry.Tool(
                ToolCallInfo(toolName = "read_file", status = ToolCallStatus.RUNNING, arguments = "/tmp/foo.txt"),
            ),
        )

        val stored = timeline.truncatedForStorage()

        assertEquals("/tmp/foo.txt", (stored.single() as TurnTimelineEntry.Tool).toolCall.arguments)
    }

    @Test
    fun `non-Tool entries are passed through unchanged`() {
        val timeline = listOf(
            TurnTimelineEntry.Status("model: claude"),
            TurnTimelineEntry.Thinking("some reasoning"),
            TurnTimelineEntry.Token("hello"),
        )

        assertEquals(timeline, timeline.truncatedForStorage())
    }

    @Test
    fun `other ToolCallInfo fields are preserved`() {
        val timeline = listOf(
            TurnTimelineEntry.Tool(
                ToolCallInfo(toolName = "exec", status = ToolCallStatus.DONE, hasFailed = true, startedAtMillis = 42L),
            ),
        )

        val tool = (timeline.truncatedForStorage().single() as TurnTimelineEntry.Tool).toolCall
        assertEquals("exec", tool.toolName)
        assertEquals(ToolCallStatus.DONE, tool.status)
        assertEquals(true, tool.hasFailed)
        assertEquals(42L, tool.startedAtMillis)
    }
}
