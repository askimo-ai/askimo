/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.settings

import io.askimo.core.agent.domain.SkillDefinition
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for [deriveSkillMdRelativePath] — specifically the bug where an existing
 * folder-based skill whose entry file was still lowercase `skill.md` on disk got a *guessed*
 * uppercase `SKILL.md` save path instead of the real file's own path. On a case-sensitive
 * filesystem that would create a second sibling file rather than overwriting the original,
 * making edits appear lost (SkillRepository then picks between the two nondeterministically).
 */
class DeriveSkillMdRelativePathTest {

    private fun skillsDir(): Path = createTempDirectory("skills")

    @Test
    fun `existing folder skill with lowercase entry file is saved back to that same lowercase file`() {
        val skillsDir = skillsDir()
        val skillFolder = skillsDir.resolve("coding/reviewer").also { it.createDirectories() }
        val entryFile = skillFolder.resolve("skill.md").also { it.writeText("---\nname: Reviewer\n---\nContent") }

        val skill = SkillDefinition(
            relativePath = "coding/reviewer.md", // virtual form, as SkillRepository's folder-loader produces
            name = "Reviewer",
            content = "Content",
            absolutePath = entryFile,
        )

        val result = deriveSkillMdRelativePath(skill, skillsDir)

        assertEquals("coding/reviewer/skill.md", result, "Must write back to the real, existing lowercase file — not a guessed uppercase one")
    }

    @Test
    fun `existing folder skill with uppercase entry file is saved back to that same uppercase file`() {
        val skillsDir = skillsDir()
        val skillFolder = skillsDir.resolve("coding/reviewer").also { it.createDirectories() }
        val entryFile = skillFolder.resolve("SKILL.md").also { it.writeText("---\nname: Reviewer\n---\nContent") }

        val skill = SkillDefinition(
            relativePath = "coding/reviewer.md",
            name = "Reviewer",
            content = "Content",
            absolutePath = entryFile,
        )

        val result = deriveSkillMdRelativePath(skill, skillsDir)

        assertEquals("coding/reviewer/SKILL.md", result)
    }

    @Test
    fun `flat single-file skill not yet folder-based migrates into a new SKILL dot md folder`() {
        val skillsDir = skillsDir()
        val flatFile = skillsDir.resolve("coding/my-skill.md").also {
            it.parent.createDirectories()
            it.writeText("---\nname: My Skill\n---\nContent")
        }

        val skill = SkillDefinition(
            relativePath = "coding/my-skill.md",
            name = "My Skill",
            content = "Content",
            absolutePath = flatFile,
        )

        val result = deriveSkillMdRelativePath(skill, skillsDir)

        assertEquals("coding/my-skill/SKILL.md", result, "Flat skills must migrate into a fresh folder using the canonical SKILL.md casing")
    }
}
