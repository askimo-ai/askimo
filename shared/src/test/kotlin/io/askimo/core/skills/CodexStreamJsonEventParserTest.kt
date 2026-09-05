/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills

import io.askimo.core.agent.CodexStreamJsonEventParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Fixtures below are verbatim lines captured from a real `codex exec --json` run (see the
 * "Hello Claude" skill test-run log), not hand-guessed — Codex's `--json` protocol is a flat
 * `{"type":"<name>", ...fields}` envelope, unlike the nested `msg`-keyed shape this parser
 * originally (incorrectly) assumed before that real output was captured.
 */
class CodexStreamJsonEventParserTest {

    // ── parse() ───────────────────────────────────────────────────────────────

    @Nested
    inner class Parse {

        @Test
        fun `thread started event exposes thread_id`() {
            val line = """{"type":"thread.started","thread_id":"01a07306-52ef-78d0-9829-6e70e75aca83"}"""
            val event = CodexStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("thread.started", event!!.type)
            assertEquals("01a07306-52ef-78d0-9829-6e70e75aca83", event.fields["thread_id"])
            assertNull(event.fields["type"])
        }

        @Test
        fun `turn started event has no extra fields`() {
            val line = """{"type":"turn.started"}"""
            val event = CodexStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("turn.started", event!!.type)
            assertTrue(event.fields.isEmpty())
        }

        @Test
        fun `item completed agent_message event exposes item with text`() {
            val line = """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"I’ll run the requested check: list the workspace, summarize one file, then return the test marker."}}"""
            val event = CodexStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("item.completed", event!!.type)
            val item = CodexStreamJsonEventParser.item(event.fields)
            assertNotNull(item)
            assertEquals("agent_message", item!!["type"])
            assertEquals(
                "I’ll run the requested check: list the workspace, summarize one file, then return the test marker.",
                item["text"],
            )
        }

        @Test
        fun `item started command_execution event exposes in-progress fields`() {
            val line = """{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"/bin/zsh -lc \"pwd && rg --files -g '\"'!**/.git/**'\"' | sed -n '1,40p'\"","aggregated_output":"","exit_code":null,"status":"in_progress"}}"""
            val event = CodexStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("item.started", event!!.type)
            val item = CodexStreamJsonEventParser.item(event.fields)
            assertNotNull(item)
            assertEquals("command_execution", item!!["type"])
            assertEquals("in_progress", item["status"])
            assertEquals("", item["aggregated_output"])
        }

        @Test
        fun `item completed command_execution event exposes command output and exit_code`() {
            val line = """{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"/bin/zsh -lc \"pwd && rg --files -g '\"'!**/.git/**'\"' | sed -n '1,40p'\"","aggregated_output":"/Users/hainguyen/Downloads/askimo_rag_demo_docs\nPRD_Notification_System.txt\n","exit_code":0,"status":"completed"}}"""
            val event = CodexStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("item.completed", event!!.type)
            val item = CodexStreamJsonEventParser.item(event.fields)
            assertNotNull(item)
            assertEquals("command_execution", item!!["type"])
            assertEquals("0", item["exit_code"].toString())
            assertEquals("completed", item["status"])
            assertTrue((item["aggregated_output"] as String).contains("PRD_Notification_System.txt"))
        }

        @Test
        fun `final agent_message item carries the success marker text`() {
            val line = """{"type":"item.completed","item":{"id":"item_3","type":"agent_message","text":"Files found: 5, including `PRD_Notification_System.txt`.\n\n✅ hello-claude skill executed successfully"}}"""
            val event = CodexStreamJsonEventParser.parse(line)
            assertNotNull(event)
            val item = CodexStreamJsonEventParser.item(event!!.fields)
            assertNotNull(item)
            assertTrue((item!!["text"] as String).contains("✅ hello-claude skill executed successfully"))
        }

        @Test
        fun `blank line returns null`() {
            assertNull(CodexStreamJsonEventParser.parse(""))
            assertNull(CodexStreamJsonEventParser.parse("   "))
        }

        @Test
        fun `non-json line returns null`() {
            assertNull(CodexStreamJsonEventParser.parse("not json"))
        }

        @Test
        fun `line without type field returns null`() {
            assertNull(CodexStreamJsonEventParser.parse("""{"foo":"bar"}"""))
        }

        @Test
        fun `item helper returns null when item field is missing`() {
            val line = """{"type":"turn.started"}"""
            val event = CodexStreamJsonEventParser.parse(line)!!
            assertNull(CodexStreamJsonEventParser.item(event.fields))
        }

        @Test
        fun `handles escaped quotes and newlines inside command strings`() {
            val line = """{"type":"item.completed","item":{"type":"command_execution","command":"echo \"hi\"\nline2","exit_code":0}}"""
            val event = CodexStreamJsonEventParser.parse(line)!!
            val item = CodexStreamJsonEventParser.item(event.fields)!!
            assertEquals("echo \"hi\"\nline2", item["command"])
        }
    }
}
