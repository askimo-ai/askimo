/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.memory

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that tool_use/tool_result pairing survives a MemoryMessage round-trip
 * (the serialization format used to persist session memory to the database).
 *
 * Regression coverage for: Anthropic error "tool_use ids were found without
 * tool_result blocks immediately after" — which happens when the id linking a
 * `tool_use` block to its `tool_result` is lost across a DB save/reload cycle.
 */
class MemoryMessageTest {

    @Test
    @DisplayName("should preserve AiMessage tool execution requests across MemoryMessage round-trip")
    fun shouldPreserveToolExecutionRequests() {
        val request1 = ToolExecutionRequest.builder()
            .id("toolu_01Aoaxsi9ygKmuSFYAwcRFTf")
            .name("list_files")
            .arguments("{\"path\":\"a\"}")
            .build()
        val request2 = ToolExecutionRequest.builder()
            .id("toolu_01VAxJvxu9WCD2Hb3yabKtPY")
            .name("list_files")
            .arguments("{\"path\":\"b\"}")
            .build()

        val aiMessage = AiMessage.from(listOf(request1, request2))

        val memoryMessage = MemoryMessage.from(aiMessage)
        val restored = memoryMessage.toChatMessage()

        assertTrue(restored is AiMessage)
        val restoredAi = restored
        assertTrue(restoredAi.hasToolExecutionRequests())
        assertEquals(2, restoredAi.toolExecutionRequests().size)
        val restoredIds = restoredAi.toolExecutionRequests().map { it.id() }.toSet()
        assertTrue(restoredIds.contains("toolu_01Aoaxsi9ygKmuSFYAwcRFTf"))
        assertTrue(restoredIds.contains("toolu_01VAxJvxu9WCD2Hb3yabKtPY"))
    }

    @Test
    @DisplayName("should preserve ToolExecutionResultMessage id and toolName across MemoryMessage round-trip")
    fun shouldPreserveToolExecutionResultId() {
        val request = ToolExecutionRequest.builder()
            .id("toolu_middle_call")
            .name("some_tool")
            .arguments("{}")
            .build()
        val resultMessage = ToolExecutionResultMessage.from(request, "tool output")

        val memoryMessage = MemoryMessage.from(resultMessage)
        val restored = memoryMessage.toChatMessage()

        assertTrue(restored is ToolExecutionResultMessage)
        val restoredResult = restored
        assertEquals("toolu_middle_call", restoredResult.id())
        assertEquals("some_tool", restoredResult.toolName())
        assertEquals("tool output", restoredResult.text())
    }

    @Test
    @DisplayName("should preserve full tool_use/tool_result pairing through a JSON serialization round-trip")
    fun shouldPreservePairingThroughJsonSerialization() {
        val request = ToolExecutionRequest.builder()
            .id("toolu_json_roundtrip")
            .name("some_tool")
            .arguments("{\"x\":1}")
            .build()

        val originalMessages = listOf(
            UserMessage.from("Do the thing"),
            AiMessage.from(request),
            ToolExecutionResultMessage.from(request, "done"),
        )

        val memoryMessages = originalMessages.map { it.toMemoryMessage() }
        val jsonSerialized = io.askimo.core.util.JsonUtils.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MemoryMessage.serializer()),
            memoryMessages,
        )
        val deserialized = io.askimo.core.util.JsonUtils.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(MemoryMessage.serializer()),
            jsonSerialized,
        )
        val restoredMessages = deserialized.map { it.toChatMessage() }

        val restoredAi = restoredMessages[1] as AiMessage
        val restoredResult = restoredMessages[2] as ToolExecutionResultMessage

        assertTrue(restoredAi.hasToolExecutionRequests())
        val toolUseId = restoredAi.toolExecutionRequests().first().id()
        assertEquals(toolUseId, restoredResult.id(), "tool_use id must match tool_result id after JSON round-trip")
    }

    @Test
    @DisplayName("should drop (not throw) legacy TOOL_EXECUTION_RESULT_MESSAGE rows persisted before tool-call metadata existed")
    fun shouldDropLegacyToolResultMessagesWithoutId() {
        // Simulates a MemoryMessage row persisted by an older app version, before toolCallId/
        // toolName/toolExecutionRequests existed — deserialization falls back to the field
        // defaults (null / empty), exactly like this hand-built instance.
        val legacyToolResult = MemoryMessage(
            content = "some legacy tool output",
            type = io.askimo.core.context.MessageRole.TOOL_EXECUTION_RESULT_MESSAGE.value,
        )

        val restored = legacyToolResult.toChatMessage()

        assertEquals(null, restored, "Legacy tool_result rows without an id must be dropped, not reconstructed with a blank id")
    }

    @Test
    @DisplayName("mapNotNull over a mixed legacy + modern history should keep only reconstructable messages")
    fun shouldFilterOutOnlyLegacyToolResultsFromMixedHistory() {
        val request = ToolExecutionRequest.builder()
            .id("toolu_modern_call")
            .name("some_tool")
            .arguments("{}")
            .build()

        val history = listOf(
            MemoryMessage(content = "Hello", type = io.askimo.core.context.MessageRole.USER.value),
            MemoryMessage(content = "legacy tool output", type = io.askimo.core.context.MessageRole.TOOL_EXECUTION_RESULT_MESSAGE.value),
            MemoryMessage.from(AiMessage.from(request)),
            MemoryMessage.from(ToolExecutionResultMessage.from(request, "modern output")),
        )

        val restored = history.mapNotNull { it.toChatMessage() }

        assertEquals(3, restored.size, "Only the unreconstructable legacy tool_result should be dropped")
        assertTrue(restored.any { it is UserMessage })
        assertTrue(restored.any { it is AiMessage && it.hasToolExecutionRequests() })
        assertTrue(restored.any { it is ToolExecutionResultMessage && it.id() == "toolu_modern_call" })
    }

    @Test
    @DisplayName("should drop TOOL_EXECUTION_RESULT_MESSAGE rows with a valid toolCallId but blank/missing toolName")
    fun shouldDropToolResultMessagesWithBlankToolName() {
        // A tool_result missing its tool name is just as invalid for providers as one missing
        // its id — must be dropped rather than reconstructed with a blank name fallback.
        val missingToolName = MemoryMessage(
            content = "output",
            type = io.askimo.core.context.MessageRole.TOOL_EXECUTION_RESULT_MESSAGE.value,
            toolCallId = "toolu_has_id_no_name",
            toolName = null,
        )
        val blankToolName = missingToolName.copy(toolName = "   ")

        assertEquals(null, missingToolName.toChatMessage(), "toolName == null must be dropped")
        assertEquals(null, blankToolName.toChatMessage(), "toolName blank must be dropped")
    }
}
