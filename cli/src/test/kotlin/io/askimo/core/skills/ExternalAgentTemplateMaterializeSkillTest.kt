/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills

import io.askimo.core.agent.ExternalAgentTemplate
import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.logging.logger
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExternalAgentTemplateMaterializeSkillTest {

    /** Minimal concrete agent exposing the protected materialize* methods for testing. */
    private class FakeAgent : ExternalAgentTemplate() {
        override val log = logger<FakeAgent>()
        override val id = "fake"
        override val name = "Fake Agent"
        override val installUrl = ""

        override fun resolveAgentPath(): String? = null

        override fun buildCommand(
            agentPath: String,
            systemPrompt: String,
            userInput: String,
            effectiveWorkDir: File,
            resumeSessionId: String?,
        ): List<String> = emptyList()

        override fun parseStdoutLine(
            line: String,
            onToken: (String) -> Unit,
            onToolCall: (toolName: String, detail: String?) -> Unit,
            onStatus: (String) -> Unit,
            onThinking: (String) -> Unit,
            output: StringBuilder,
        ) {
            // no-op — not exercised by these tests
        }

        fun materializeFolder(skill: SkillDefinition, skillsRootDir: Path): AutoCloseable = materializeSkillFolder(skill, skillsRootDir)
        fun materializeSymlink(skill: SkillDefinition, skillsRootDir: Path): AutoCloseable = materializeSkillSymlink(skill, skillsRootDir)
    }

    @TempDir
    lateinit var sourceRoot: Path

    @TempDir
    lateinit var skillsRootDir: Path

    private val agent = FakeAgent()

    /**
     * Writes a real skill folder under [sourceRoot] at [categoryAndFolder] (e.g. `"pack1/reviewer"`)
     * and returns the [SkillDefinition] pointing at it, using the same virtual relativePath
     * convention `SkillRepository` produces (`<category>/<folderName>.md`).
     */
    private fun writeSkill(categoryAndFolder: String, extraFiles: Map<String, String> = emptyMap()): SkillDefinition {
        val skillDir = sourceRoot.resolve(categoryAndFolder)
        skillDir.createDirectories()
        skillDir.resolve("skill.md").writeText("---\nname: Test\n---\nContent")
        extraFiles.forEach { (relPath, content) ->
            val file = skillDir.resolve(relPath)
            file.parent.createDirectories()
            file.writeText(content)
        }

        val folderName = skillDir.fileName.toString()
        val parentRel = skillDir.parent.let { parent ->
            if (parent == sourceRoot) "" else sourceRoot.relativize(parent).toString().replace("\\", "/")
        }
        val virtualRelativePath = if (parentRel.isNotBlank()) "$parentRel/$folderName.md" else "$folderName.md"

        return SkillDefinition(
            relativePath = virtualRelativePath,
            name = "Test",
            content = "Content",
            absolutePath = skillDir.resolve("skill.md"),
        )
    }

    // ── materializeSkillFolder (copy) ────────────────────────────────────────

    @Nested
    inner class MaterializeFolder {

        @Test
        fun `materializes into a folder named after the skill's slug, not its leaf folder name`() {
            val skill = writeSkill("pack1/reviewer")
            agent.materializeFolder(skill, skillsRootDir)

            // Destination entry point is normalized to uppercase SKILL.md — see the dedicated
            // casing-normalization tests below — regardless of the source's own `skill.md` casing.
            assertTrue(Files.exists(skillsRootDir.resolve("pack1-reviewer/SKILL.md")))
            assertFalse(Files.exists(skillsRootDir.resolve("reviewer")), "Should not use the bare leaf folder name")
        }

        @Test
        fun `two skills with the same leaf folder name from different packs both materialize without collision`() {
            val skillA = writeSkill("pack1/reviewer")
            val skillB = writeSkill("pack2/reviewer")

            agent.materializeFolder(skillA, skillsRootDir)
            agent.materializeFolder(skillB, skillsRootDir)

            assertNotEquals(skillA.slug, skillB.slug)
            assertTrue(Files.exists(skillsRootDir.resolve("${skillA.slug}/SKILL.md")), "First skill must be materialized")
            assertTrue(
                Files.exists(skillsRootDir.resolve("${skillB.slug}/SKILL.md")),
                "Second skill must ALSO be materialized — this is the collision bug being fixed",
            )
        }

        @Test
        fun `supplemental files are copied alongside skill dot md`() {
            val skill = writeSkill("coding/reviewer", extraFiles = mapOf("examples.md" to "Examples", "helpers/Util.java" to "// util"))
            agent.materializeFolder(skill, skillsRootDir)

            val target = skillsRootDir.resolve(skill.slug)
            assertTrue(Files.exists(target.resolve("SKILL.md")))
            assertTrue(Files.exists(target.resolve("examples.md")))
            assertTrue(Files.exists(target.resolve("helpers/Util.java")))
        }

        @Test
        fun `entry point is normalized to uppercase SKILL dot md regardless of source casing`() {
            // The test's own source fixture (writeSkill) still writes a lowercase `skill.md` —
            // SkillRepository.SKILL_ENTRY (now the canonical "SKILL.md") is matched
            // case-insensitively when *reading* — but the target agent's native discovery
            // (Claude Code, Codex, Antigravity) requires the file to literally be named
            // `SKILL.md`. On a case-sensitive filesystem a preserved-casing copy would be
            // invisible to it.
            val skill = writeSkill("coding/reviewer")
            agent.materializeFolder(skill, skillsRootDir)

            val target = skillsRootDir.resolve(skill.slug)
            assertTrue(Files.exists(target.resolve("SKILL.md")), "Entry point must be normalized to uppercase SKILL.md")
            // Files.exists() is case-insensitive on the default macOS/Windows filesystem, so it
            // can't distinguish "SKILL.md" from "skill.md" there — list the directory instead to
            // check the *literal* on-disk filename regardless of the test filesystem's own
            // case-sensitivity.
            val actualNames = Files.list(target).use { s -> s.map { it.fileName.toString() }.toList() }
            assertEquals(1, actualNames.count { it.equals("SKILL.md", ignoreCase = true) }, "Exactly one entry-point file expected")
            assertTrue("SKILL.md" in actualNames, "On-disk entry point must literally be named SKILL.md, was: $actualNames")
            assertFalse("skill.md" in actualNames, "Lowercase source name must not be preserved for the entry point, was: $actualNames")
        }

        @Test
        fun `only the canonical entry file wins when both skill dot md and SKILL dot md exist on a case-sensitive filesystem`() {
            // On a case-insensitive filesystem (default macOS/Windows), writing both names below
            // just overwrites the same physical file — there is nothing to disambiguate, so the
            // test is meaningless there and is skipped.
            val skill = writeSkill("coding/reviewer")
            val skillDir = skill.absolutePath.parent
            skillDir.resolve("SKILL.md").writeText("---\nname: Rogue\n---\nRogue content")
            val distinctFilesOnDisk = Files.list(skillDir).use { s ->
                s.map { it.fileName.toString() }.toList()
            }
            assumeTrue(
                distinctFilesOnDisk.count { it.equals("SKILL.md", ignoreCase = true) } == 2,
                "Filesystem is case-insensitive — skill.md/SKILL.md collapse to one file, nothing to test here",
            )

            agent.materializeFolder(skill, skillsRootDir)

            val target = skillsRootDir.resolve(skill.slug)
            val materializedNames = Files.list(target).use { s -> s.map { it.fileName.toString() }.toList() }
            assertEquals(
                1,
                materializedNames.count { it.equals("SKILL.md", ignoreCase = true) },
                "Exactly one entry-point file must be materialized, was: $materializedNames",
            )
            // Content must come from skill.absolutePath (the canonical entry SkillRepository
            // selected), never from the rogue duplicate — regardless of Files.walk() order.
            assertEquals("---\nname: Test\n---\nContent", Files.readString(target.resolve("SKILL.md")))
        }

        @Test
        fun `dot git directory is excluded from the copy`() {
            val skill = writeSkill("coding/reviewer")
            val gitDir = skill.absolutePath.parent.resolve(".git")
            gitDir.createDirectories()
            gitDir.resolve("config").writeText("[core]")

            agent.materializeFolder(skill, skillsRootDir)

            assertFalse(Files.exists(skillsRootDir.resolve("${skill.slug}/.git")))
        }

        @Test
        fun `pre-existing folder with the same slug is left untouched, never clobbered`() {
            val skill = writeSkill("coding/reviewer")
            val target = skillsRootDir.resolve(skill.slug)
            target.createDirectories()
            target.resolve("user-owned.txt").writeText("do not touch")

            agent.materializeFolder(skill, skillsRootDir)

            assertTrue(Files.exists(target.resolve("user-owned.txt")), "Pre-existing user content must survive")
            assertFalse(Files.exists(target.resolve("SKILL.md")), "Should not have copied into a folder that already existed")
        }

        @Test
        fun `close() removes only what was materialized`() {
            val skill = writeSkill("coding/reviewer")
            val cleanup = agent.materializeFolder(skill, skillsRootDir)
            val target = skillsRootDir.resolve(skill.slug)
            assertTrue(Files.exists(target))

            cleanup.close()

            assertFalse(Files.exists(target), "Materialized folder should be removed on cleanup")
        }

        @Test
        fun `close() is a no-op when the folder pre-existed (never deletes user-owned content)`() {
            val skill = writeSkill("coding/reviewer")
            val target = skillsRootDir.resolve(skill.slug)
            target.createDirectories()
            target.resolve("user-owned.txt").writeText("do not touch")

            val cleanup = agent.materializeFolder(skill, skillsRootDir)
            cleanup.close()

            assertTrue(Files.exists(target.resolve("user-owned.txt")), "Cleanup must never delete a folder Askimo didn't create")
        }
    }

    // ── materializeSkillSymlink ───────────────────────────────────────────────

    @Nested
    inner class MaterializeSymlink {

        @Test
        fun `symlinks into a link named after the skill's slug, not its leaf folder name`() {
            val skill = writeSkill("pack1/reviewer")
            agent.materializeSymlink(skill, skillsRootDir)

            val linkPath = skillsRootDir.resolve(skill.slug)
            assertTrue(Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(skillsRootDir.resolve("reviewer"), LinkOption.NOFOLLOW_LINKS))
        }

        @Test
        fun `two skills with the same leaf folder name from different packs both get symlinked without collision`() {
            val skillA = writeSkill("pack1/reviewer")
            val skillB = writeSkill("pack2/reviewer")

            agent.materializeSymlink(skillA, skillsRootDir)
            agent.materializeSymlink(skillB, skillsRootDir)

            assertTrue(Files.exists(skillsRootDir.resolve(skillA.slug), LinkOption.NOFOLLOW_LINKS))
            assertTrue(
                Files.exists(skillsRootDir.resolve(skillB.slug), LinkOption.NOFOLLOW_LINKS),
                "Second skill's symlink must ALSO be created — this is the collision bug being fixed",
            )
        }

        @Test
        fun `symlink points at the skill's own source folder`() {
            val skill = writeSkill("coding/reviewer")
            agent.materializeSymlink(skill, skillsRootDir)

            val linkPath = skillsRootDir.resolve(skill.slug)
            val target = Files.readSymbolicLink(linkPath)
            assertEquals(skill.absolutePath.parent, target)
        }

        @Test
        fun `close() removes only the symlink, not the source folder`() {
            val skill = writeSkill("coding/reviewer")
            val cleanup = agent.materializeSymlink(skill, skillsRootDir)
            val linkPath = skillsRootDir.resolve(skill.slug)
            assertTrue(Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS))

            cleanup.close()

            assertFalse(Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS), "Symlink should be removed on cleanup")
            assertTrue(Files.exists(skill.absolutePath), "Source skill file must be untouched")
        }

        @Test
        fun `pre-existing folder or link with the same slug is left untouched`() {
            val skill = writeSkill("coding/reviewer")
            val existing = skillsRootDir.resolve(skill.slug)
            existing.createDirectories()
            existing.resolve("user-owned.txt").writeText("do not touch")

            agent.materializeSymlink(skill, skillsRootDir)

            assertTrue(Files.exists(existing.resolve("user-owned.txt")))
            assertFalse(Files.isSymbolicLink(existing), "Should not have replaced the user's own folder with a symlink")
        }
    }
}
