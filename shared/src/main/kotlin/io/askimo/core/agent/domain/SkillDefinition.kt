/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.domain

import java.nio.file.Path

/**
 * Parsed representation of a single skill markdown file.
 *
 * A skill file has a YAML frontmatter block followed by a freeform markdown body
 * that acts as the agent-agnostic system prompt:
 *
 * ```markdown
 * ---
 * name: Code Reviewer
 * description: Reviews code for bugs, style, and best practices
 * tags: [code, review, quality]
 * visibility: private
 * ---
 *
 * You are an expert code reviewer. When given code or a file path, you:
 * 1. Identify bugs and potential issues
 * 2. Suggest improvements for readability and performance
 * 3. Check for security vulnerabilities
 * 4. Provide actionable, specific feedback
 * ```
 *
 * ## Intentionally omitted from frontmatter
 * - `model` — agent-agnostic; the caller decides which model to use.
 * - `tools` — the active agent and MCP configuration controls tool availability.
 *
 * @param relativePath  Relative path from `skillsDir()`, e.g. `"coding/review/code-reviewer.md"`.
 *                      The path segments form the category tree shown in the UI.
 * @param name          Human-readable display name (from frontmatter `name:`, or derived from file name).
 * @param description   Short description shown in the skills panel (optional).
 * @param visibility    Controls sync behaviour — [SkillVisibility.PRIVATE] skills never leave the device.
 * @param content       Raw markdown body used verbatim as the system prompt.
 * @param absolutePath  Absolute [Path] to the source file on disk.
 * @param systemPrompt  Human-authored body from `skill.md` only (no supplemental merging).
 *                      Use this for display; [content] is the full merged prompt sent to agents.
 * @param supplementalFileNames Names of supplemental files (relative to skill folder) merged into [content].
 */
data class SkillDefinition(
    val relativePath: String,
    val name: String,
    val description: String = "",
    val visibility: SkillVisibility = SkillVisibility.PRIVATE,
    val content: String,
    val absolutePath: Path,
    val systemPrompt: String = content,
    val supplementalFileNames: List<String> = emptyList(),
) {
    /**
     * Category path derived from the relative path directory segments.
     * e.g. `"coding/review/code-reviewer.md"` → `["coding", "review"]`.
     * Empty list for skills at the root of `skillsDir()`.
     */
    val categoryPath: List<String>
        get() {
            val parts = relativePath.replace("\\", "/").split("/").dropLast(1)
            return parts.filter { it.isNotBlank() }
        }

    /**
     * Slash-joined category string, e.g. `"coding/review"`.
     * Empty string for root-level skills.
     */
    val category: String get() = categoryPath.joinToString("/")
}
