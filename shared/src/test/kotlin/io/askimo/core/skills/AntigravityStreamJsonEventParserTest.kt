/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills

import io.askimo.core.skills.agent.AntigravityStreamJsonEventParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AntigravityStreamJsonEventParserTest {

    // ── parse() ───────────────────────────────────────────────────────────────

    @Nested
    inner class Parse {

        @Test
        fun `init event flattens nested payload with top-level conversation_id`() {
            val line = """{"event":"init","conversation_id":"a96857a3-bbd6-449f-bbf9-ffbb6f2153aa","init":{"cwd":"C:\\Users\\hanna\\.askimo\\personal\\skills-workspace","tools":["read_file","write_to_file"],"permission_mode":"always-proceed"}}"""
            val event = AntigravityStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("init", event!!.type)
            assertEquals("a96857a3-bbd6-449f-bbf9-ffbb6f2153aa", event.fields["conversation_id"])
            assertEquals("C:\\Users\\hanna\\.askimo\\personal\\skills-workspace", event.fields["cwd"])
            assertEquals("always-proceed", event.fields["permission_mode"])
            assertNull(event.fields["event"])
            assertNull(event.fields["init"])
        }

        @Test
        fun `step_update user_input event exposes state and step_type`() {
            val line = """{"event":"step_update","step_update":{"conversation_id":"a968","step_index":0,"state":"DONE","step_type":"user_input"}}"""
            val event = AntigravityStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("step_update", event!!.type)
            assertEquals("user_input", event.fields["step_type"])
            assertEquals("DONE", event.fields["state"])
            assertEquals("a968", event.fields["conversation_id"])
        }

        @Test
        fun `step_update agent_response event exposes text_delta and usage`() {
            val line = """{"event":"step_update","step_update":{"conversation_id":"a968","step_index":1,"state":"DONE","step_type":"agent_response","text_delta":"The capital of Vietnam is **Hanoi** (Hà Nội).\n","duration_seconds":0.9911523,"usage":{"input_tokens":14741,"output_tokens":93,"total_tokens":14834}}}"""
            val event = AntigravityStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("step_update", event!!.type)
            assertEquals("agent_response", event.fields["step_type"])
            assertEquals("The capital of Vietnam is **Hanoi** (Hà Nội).\n", event.fields["text_delta"])
            val usage = event.fields["usage"]
            assertTrue(usage is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            assertEquals("14834", (usage as Map<String, Any>)["total_tokens"].toString())
        }

        @Test
        fun `result event exposes status, response and usage`() {
            val line = """{"event":"result","result":{"conversation_id":"a968","status":"SUCCESS","response":"The capital of Vietnam is **Hanoi** (Hà Nội).\n","duration_seconds":1.1338377,"num_turns":1,"usage":{"input_tokens":14741,"output_tokens":93,"total_tokens":14834}}}"""
            val event = AntigravityStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("result", event!!.type)
            assertEquals("SUCCESS", event.fields["status"])
            assertEquals("1.1338377", event.fields["duration_seconds"].toString())
            assertTrue(event.fields["usage"] is Map<*, *>)
        }

        @Test
        fun `blank line returns null`() {
            assertNull(AntigravityStreamJsonEventParser.parse(""))
            assertNull(AntigravityStreamJsonEventParser.parse("   "))
        }

        @Test
        fun `non-json line returns null`() {
            assertNull(AntigravityStreamJsonEventParser.parse("not json"))
        }

        @Test
        fun `line without type or event field returns null`() {
            assertNull(AntigravityStreamJsonEventParser.parse("""{"foo":"bar"}"""))
        }

        @Test
        fun `handles escaped newlines and quotes in string values`() {
            val line = """{"event":"step_update","step_update":{"text_delta":"line1\nline2\t\"quoted\""}}"""
            val event = AntigravityStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("line1\nline2\t\"quoted\"", event!!.fields["text_delta"])
        }
    }

    // ── render() ─────────────────────────────────────────────────────────────

    @Nested
    inner class Render {

        @Test
        fun `unknown-shaped event renders type and fields`() {
            val line = """{"event":"step_update","step_update":{"step_type":"tool_call","state":"RUNNING"}}"""
            val event = AntigravityStreamJsonEventParser.parse(line)!!
            val rendered = AntigravityStreamJsonEventParser.render(event)
            assertTrue(rendered.startsWith("step_update:"))
            assertTrue(rendered.contains("step_type: tool_call"))
            assertTrue(rendered.contains("state: RUNNING"))
        }

        @Test
        fun `truncates long string values`() {
            val longValue = "x".repeat(200)
            val line = """{"event":"step_update","step_update":{"text_delta":"$longValue"}}"""
            val event = AntigravityStreamJsonEventParser.parse(line)!!
            val rendered = AntigravityStreamJsonEventParser.render(event)
            assertTrue(rendered.contains("…"), "Long value should be truncated with ellipsis")
        }
    }
}
