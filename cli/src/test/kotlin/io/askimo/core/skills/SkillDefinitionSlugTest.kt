/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills

import io.askimo.core.agent.domain.SkillDefinition
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * ## `SkillDefinition.slug` spec
 *
 * [SkillDefinition.slug] is the stable, filesystem/agent-safe identifier used as the
 * destination folder name whenever a skill is materialized into an external agent's native
 * skill directory (see `ExternalAgentTemplate.materializeSkillFolder`/`materializeSkillSymlink`).
 *
 * It must:
 * - Be derived from the **full category-qualified path**, not just the leaf folder name,
 *   so skills with the same leaf name in different categories never collide.
 * - Normalize to lowercase kebab-case ASCII, since imported packs (GitHub/zip) can contain
 *   folder names with spaces, unicode, or mixed case.
 * - Never be blank, and never exceed a sane max length.
 */
class SkillDefinitionSlugTest {

    private fun skill(relativePath: String): SkillDefinition = SkillDefinition(
        relativePath = relativePath,
        name = "Test",
        content = "Content",
        absolutePath = Path.of("/tmp/unused"),
    )

    @Test
    fun `root-level skill slug is just the file base name`() {
        assertEquals("reviewer", skill("reviewer.md").slug)
    }

    @Test
    fun `single category folds into slug with a hyphen`() {
        assertEquals("coding-reviewer", skill("coding/reviewer.md").slug)
    }

    @Test
    fun `multi-level category folds every segment into the slug`() {
        assertEquals("a-b-c-deep-skill", skill("a/b/c/deep-skill.md").slug)
    }

    @Test
    fun `windows-style backslash separators are normalized like forward slashes`() {
        assertEquals("coding-reviewer", skill("coding\\reviewer.md").slug)
    }

    @Test
    fun `spaces and punctuation are sanitized to single hyphens`() {
        assertEquals("my-skills-code-review", skill("My Skills/Code Review!.md").slug)
    }

    @Test
    fun `uppercase is lowercased`() {
        assertEquals("coding-reviewer", skill("Coding/Reviewer.md").slug)
    }

    @Test
    fun `accented characters are replaced with hyphens, not kept as-is`() {
        // Individual non a-z0-9 characters become single hyphens; runs of hyphens collapse to one.
        assertEquals("h-llo-w-rld", skill("héllo/wörld.md").slug)
    }

    @Test
    fun `fully non-ascii path falls back to a non-empty default`() {
        assertEquals("skill", skill("日本語/スキル.md").slug)
    }

    @Test
    fun `repeated separators collapse to a single hyphen`() {
        assertEquals("coding-reviewer", skill("coding//reviewer.md").slug)
    }

    @Test
    fun `blank result falls back to a non-empty default`() {
        assertEquals("skill", skill("!!!.md").slug)
        assertEquals("skill", skill(".md").slug)
    }

    @Test
    fun `slug is capped at a reasonable max length`() {
        val longSegment = "a".repeat(200)
        val slug = skill("$longSegment.md").slug
        assertEquals(80, slug.length)
    }

    @Test
    fun `two skills with the same leaf name but different categories produce different slugs`() {
        val fromPack1 = skill("pack1/reviewer.md").slug
        val fromPack2 = skill("pack2/reviewer.md").slug
        assertNotEquals(fromPack1, fromPack2)
        assertEquals("pack1-reviewer", fromPack1)
        assertEquals("pack2-reviewer", fromPack2)
    }

    @Test
    fun `slug is deterministic for the same relative path`() {
        assertEquals(skill("coding/reviewer.md").slug, skill("coding/reviewer.md").slug)
    }
}
