/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills

import io.askimo.core.agent.ClaudeStreamJsonEventParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeStreamJsonEventParserTest {

    // ── parse() ───────────────────────────────────────────────────────────────

    @Nested
    inner class Parse {

        @Test
        fun `blank line returns null`() {
            assertNull(ClaudeStreamJsonEventParser.parse(""))
            assertNull(ClaudeStreamJsonEventParser.parse("   "))
        }

        @Test
        fun `non-json line returns null`() {
            assertNull(ClaudeStreamJsonEventParser.parse("not json"))
        }

        @Test
        fun `system init event extracts top-level fields`() {
            val line = """{"type":"system","subtype":"init","cwd":"/work","session_id":"abc123","model":"claude-haiku-4-5-20251001","permissionMode":"bypassPermissions","claude_code_version":"2.1.138","tools":["Bash","Write"],"uuid":"u1"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("system", event!!.type)
            assertEquals("init", event.fields["subtype"])
            assertEquals("claude-haiku-4-5-20251001", event.fields["model"])
            assertEquals("bypassPermissions", event.fields["permissionMode"])
            assertEquals("2.1.138", event.fields["claude_code_version"])
            assertNull(event.fields["type"])
        }

        @Test
        fun `assistant event with text content block`() {
            val line = """{"type":"assistant","message":{"model":"claude-haiku-4-5-20251001","id":"msg_01","type":"message","role":"assistant","content":[{"type":"text","text":"Hello, I will create the project."}],"stop_reason":null,"usage":{"input_tokens":9,"output_tokens":7}},"session_id":"s1","uuid":"u2"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("assistant", event!!.type)
            assertTrue(event.fields["message"] is Map<*, *>)
            assertNull(event.fields["type"])
        }

        @Test
        fun `result success event extracts result and stats fields`() {
            val line = """{"type":"result","subtype":"success","is_error":false,"duration_ms":5975,"num_turns":1,"result":"# Done\n\nProject created.","stop_reason":"end_turn","total_cost_usd":0.011,"uuid":"u3"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("result", event!!.type)
            assertEquals("success", event.fields["subtype"])
            assertEquals("# Done\n\nProject created.", event.fields["result"])
            assertEquals("end_turn", event.fields["stop_reason"])
            assertEquals("5975", event.fields["duration_ms"])
            assertEquals("1", event.fields["num_turns"])
            assertNull(event.fields["type"])
        }

        @Test
        fun `user event with tool_use_result string`() {
            val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Answer questions?","is_error":true,"tool_use_id":"t1"}]},"tool_use_result":"Error: Answer questions?","uuid":"u4"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("user", event!!.type)
            assertEquals("Error: Answer questions?", event.fields["tool_use_result"])
        }

        @Test
        fun `user event with tool_use_result map for file creation`() {
            val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"File created","tool_use_id":"t2"}]},"tool_use_result":{"type":"create","filePath":"/work/pom.xml","content":"<project/>"},"uuid":"u5"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("user", event!!.type)
            assertTrue(event.fields["tool_use_result"] is Map<*, *>)
        }

        @Test
        fun `handles escaped characters in strings`() {
            val line = """{"type":"result","subtype":"success","result":"line1\nline2\t\"quoted\"","uuid":"u6"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)
            assertNotNull(event)
            assertEquals("line1\nline2\t\"quoted\"", event!!.fields["result"])
        }
    }

    // ── extractContentBlocks() ────────────────────────────────────────────────

    @Nested
    inner class ExtractContentBlocks {

        @Test
        fun `returns text block`() {
            val line = """{"type":"assistant","message":{"model":"m","id":"i","type":"message","role":"assistant","content":[{"type":"text","text":"Hello world"}],"stop_reason":null},"uuid":"u7"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            val blocks = ClaudeStreamJsonEventParser.extractContentBlocks(event.fields)
            assertEquals(1, blocks.size)
            assertEquals("text", blocks[0].type)
            assertEquals("Hello world", blocks[0].fields["text"])
        }

        @Test
        fun `returns tool_use block with name and input`() {
            val line = """{"type":"assistant","message":{"model":"m","id":"i","type":"message","role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"mkdir -p spring-boot","description":"Create dir"}}],"stop_reason":null},"uuid":"u8"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            val blocks = ClaudeStreamJsonEventParser.extractContentBlocks(event.fields)
            assertEquals(1, blocks.size)
            assertEquals("tool_use", blocks[0].type)
            assertEquals("Bash", blocks[0].fields["name"])
            val input = blocks[0].fields["input"]
            assertTrue(input is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            val inputMap = input as Map<String, Any>
            assertEquals("mkdir -p spring-boot", inputMap["command"])
        }

        @Test
        fun `returns thinking block`() {
            val line = """{"type":"assistant","message":{"model":"m","id":"i","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"I need to plan this carefully.","signature":"sig1"}],"stop_reason":null},"uuid":"u9"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            val blocks = ClaudeStreamJsonEventParser.extractContentBlocks(event.fields)
            assertEquals(1, blocks.size)
            assertEquals("thinking", blocks[0].type)
            assertEquals("I need to plan this carefully.", blocks[0].fields["thinking"])
        }

        @Test
        fun `returns multiple mixed blocks in order`() {
            val line = """{"type":"assistant","message":{"model":"m","id":"i","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"I'll help you."},{"type":"tool_use","id":"t1","name":"Write","input":{"file_path":"pom.xml","content":"<project/>"}}],"stop_reason":null},"uuid":"u10"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            val blocks = ClaudeStreamJsonEventParser.extractContentBlocks(event.fields)
            assertEquals(3, blocks.size)
            assertEquals("thinking", blocks[0].type)
            assertEquals("text", blocks[1].type)
            assertEquals("tool_use", blocks[2].type)
            assertEquals("Write", blocks[2].fields["name"])
        }

        @Test
        fun `returns empty list when no message field`() {
            val line = """{"type":"result","subtype":"success","result":"done","uuid":"u11"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            val blocks = ClaudeStreamJsonEventParser.extractContentBlocks(event.fields)
            assertTrue(blocks.isEmpty())
        }
    }

    // ── extractToolUseResult() ────────────────────────────────────────────────

    @Nested
    inner class ExtractToolUseResult {

        @Test
        fun `returns string for error result`() {
            val line = """{"type":"user","message":{"role":"user","content":[]},"tool_use_result":"Error: Answer questions?","uuid":"u12"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            val result = ClaudeStreamJsonEventParser.extractToolUseResult(event.fields)
            assertEquals("Error: Answer questions?", result)
        }

        @Test
        fun `returns map for file create result`() {
            val line = """{"type":"user","message":{"role":"user","content":[]},"tool_use_result":{"type":"create","filePath":"/work/pom.xml","content":"<project/>"},"uuid":"u13"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            val result = ClaudeStreamJsonEventParser.extractToolUseResult(event.fields)
            assertTrue(result is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            val map = result as Map<String, Any>
            assertEquals("create", map["type"])
            assertEquals("/work/pom.xml", map["filePath"])
        }

        @Test
        fun `returns null when field absent`() {
            val line = """{"type":"result","subtype":"success","result":"done","uuid":"u14"}"""
            val event = ClaudeStreamJsonEventParser.parse(line)!!
            assertNull(ClaudeStreamJsonEventParser.extractToolUseResult(event.fields))
        }
    }

    // ── parseArray() ─────────────────────────────────────────────────────────

    @Nested
    inner class ParseArray {

        @Test
        fun `parses array of strings`() {
            val result = ClaudeStreamJsonEventParser.parseArray("""["Bash","Write","Read"]""")
            assertEquals(3, result.size)
            assertEquals("Bash", result[0])
            assertEquals("Write", result[1])
            assertEquals("Read", result[2])
        }

        @Test
        fun `parses array of objects`() {
            val result = ClaudeStreamJsonEventParser.parseArray("""[{"type":"text","text":"hello"},{"type":"tool_use","name":"Bash"}]""")
            assertEquals(2, result.size)
            assertTrue(result[0] is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            val first = result[0] as Map<String, Any>
            assertEquals("text", first["type"])
            assertEquals("hello", first["text"])
        }

        @Test
        fun `returns empty for invalid input`() {
            assertTrue(ClaudeStreamJsonEventParser.parseArray("not an array").isEmpty())
            assertTrue(ClaudeStreamJsonEventParser.parseArray("{}").isEmpty())
            assertTrue(ClaudeStreamJsonEventParser.parseArray("").isEmpty())
        }
    }
}
