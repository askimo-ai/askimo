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

    /**
     * Stable, filesystem/agent-safe identifier derived from the **full category-qualified
     * path** (not just the leaf folder name), used as the destination folder name whenever
     * this skill is materialized into an external agent's native skill directory (see
     * [io.askimo.core.agent.ExternalAgentTemplate.materializeSkillFolder]/
     * [io.askimo.core.agent.ExternalAgentTemplate.materializeSkillSymlink]).
     *
     * ## Why not just the leaf folder name?
     * Two skills in different categories (or imported from different packs) can share the
     * same leaf folder name (e.g. `pack1/reviewer/skill.md` and `pack2/reviewer/skill.md`).
     * Using only the leaf name (`"reviewer"`) as the materialized folder name would collide —
     * the second skill would silently never become visible to the agent, since materialization
     * checks "does a folder with this name already exist?" and leaves it alone if so (to avoid
     * clobbering a user's own project skill). Folding the full category path into the
     * identifier keeps every skill's materialized location unique to its own location in the
     * tree, regardless of how many "group" folders it was imported under.
     *
     * ## Why sanitize?
     * Imported skill packs (GitHub/zip) can contain folder names with spaces, unicode,
     * mixed case, or unusual characters. Agent-native skill-discovery conventions generally
     * assume simple, stable identifiers, so this is normalized to lowercase kebab-case ASCII.
     *
     * Examples:
     * - `"coding/reviewer.md"` → `"coding-reviewer"`
     * - `"reviewer.md"` (root-level) → `"reviewer"`
     * - `"My Skills/Code Review!.md"` → `"my-skills-code-review"`
     */
    val slug: String
        get() {
            val withoutExtension = relativePath.replace("\\", "/").removeSuffix(".md")
            val raw = withoutExtension
                .split("/")
                .filter { it.isNotBlank() }
                .joinToString("-")
            return raw
                .lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(MAX_SLUG_LENGTH)
                .ifBlank { "skill" }
        }

    companion object {
        /** Cap on [slug] length — keeps materialized folder names reasonable across filesystems. */
        private const val MAX_SLUG_LENGTH = 80
    }
}
