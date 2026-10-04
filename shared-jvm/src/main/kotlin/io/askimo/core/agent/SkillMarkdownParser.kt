/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.SkillVisibility
import java.nio.file.Path

/**
 * Parses a skill markdown file into a [SkillDefinition].
 *
 * ## File format
 * ```markdown
 * ---
 * name: Code Reviewer
 * description: Reviews code for bugs, style, and best practices
 * tags: [code, review, quality]
 * visibility: private
 * ---
 *
 * You are an expert code reviewer...
 * ```
 *
 * The YAML frontmatter block is delimited by `---` lines.
 * Everything after the closing `---` is the skill body (system prompt).
 * The frontmatter block is optional — a file with no `---` delimiter is treated as body-only.
 *
 * Only the fields defined in [SkillDefinition] are extracted; unknown frontmatter keys are ignored.
 */
object SkillMarkdownParser {

    private val FRONTMATTER_DELIMITER = "---"

    /**
     * Parses [rawContent] (full file text) into a [SkillDefinition].
     *
     * @param rawContent   Full text content of the `.md` file.
     * @param relativePath Relative path from `skillsDir()`, used to derive [SkillDefinition.relativePath].
     * @param absolutePath Absolute path on disk, stored as-is in [SkillDefinition.absolutePath].
     */
    fun parse(rawContent: String, relativePath: String, absolutePath: Path): SkillDefinition {
        val (frontmatter, body) = splitFrontmatter(rawContent)
        val meta = parseFrontmatter(frontmatter)

        val fileBaseName = relativePath
            .replace("\\", "/")
            .substringAfterLast("/")
            .removeSuffix(".md")
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }

        return SkillDefinition(
            relativePath = relativePath,
            name = meta["name"]?.trim()?.takeIf { it.isNotBlank() } ?: fileBaseName,
            description = meta["description"]?.trim() ?: "",
            visibility = SkillVisibility.fromString(meta["visibility"]),
            content = body.trim(),
            absolutePath = absolutePath,
        )
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Splits raw file content into frontmatter map entries and body text.
     * Returns a pair of (frontmatter raw lines, body string).
     */
    private fun splitFrontmatter(raw: String): Pair<String, String> {
        val lines = raw.lines()

        // Must start with "---" to have frontmatter
        if (lines.isEmpty() || lines[0].trim() != FRONTMATTER_DELIMITER) {
            return Pair("", raw)
        }

        val closingIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (closingIndex == -1) {
            // No closing delimiter — treat entire file as body
            return Pair("", raw)
        }

        val frontmatterLines = lines.subList(1, closingIndex + 1)
        val bodyLines = lines.drop(closingIndex + 2)

        return Pair(frontmatterLines.joinToString("\n"), bodyLines.joinToString("\n"))
    }

    /**
     * Very lightweight YAML key-value parser — handles simple `key: value` lines only.
     */
    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()

        return frontmatter.lines()
            .mapNotNull { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex <= 0) return@mapNotNull null
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }
}
