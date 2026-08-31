/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills

import io.askimo.core.agent.domain.SkillTreeNode
import io.askimo.core.agent.repository.SkillRepository
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ## Skill Storage Spec
 *
 * ### Convention
 * A **skill** is always a **folder** that contains a `skill.md` entry point file
 * (matched **case-insensitively** — `SKILL.md`, `Skill.md`, `skill.md` all work).
 *
 * Everything inside a skill folder (all files and sub-folders recursively) is
 * supplemental to that skill. Direct `.md` siblings (excluding reserved files) are
 * merged into the skill content. Non-`.md` files are preserved on import.
 *
 * Plain folders without any `skill.md` are transparent **category** containers.
 * Sub-folders of a skill folder are NOT treated as separate skills.
 *
 * Standalone `.md` files (not inside a skill folder) are NOT skills.
 *
 * ```
 * skills/
 * ├── coding/                  ← plain category (not a skill)
 * │   └── reviewer/            ← skill folder (has skill.md) → skill "reviewer"
 * │       ├── skill.md         ← entry point
 * │       ├── examples.md      ← merged as supplemental context
 * │       └── CLAUDE.md        ← reserved, ignored
 * └── formatter/               ← skill folder → skill "formatter"
 *     └── skill.md
 * ```
 *
 * ### `getSkillsOnly()` — launcher view
 * Returns only **selectable** skills (skill folders only):
 * - One entry per skill folder (folder with `skill.md`)
 * - Plain category folders are transparent — traversed but not listed
 * - Sub-skill-folders inside a skill folder are NOT listed separately
 *
 * ### `getTree()` — settings / file-manager view
 * Returns the full folder tree. Skill folders appear as `Category(isSkillFolder=true)`.
 * Plain folders appear as `Category(isSkillFolder=false)`. No `Leaf` for standalone `.md`.
 *
 * ### `getAll()` — flat list (internal)
 * Returns supplemental `.md` files (non-reserved, non-entry-point) across skill folders.
 */
class SkillRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun repo(): SkillRepository = SkillRepository()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun skillsDir(): Path = tempDir.resolve("personal/skills").also { it.createDirectories() }

    private fun write(relativePath: String, content: String = "# Content") {
        val file = skillsDir().resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun withSkillsDir(block: () -> Unit) {
        AskimoHome.withTestBase(tempDir).use { block() }
    }

    // ── getSkillsOnly ─────────────────────────────────────────────────────────

    @Nested
    inner class GetSkillsOnly {

        @Test
        fun `empty skills dir returns empty list`() = withSkillsDir {
            skillsDir()
            assertTrue(repo().getSkillsOnly().isEmpty())
        }

        @Test
        fun `standalone md file is NOT a skill`() = withSkillsDir {
            write("my-skill.md", "---\nname: My Skill\n---\nContent")
            assertTrue(repo().getSkillsOnly().isEmpty())
        }

        @Test
        fun `skill folder with skill dot md is returned as single skill`() = withSkillsDir {
            write("code-reviewer/skill.md", "---\nname: Code Reviewer\n---\nYou are a code reviewer.")
            write("code-reviewer/examples.md", "# Examples")
            val skills = repo().getSkillsOnly()
            assertEquals(1, skills.size)
            assertEquals("Code Reviewer", skills[0].name)
        }

        @Test
        fun `skill folder entry point is case-insensitive`() = withSkillsDir {
            write("reviewer/SKILL.md", "---\nname: Reviewer\n---\nContent")
            val skills = repo().getSkillsOnly()
            assertEquals(1, skills.size)
            assertEquals("Reviewer", skills[0].name)
        }

        @Test
        fun `skill folder sibling md files are NOT listed as separate skills`() = withSkillsDir {
            write("code-reviewer/skill.md", "---\nname: Code Reviewer\n---\nPrompt")
            write("code-reviewer/examples.md", "# Examples")
            write("code-reviewer/patterns.md", "# Patterns")
            val skills = repo().getSkillsOnly()
            assertEquals(1, skills.size)
            assertEquals("Code Reviewer", skills[0].name)
        }

        @Test
        fun `skill folder supplemental content is merged into skill content`() = withSkillsDir {
            write("code-reviewer/skill.md", "---\nname: Code Reviewer\n---\nMain prompt.")
            write("code-reviewer/examples.md", "Extra context.")
            val skill = repo().getSkillsOnly().single()
            assertTrue(skill.content.contains("Main prompt."), "Should contain entry point body")
            assertTrue(skill.content.contains("Extra context."), "Should contain supplemental .md body")
            // Supplemental structure headers should be present
            assertTrue(skill.content.contains("Skill Folder Structure"), "Should contain folder structure section")
            assertTrue(skill.content.contains("Supplemental Files"), "Should contain supplemental files section")
            assertTrue(skill.content.contains("`examples.md`"), "Should list examples.md in file tree")
        }

        @Test
        fun `skill folder with non-md supplemental files includes them in content`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nReview code.")
            write("reviewer/rules.txt", "Rule 1: No magic numbers.")
            write("reviewer/examples/Good.java", "// Good example")
            val skill = repo().getSkillsOnly().single()
            assertTrue(skill.content.contains("Review code."), "Should contain entry point body")
            assertTrue(skill.content.contains("Rule 1: No magic numbers."), "Should contain txt file content")
            assertTrue(skill.content.contains("// Good example"), "Should contain java file content")
            assertTrue(skill.content.contains("`rules.txt`"), "Should list rules.txt in structure")
            assertTrue(skill.content.contains("`examples/Good.java`"), "Should list nested java file")
        }

        @Test
        fun `skill folder structure section lists all files`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nPrompt.")
            write("reviewer/examples.md", "Examples")
            write("reviewer/notes.txt", "Notes")
            val skill = repo().getSkillsOnly().single()
            // Structure section lists skill.md and all supplemental files
            val structureSection = skill.content.substringAfter("Skill Folder Structure").substringBefore("Supplemental Files")
            assertTrue(structureSection.contains("`skill.md`"), "Should list skill.md as entry point")
            assertTrue(structureSection.contains("`examples.md`"), "Should list examples.md")
            assertTrue(structureSection.contains("`notes.txt`"), "Should list notes.txt")
        }

        @Test
        fun `supplemental md files have frontmatter stripped in content`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nPrompt.")
            write("reviewer/examples.md", "---\nname: Examples\n---\nExamples body only.")
            val skill = repo().getSkillsOnly().single()
            // Frontmatter fields should NOT appear in supplemental content
            assertFalse(skill.content.contains("name: Examples"), "Frontmatter should be stripped from supplemental .md")
            assertTrue(skill.content.contains("Examples body only."), "Body should be included")
        }

        @Test
        fun `reserved files inside skill folder are NOT merged as context`() = withSkillsDir {
            write("code-reviewer/skill.md", "---\nname: Code Reviewer\n---\nPrompt.")
            write("code-reviewer/CLAUDE.md", "Claude project context")
            write("code-reviewer/AGENTS.md", "Agents context")
            write("code-reviewer/README.md", "Readme docs")
            val skill = repo().getSkillsOnly().single()
            assertFalse(skill.content.contains("Claude project context"))
            assertFalse(skill.content.contains("Agents context"))
            assertFalse(skill.content.contains("Readme docs"))
        }

        @Test
        fun `plain folder without skill dot md is NOT a skill`() = withSkillsDir {
            write("utils/formatter.md", "---\nname: Formatter\n---\nContent")
            assertTrue(repo().getSkillsOnly().isEmpty())
        }

        @Test
        fun `plain category folder is transparent - skills inside are found`() = withSkillsDir {
            write("coding/reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            val skills = repo().getSkillsOnly()
            assertEquals(1, skills.size)
            assertEquals("Reviewer", skills[0].name)
        }

        @Test
        fun `multiple skill folders across categories`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            write("reviewer/examples.md", "Examples")
            write("advanced/refactor/skill.md", "---\nname: Refactor\n---\nContent")

            val names = repo().getSkillsOnly().map { it.name }.toSet()
            assertEquals(setOf("Reviewer", "Refactor"), names)
        }

        @Test
        fun `sub-folder of skill folder is NOT a separate skill`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            write("reviewer/sub/skill.md", "---\nname: Sub Skill\n---\nContent")
            // Only the parent skill folder should be returned
            val skills = repo().getSkillsOnly()
            assertEquals(1, skills.size)
            assertEquals("Reviewer", skills[0].name)
        }

        @Test
        fun `skill folder name comes from folder when frontmatter name is blank`() = withSkillsDir {
            write("my-cool-skill/skill.md", "# Just markdown, no frontmatter name")
            val skills = repo().getSkillsOnly()
            assertEquals(1, skills.size)
            assertEquals("my-cool-skill", skills[0].name)
        }

        @Test
        fun `skill folder category is the parent folder not the skill folder itself`() = withSkillsDir {
            write("coding/reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            val skill = repo().getSkillsOnly().single()
            assertEquals("coding", skill.category)
            assertEquals(listOf("coding"), skill.categoryPath)
        }

        @Test
        fun `root-level skill folder has empty category`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            val skill = repo().getSkillsOnly().single()
            assertEquals("", skill.category)
            assertEquals(emptyList<String>(), skill.categoryPath)
        }
    }

    // ── getAll ────────────────────────────────────────────────────────────────

    @Nested
    inner class GetAll {

        @Test
        fun `reserved filenames and entry point are excluded`() = withSkillsDir {
            write("skill-a/skill.md", "Entry")
            write("skill-a/CLAUDE.md", "Nope")
            write("skill-a/GEMINI.md", "Nope")
            write("skill-a/AGENTS.md", "Nope")
            write("skill-a/README.md", "Nope")
            write("skill-a/supplement.md", "Included")

            val names = repo().getAll().map { it.relativePath }
            assertTrue(names.none { it.endsWith("skill.md") }, "skill.md should be excluded")
            assertTrue(names.none { it.endsWith("CLAUDE.md") })
            assertTrue(names.none { it.endsWith("GEMINI.md") })
            assertTrue(names.none { it.endsWith("AGENTS.md") })
            assertTrue(names.none { it.endsWith("README.md") })
            assertTrue(names.any { it.endsWith("supplement.md") })
        }
    }

    // ── getTree ───────────────────────────────────────────────────────────────

    @Nested
    inner class GetTree {

        @Test
        fun `root-level skill folder has no category wrapper`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            val tree = repo().getTree()
            assertEquals(1, tree.size)
            val node = tree[0] as SkillTreeNode.Category
            assertEquals(true, node.isSkillFolder)
            assertNotNull(node.skill)
        }

        @Test
        fun `skill one level deep - direct parent shown as category`() = withSkillsDir {
            write("coding/reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            val tree = repo().getTree()
            // top-level: "coding" category
            assertEquals(1, tree.size)
            val category = tree[0] as SkillTreeNode.Category
            assertEquals("coding", category.name)
            assertEquals(false, category.isSkillFolder)
            // child: "reviewer" skill
            assertEquals(1, category.children.size)
            val skill = category.children[0] as SkillTreeNode.Category
            assertEquals(true, skill.isSkillFolder)
        }

        @Test
        fun `skill deeply nested - only immediate parent shown as category`() = withSkillsDir {
            write("a/b/c/reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            val tree = repo().getTree()
            // only "c" (immediate parent) should appear, not "a" or "b"
            assertEquals(1, tree.size)
            val category = tree[0] as SkillTreeNode.Category
            assertEquals("c", category.name)
            assertEquals(false, category.isSkillFolder)
            val skill = category.children[0] as SkillTreeNode.Category
            assertEquals(true, skill.isSkillFolder)
        }

        @Test
        fun `skill folder children include sibling md files as Leaf nodes`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            write("reviewer/examples.md", "Examples")
            val tree = repo().getTree()
            val skillNode = tree[0] as SkillTreeNode.Category
            assertTrue(skillNode.children.any { it is SkillTreeNode.Leaf })
        }

        @Test
        fun `skill folder children include sub-directories`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            write("reviewer/src/Helper.java", "// helper")
            val tree = repo().getTree()
            val skillNode = tree[0] as SkillTreeNode.Category
            assertTrue(skillNode.children.any { it is SkillTreeNode.Category })
        }

        @Test
        fun `two skills under same immediate parent grouped under one category`() = withSkillsDir {
            write("coding/reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            write("coding/formatter/skill.md", "---\nname: Formatter\n---\nContent")
            val tree = repo().getTree()
            assertEquals(1, tree.size)
            val category = tree[0] as SkillTreeNode.Category
            assertEquals("coding", category.name)
            assertEquals(2, category.children.size)
        }
    }

    // ── save / delete / findByPath ────────────────────────────────────────────

    @Nested
    inner class SaveAndDelete {

        @Test
        fun `save persists file and findByPath retrieves the skill`() = withSkillsDir {
            skillsDir()
            // Save the skill entry file (skill.md inside a folder)
            repo().save("test-skill/skill.md", "---\nname: Test Skill\n---\nContent")
            // findByPath uses virtual relative path: "test-skill.md"
            val found = repo().findByPath("test-skill.md")
            assertNotNull(found)
            assertEquals("Test Skill", found.name)
        }

        @Test
        fun `delete removes file`() = withSkillsDir {
            skillsDir()
            repo().save("to-delete/skill.md", "---\nname: Delete Me\n---\nContent")
            val deleted = repo().delete("to-delete/skill.md")
            assertTrue(deleted)
        }

        @Test
        fun `delete prunes the skill folder itself`() = withSkillsDir {
            write("my-skill/skill.md", "content")
            repo().delete("my-skill/skill.md")
            assertFalse(Files.exists(skillsDir().resolve("my-skill")), "Skill folder should be removed")
        }

        @Test
        fun `delete prunes skill folder together with supplemental files`() = withSkillsDir {
            write("my-skill/skill.md", "content")
            write("my-skill/examples.md", "examples")
            write("my-skill/helper.java", "// helper")
            repo().delete("my-skill/skill.md")
            assertFalse(Files.exists(skillsDir().resolve("my-skill")), "Skill folder including supplementals should be removed")
        }

        @Test
        fun `delete prunes empty category parent after last skill removed`() = withSkillsDir {
            // parent1/parent2/skill-a is the only skill under parent1
            write("parent1/parent2/skill-a/skill.md", "content")
            repo().delete("parent1/parent2/skill-a/skill.md")
            assertFalse(Files.exists(skillsDir().resolve("parent1")), "parent1 should be pruned as it has no skills")
            assertFalse(Files.exists(skillsDir().resolve("parent1/parent2")), "parent2 should be pruned too")
        }

        @Test
        fun `delete does NOT prune category parent when sibling skill still exists`() = withSkillsDir {
            write("coding/skill-a/skill.md", "content")
            write("coding/skill-b/skill.md", "content")
            repo().delete("coding/skill-a/skill.md")
            assertTrue(Files.exists(skillsDir().resolve("coding")), "coding category should remain — skill-b still lives there")
            assertTrue(Files.exists(skillsDir().resolve("coding/skill-b/skill.md")), "skill-b must be untouched")
            assertFalse(Files.exists(skillsDir().resolve("coding/skill-a")), "skill-a folder should be gone")
        }

        @Test
        fun `delete partially prunes deep category chain up to surviving sibling`() = withSkillsDir {
            // parent/cat-a/skill-a  and  parent/cat-b/skill-b
            // deleting skill-a should prune cat-a but leave parent and cat-b/skill-b intact
            write("parent/cat-a/skill-a/skill.md", "content")
            write("parent/cat-b/skill-b/skill.md", "content")
            repo().delete("parent/cat-a/skill-a/skill.md")
            assertFalse(Files.exists(skillsDir().resolve("parent/cat-a")), "cat-a should be pruned")
            assertTrue(Files.exists(skillsDir().resolve("parent")), "parent should survive — cat-b still has a skill")
            assertTrue(Files.exists(skillsDir().resolve("parent/cat-b/skill-b/skill.md")), "skill-b must be untouched")
        }

        @Test
        fun `delete returns false for non-existent file and does not prune`() = withSkillsDir {
            write("my-skill/skill.md", "content")
            val result = repo().delete("my-skill/nonexistent.md")
            assertFalse(result)
            assertTrue(Files.exists(skillsDir().resolve("my-skill/skill.md")), "skill.md should be untouched")
        }
    }

    // ── importFromDirectory ───────────────────────────────────────────────────

    @Nested
    inner class ImportFromDirectory {

        @TempDir
        lateinit var sourceDir: Path

        private fun src(relativePath: String, content: String = "content") {
            val file = sourceDir.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content)
        }

        private fun imported(relativePath: String): Boolean = skillsDir().resolve("imported/$relativePath").let { Files.exists(it) }

        @Test
        fun `skill folder with skill dot md is imported`() = withSkillsDir {
            src("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            repo().importFromDirectory(sourceDir, "imported")
            assertTrue(imported("reviewer/skill.md"))
        }

        @Test
        fun `skill folder sibling non-md files are imported`() = withSkillsDir {
            src("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            src("reviewer/examples.java", "// Java example")
            src("reviewer/notes.txt", "some notes")
            src("reviewer/src/main/Helper.java", "// Helper")
            src("reviewer/resources/config.json", "{}")
            repo().importFromDirectory(sourceDir, "imported")
            assertTrue(imported("reviewer/skill.md"))
            assertTrue(imported("reviewer/examples.java"))
            assertTrue(imported("reviewer/notes.txt"))
            assertTrue(imported("reviewer/src/main/Helper.java"))
            assertTrue(imported("reviewer/resources/config.json"))
        }

        @Test
        fun `skill folder subdirectory files are imported`() = withSkillsDir {
            src("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            src("reviewer/examples/Foo.java", "// Foo")
            src("reviewer/examples/Bar.java", "// Bar")
            repo().importFromDirectory(sourceDir, "imported")
            assertTrue(imported("reviewer/examples/Foo.java"))
            assertTrue(imported("reviewer/examples/Bar.java"))
        }

        @Test
        fun `standalone md file is NOT imported (not a skill)`() = withSkillsDir {
            src("simple-skill.md", "---\nname: Simple\n---\nContent")
            repo().importFromDirectory(sourceDir, "imported")
            assertFalse(imported("simple-skill.md"))
        }

        @Test
        fun `dot git directory is excluded`() = withSkillsDir {
            src("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            src(".git/config", "[core]")
            src(".git/HEAD", "ref: refs/heads/main")
            repo().importFromDirectory(sourceDir, "imported")
            assertTrue(imported("reviewer/skill.md"))
            assertFalse(imported(".git/config"))
            assertFalse(imported(".git/HEAD"))
        }

        @Test
        fun `directories with no skills are excluded`() = withSkillsDir {
            src("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            src("ci/build.sh", "#!/bin/bash")
            src("ci/deploy.sh", "#!/bin/bash")
            repo().importFromDirectory(sourceDir, "imported")
            assertTrue(imported("reviewer/skill.md"))
            assertFalse(imported("ci/build.sh"))
            assertFalse(imported("ci/deploy.sh"))
        }

        @Test
        fun `reserved files at root are excluded (not skill folders)`() = withSkillsDir {
            src("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            src("README.md", "# This repo")
            src("AGENTS.md", "Agent instructions")
            repo().importFromDirectory(sourceDir, "imported")
            assertTrue(imported("reviewer/skill.md"))
            assertFalse(imported("README.md"))
            assertFalse(imported("AGENTS.md"))
        }

        @Test
        fun `returns correct skill count - only skill folders counted`() = withSkillsDir {
            src("reviewer/skill.md", "# Reviewer")
            src("formatter/skill.md", "# Formatter")
            src("simple.md", "# Not a skill - ignored")
            src("README.md", "# Ignored")
            val count = repo().importFromDirectory(sourceDir, "imported")
            assertEquals(2, count) // only the 2 skill folders
        }

        @Test
        fun `multiple skill folders all imported`() = withSkillsDir {
            src("reviewer/skill.md", "# Reviewer")
            src("reviewer/examples.java", "// example")
            src("formatter/skill.md", "# Formatter")
            src("formatter/rules/style.txt", "style rules")
            repo().importFromDirectory(sourceDir, "imported")
            assertTrue(imported("reviewer/skill.md"))
            assertTrue(imported("reviewer/examples.java"))
            assertTrue(imported("formatter/skill.md"))
            assertTrue(imported("formatter/rules/style.txt"))
        }

        @Test
        fun `sub-skill-folder inside skill folder is NOT imported as separate skill`() = withSkillsDir {
            src("reviewer/skill.md", "# Reviewer")
            src("reviewer/sub/skill.md", "# Sub - part of reviewer, not separate")
            val count = repo().importFromDirectory(sourceDir, "imported")
            assertEquals(1, count) // only "reviewer" is a skill
            assertTrue(imported("reviewer/skill.md"))
            assertTrue(imported("reviewer/sub/skill.md")) // file is copied as supplemental
        }

        @Test
        fun `case-insensitive entry point detection on import`() = withSkillsDir {
            src("reviewer/SKILL.md", "---\nname: Reviewer\n---\nContent")
            val count = repo().importFromDirectory(sourceDir, "imported")
            assertEquals(1, count)
            assertTrue(imported("reviewer/SKILL.md"))
        }
    }

    // ── CountSkills ───────────────────────────────────────────────────────────

    @Nested
    inner class CountSkills {

        @Test
        fun `empty directory returns 0`() = withSkillsDir {
            skillsDir()
            assertEquals(0, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `single root-level skill folder counts as 1`() = withSkillsDir {
            write("my-skill/skill.md", "---\nname: Test\n---\nContent")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `multiple root-level skill folders count individually`() = withSkillsDir {
            write("skill-a/skill.md", "content")
            write("skill-b/skill.md", "content")
            write("skill-c/skill.md", "content")
            assertEquals(3, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `skill nested inside category folder is counted`() = withSkillsDir {
            write("coding/reviewer/skill.md", "content")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `multiple skills across categories`() = withSkillsDir {
            write("coding/reviewer/skill.md", "content")
            write("coding/formatter/skill.md", "content")
            write("writing/summarizer/skill.md", "content")
            write("root-skill/skill.md", "content")
            assertEquals(4, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `sub-folder inside a skill folder is NOT a separate skill`() = withSkillsDir {
            write("reviewer/skill.md", "content")
            write("reviewer/helpers/extra.md", "extra content")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `sub-folder with skill-md inside a skill folder is NOT counted separately`() = withSkillsDir {
            write("parent-skill/skill.md", "content")
            write("parent-skill/nested/skill.md", "content")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `git directory is excluded`() = withSkillsDir {
            write("real-skill/skill.md", "content")
            val gitSkill = skillsDir().resolve(".git/some/path")
            Files.createDirectories(gitSkill)
            Files.writeString(gitSkill.resolve("skill.md"), "content")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `hidden directories are skipped`() = withSkillsDir {
            write("visible-skill/skill.md", "content")
            val hidden = skillsDir().resolve(".hidden/sneaky")
            Files.createDirectories(hidden)
            Files.writeString(hidden.resolve("skill.md"), "content")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `reserved files alone do not count as skills`() = withSkillsDir {
            write("not-a-skill/CLAUDE.md", "content")
            write("not-a-skill/README.md", "content")
            assertEquals(0, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `skill-md matching is case-insensitive`() = withSkillsDir {
            write("case-skill/SKILL.MD", "content")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `deeply nested category structure counts correctly`() = withSkillsDir {
            write("a/b/c/deep-skill/skill.md", "content")
            assertEquals(1, SkillRepository.countSkills(skillsDir()))
        }

        @Test
        fun `category folder without skills returns 0`() = withSkillsDir {
            write("empty-category/README.md", "readme")
            assertEquals(0, SkillRepository.countSkills(skillsDir()))
        }
    }

    // ── Hidden directory filtering ────────────────────────────────────────────

    @Nested
    inner class HiddenDirectoryFiltering {

        @Test
        fun `getAll - files inside dot-git are excluded`() = withSkillsDir {
            write("reviewer/skill-a.md", "---\nname: Skill A\n---\nContent")
            val gitDir = skillsDir().resolve(".git")
            gitDir.createDirectories()
            gitDir.resolve("config").writeText("[core]")
            gitDir.resolve("COMMIT_EDITMSG").writeText("# commit")

            val names = repo().getAll().map { it.relativePath }
            assertTrue(names.none { it.contains(".git") }, "Expected no .git files, got: $names")
        }

        @Test
        fun `getAll - files inside dot-github are excluded`() = withSkillsDir {
            write("my-skill/skill.md", "---\nname: My Skill\n---\nContent")
            write("my-skill/notes.md", "notes")
            val githubDir = skillsDir().resolve(".github")
            githubDir.createDirectories()
            githubDir.resolve("PULL_REQUEST_TEMPLATE.md").writeText("# PR")

            val names = repo().getAll().map { it.relativePath }
            assertTrue(names.none { it.contains(".github") }, "Expected no .github files, got: $names")
        }

        @Test
        fun `getTree - dot-git directory does not appear as Category node`() = withSkillsDir {
            write("reviewer/skill.md", "---\nname: Reviewer\n---\nContent")
            val gitDir = skillsDir().resolve("reviewer/.git")
            gitDir.createDirectories()
            gitDir.resolve("config").writeText("[core]")

            val tree = repo().getTree()
            fun collectNames(nodes: List<SkillTreeNode>): List<String> = nodes.flatMap { node ->
                when (node) {
                    is SkillTreeNode.Category -> listOf(node.name) + collectNames(node.children)
                    is SkillTreeNode.Leaf -> emptyList()
                }
            }
            val categoryNames = collectNames(tree)
            assertFalse(categoryNames.any { it == ".git" }, "Expected no .git category, got: $categoryNames")
            assertTrue(categoryNames.any { it.lowercase() == "reviewer" }, "Expected 'reviewer' category, got: $categoryNames")
        }

        @Test
        fun `getSkillsOnly - skills in dot-hidden dirs are excluded`() = withSkillsDir {
            write("real-skill/skill.md", "---\nname: Real\n---\nContent")
            val hiddenDir = skillsDir().resolve(".hidden")
            hiddenDir.createDirectories()
            hiddenDir.resolve("skill.md").writeText("---\nname: Secret\n---\nContent")

            val skills = repo().getSkillsOnly()
            assertEquals(1, skills.size)
            assertEquals("Real", skills[0].name)
        }
    }
}
