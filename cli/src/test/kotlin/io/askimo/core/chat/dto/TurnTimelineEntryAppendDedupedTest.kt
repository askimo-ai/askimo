/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for [appendDeduped] — collapsing a burst of identical
 * [TurnTimelineEntry.Status] updates (e.g. an agent repeatedly reporting the same ambient
 * "thinking" state with no new content yet) into a single entry, without touching any other
 * kind of [TurnTimelineEntry] or a [TurnTimelineEntry.Status] whose text genuinely changed.
 */
class TurnTimelineEntryAppendDedupedTest {

    private fun status(text: String) = TurnTimelineEntry.Status(text)
    private fun thinking(text: String) = TurnTimelineEntry.Thinking(text)
    private fun token(text: String) = TurnTimelineEntry.Token(text)
    private fun tool(name: String) = TurnTimelineEntry.Tool(ToolCallInfo(toolName = name, status = ToolCallStatus.RUNNING))

    @Test
    fun `identical consecutive Status entries collapse into one`() {
        var timeline = emptyList<TurnTimelineEntry>()
        timeline = timeline.appendDeduped(status("thinking…"))
        timeline = timeline.appendDeduped(status("thinking…"))
        timeline = timeline.appendDeduped(status("thinking…"))

        assertEquals(listOf(status("thinking…")), timeline)
    }

    @Test
    fun `a Status with different text is appended, not collapsed`() {
        var timeline = emptyList<TurnTimelineEntry>()
        timeline = timeline.appendDeduped(status("thinking…"))
        timeline = timeline.appendDeduped(status("model: claude-opus-4"))

        assertEquals(listOf(status("thinking…"), status("model: claude-opus-4")), timeline)
    }

    @Test
    fun `a repeated Status text is appended again once a different entry interrupted the run`() {
        var timeline = emptyList<TurnTimelineEntry>()
        timeline = timeline.appendDeduped(status("thinking…"))
        timeline = timeline.appendDeduped(token("Hello"))
        timeline = timeline.appendDeduped(status("thinking…"))

        // Not collapsed with the first "thinking…" — a Token arrived in between, so this is a
        // genuinely new phase, not a continuation of the earlier one.
        assertEquals(listOf(status("thinking…"), token("Hello"), status("thinking…")), timeline)
    }

    @Test
    fun `non-Status entries are never deduplicated, even if identical`() {
        var timeline = emptyList<TurnTimelineEntry>()
        timeline = timeline.appendDeduped(thinking("same"))
        timeline = timeline.appendDeduped(thinking("same"))
        timeline = timeline.appendDeduped(token("same"))
        timeline = timeline.appendDeduped(token("same"))
        timeline = timeline.appendDeduped(tool("read_file"))
        timeline = timeline.appendDeduped(tool("read_file"))

        assertEquals(
            listOf(thinking("same"), thinking("same"), token("same"), token("same"), tool("read_file"), tool("read_file")),
            timeline,
        )
    }

    @Test
    fun `appending to an empty list always succeeds`() {
        val timeline = emptyList<TurnTimelineEntry>().appendDeduped(status("thinking…"))
        assertEquals(listOf(status("thinking…")), timeline)
    }
}
