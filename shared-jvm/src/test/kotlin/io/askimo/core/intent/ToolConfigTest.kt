/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

import dev.langchain4j.agent.tool.ToolSpecification
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [ToolConfig.effectiveApprovalPolicy] / [ToolConfig.requiresApproval] — the single
 * source of truth for resolving a tool's category-inheriting [ToolApprovalPolicy.DEFAULT]
 * down to a concrete decision, shared by the runtime guardrail
 * (`ChatClientExtensions.sendStreamingMessageWithCallback`) and the MCP tools UI
 * (`McpToolsDialog`'s two-way "Require approval" / "Auto approve" toggle).
 */
class ToolConfigTest {

    private fun toolConfig(
        category: ToolCategory,
        approvalPolicy: ToolApprovalPolicy,
    ) = ToolConfig(
        specification = ToolSpecification.builder().name("test_tool").build(),
        category = category,
        strategy = 0,
        source = ToolSource.ASKIMO_BUILTIN,
        approvalPolicy = approvalPolicy,
    )

    // ── DEFAULT: inherits from category ─────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(ToolCategory::class, names = ["FILE_WRITE", "EXECUTE"])
    fun `DEFAULT on a mutating category resolves to REQUIRE_APPROVAL`(category: ToolCategory) {
        val tool = toolConfig(category, ToolApprovalPolicy.DEFAULT)
        assertEquals(ToolApprovalPolicy.REQUIRE_APPROVAL, tool.effectiveApprovalPolicy())
        assertTrue(tool.requiresApproval())
    }

    @ParameterizedTest
    @EnumSource(ToolCategory::class, names = ["FILE_WRITE", "EXECUTE"], mode = EnumSource.Mode.EXCLUDE)
    fun `DEFAULT on a non-mutating category resolves to auto-run`(category: ToolCategory) {
        val tool = toolConfig(category, ToolApprovalPolicy.DEFAULT)
        assertEquals(ToolApprovalPolicy.DEFAULT, tool.effectiveApprovalPolicy())
        assertFalse(tool.requiresApproval())
    }

    // ── REQUIRE_APPROVAL: explicit override always wins ─────────────────────────────────

    @ParameterizedTest
    @EnumSource(ToolCategory::class)
    fun `REQUIRE_APPROVAL always resolves to REQUIRE_APPROVAL regardless of category`(category: ToolCategory) {
        val tool = toolConfig(category, ToolApprovalPolicy.REQUIRE_APPROVAL)
        assertEquals(ToolApprovalPolicy.REQUIRE_APPROVAL, tool.effectiveApprovalPolicy())
        assertTrue(tool.requiresApproval())
    }

    // ── ALWAYS_AUTO: explicit override always wins ───────────────────────────────────────

    @ParameterizedTest
    @EnumSource(ToolCategory::class)
    fun `ALWAYS_AUTO always resolves to auto-run regardless of category`(category: ToolCategory) {
        val tool = toolConfig(category, ToolApprovalPolicy.ALWAYS_AUTO)
        assertEquals(ToolApprovalPolicy.ALWAYS_AUTO, tool.effectiveApprovalPolicy())
        assertFalse(tool.requiresApproval())
    }

    // ── Category default policy sanity check ────────────────────────────────────────────

    @Test
    fun `defaultApprovalPolicy is REQUIRE_APPROVAL only for FILE_WRITE and EXECUTE`() {
        val mutatingCategories = setOf(ToolCategory.FILE_WRITE, ToolCategory.EXECUTE)
        ToolCategory.entries.forEach { category ->
            val expected = if (category in mutatingCategories) {
                ToolApprovalPolicy.REQUIRE_APPROVAL
            } else {
                ToolApprovalPolicy.DEFAULT
            }
            assertEquals(expected, category.defaultApprovalPolicy(), "Unexpected default policy for $category")
        }
    }
}
