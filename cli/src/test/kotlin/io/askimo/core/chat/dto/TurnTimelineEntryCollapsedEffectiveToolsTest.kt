/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.dto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TurnTimelineEntryCollapsedEffectiveToolsTest {

    private fun tool(name: String, args: String? = null, status: ToolCallStatus = ToolCallStatus.DONE) = TurnTimelineEntry.Tool(ToolCallInfo(toolName = name, status = status, arguments = args))

    private fun thinking(text: String) = TurnTimelineEntry.Thinking(text)
    private fun token(text: String) = TurnTimelineEntry.Token(text)
    private fun status(text: String) = TurnTimelineEntry.Status(text)

    @Test
    fun `no tool calls - passthrough unchanged`() {
        val entries = listOf(thinking("t1"), token("hello"))
        assertEquals(entries, entries.collapsedEffectiveTools())
    }

    @Test
    fun `single tool call - kept regardless of preceding thinking`() {
        val entries = listOf(thinking("reasoning"), tool("A", "args1"))
        assertEquals(entries, entries.collapsedEffectiveTools())
    }

    @Test
    fun `distinct tools each called once - all kept in order`() {
        val entries = listOf(tool("A", "a1"), tool("B", "b1"), tool("C", "c1"))
        assertEquals(entries, entries.collapsedEffectiveTools())
    }

    @Test
    fun `A A B with different arguments - collapses to A(last) B`() {
        // The classic retry: same tool, different arguments each attempt, since the AI is
        // trying to guess the right parameters. Arguments must be ignored in the dedup key.
        val a1 = tool("A", "args1")
        val a2 = tool("A", "args2")
        val b = tool("B", "argsB")
        val result = listOf(a1, a2, b).collapsedEffectiveTools()
        assertEquals(listOf(a2, b), result)
    }

    @Test
    fun `A A B with identical arguments - still collapses to A(last) B`() {
        val a1 = tool("A", "same")
        val a2 = tool("A", "same")
        val b = tool("B", "argsB")
        val result = listOf(a1, a2, b).collapsedEffectiveTools()
        assertEquals(listOf(a2, b), result)
    }

    @Test
    fun `thinking-tool retry loop collapses to last thinking plus last tool`() {
        // Thinking, ToolA(args1), Thinking, ToolA(args2), Thinking, ToolA(args3)
        val t1 = thinking("guessing param set 1")
        val a1 = tool("A", "args1")
        val t2 = thinking("guessing param set 2")
        val a2 = tool("A", "args2")
        val t3 = thinking("finally the right params")
        val a3 = tool("A", "args3")

        val result = listOf(t1, a1, t2, a2, t3, a3).collapsedEffectiveTools()

        // Only the reasoning immediately preceding the effective (last) call survives —
        // earlier dead-end reasoning/attempts are dropped entirely.
        assertEquals(listOf(t3, a3), result)
    }

    @Test
    fun `retry loop for A followed by a distinct tool B keeps both steps`() {
        val t1 = thinking("guess 1")
        val a1 = tool("A", "args1")
        val t2 = thinking("guess 2 - correct")
        val a2 = tool("A", "args2")
        val t3 = thinking("now call B")
        val b = tool("B", "argsB")

        val result = listOf(t1, a1, t2, a2, t3, b).collapsedEffectiveTools()

        assertEquals(listOf(t2, a2, t3, b), result)
    }

    @Test
    fun `back-to-back retries with no thinking between them still collapse`() {
        val a1 = tool("A", "args1")
        val a2 = tool("A", "args2")
        val a3 = tool("A", "args3")
        val result = listOf(a1, a2, a3).collapsedEffectiveTools()
        assertEquals(listOf(a3), result)
    }

    @Test
    fun `thinking not followed by any tool call is preserved`() {
        // The AI just "thought out loud" without calling a tool afterwards — e.g. right before
        // the final answer. This must NOT be dropped.
        val t1 = thinking("no tool needed here")
        val answer = token("Here's the answer.")
        val result = listOf(t1, answer).collapsedEffectiveTools()
        assertEquals(listOf(t1, answer), result)
    }

    @Test
    fun `status and token entries pass through untouched around a retry loop`() {
        val s = status("Connecting...")
        val a1 = tool("A", "args1")
        val a2 = tool("A", "args2")
        val tok = token("Done.")
        val result = listOf(s, a1, a2, tok).collapsedEffectiveTools()
        assertEquals(listOf(s, a2, tok), result)
    }

    @Test
    fun `interleaved retries of two different tools each collapse independently`() {
        // A1, B1, A2, B2 — both A and B are retried once; only the last of each survives,
        // in their original relative positions.
        val a1 = tool("A", "a-args1")
        val b1 = tool("B", "b-args1")
        val a2 = tool("A", "a-args2")
        val b2 = tool("B", "b-args2")
        val result = listOf(a1, b1, a2, b2).collapsedEffectiveTools()
        assertEquals(listOf(a2, b2), result)
    }

    @Test
    fun `grouped() applies the same collapse before building groups`() {
        val t1 = thinking("guess 1")
        val a1 = tool("A", "args1")
        val t2 = thinking("guess 2 - correct")
        val a2 = tool("A", "args2")
        val finalText = token("All done.")

        val groups = listOf(t1, a1, t2, a2, finalText).grouped()

        assertEquals(
            listOf(
                TurnTimelineGroup.ThinkingGroup("guess 2 - correct"),
                TurnTimelineGroup.ToolGroup(listOf(a2)),
                TurnTimelineGroup.TokenGroup("All done."),
            ),
            groups,
        )
    }

    @Test
    fun `grouped() still merges genuinely consecutive thinking chunks (no tool between them)`() {
        // Two Thinking entries with nothing in between (e.g. streamed token-by-token) must
        // still concatenate into a single ThinkingGroup — this is NOT a retry loop.
        val chunks = listOf(thinking("Hello "), thinking("world"), token("answer"))
        val groups = chunks.grouped()
        assertEquals(
            listOf(
                TurnTimelineGroup.ThinkingGroup("Hello world"),
                TurnTimelineGroup.TokenGroup("answer"),
            ),
            groups,
        )
    }

    @Test
    fun `empty list returns empty`() {
        assertEquals(emptyList(), emptyList<TurnTimelineEntry>().collapsedEffectiveTools())
    }
}
