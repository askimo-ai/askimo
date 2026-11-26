/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.repository

import io.askimo.core.plan.domain.PlanScope
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests the TEAM-plan source added to [PlanDefRepository] — the file-based
 * `team-plans/` directory, merge precedence (user > team > built-in), and the
 * duplicate-to-personal flow via [PlanDefRepository.loadYamlForDuplicate].
 */
class PlanDefRepositoryTest {

    private val repository = PlanDefRepository()

    @BeforeEach
    fun cleanDirs() {
        listOf(AskimoHome.plansDir(), AskimoHome.teamPlansDir()).forEach { dir ->
            if (Files.isDirectory(dir)) {
                Files.list(dir).forEach { Files.delete(it) }
            }
        }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    private fun yamlFor(id: String, name: String) = """
        id: $id
        name: $name
        steps:
          only:
            message: "Say hello for {{name}}."
    """.trimIndent()

    @Test
    fun `saveTeamPlan writes to team-plans directory and tags scope TEAM`() {
        val plan = repository.saveTeamPlan(yamlFor("standup-notes", "Standup Notes"))

        assertEquals("standup-notes", plan.id)
        assertEquals(PlanScope.TEAM, plan.scope)
        assertTrue(Files.isRegularFile(AskimoHome.teamPlansDir().resolve("standup-notes.yml")))
    }

    @Test
    fun `getAll includes team plans tagged with scope TEAM`() {
        repository.saveTeamPlan(yamlFor("standup-notes", "Standup Notes"))

        val found = repository.getAll().find { it.id == "standup-notes" }

        assertEquals(PlanScope.TEAM, found?.scope)
    }

    @Test
    fun `findById returns team plan when no personal plan shadows it`() {
        repository.saveTeamPlan(yamlFor("standup-notes", "Standup Notes"))

        val found = repository.findById("standup-notes")

        assertEquals("Standup Notes", found?.name)
        assertEquals(PlanScope.TEAM, found?.scope)
    }

    @Test
    fun `personal plan shadows a team plan with the same id`() {
        repository.saveTeamPlan(yamlFor("standup-notes", "Team Standup Notes"))
        repository.save(yamlFor("standup-notes", "My Standup Notes"))

        val found = repository.findById("standup-notes")

        assertEquals("My Standup Notes", found?.name)
        assertEquals(PlanScope.PERSONAL, found?.scope)
        // Merged getAll() should not contain the shadowed team duplicate.
        assertEquals(1, repository.getAll().count { it.id == "standup-notes" })
    }

    @Test
    fun `deleteTeamPlan removes only the team-plans copy`() {
        repository.saveTeamPlan(yamlFor("standup-notes", "Standup Notes"))

        val deleted = repository.deleteTeamPlan("standup-notes")

        assertTrue(deleted)
        assertNull(repository.findById("standup-notes"))
    }

    @Test
    fun `deleteTeamPlan returns false when no file exists`() {
        assertEquals(false, repository.deleteTeamPlan("does-not-exist"))
    }

    @Test
    fun `loadYamlForDuplicate falls back to team plan yaml for duplicate-to-personal flow`() {
        val yaml = yamlFor("standup-notes", "Standup Notes")
        repository.saveTeamPlan(yaml)

        val duplicated = repository.loadYamlForDuplicate("standup-notes")

        assertEquals(yaml, duplicated)
    }

    @Test
    fun `delete does not remove a team plan with the same id`() {
        repository.saveTeamPlan(yamlFor("standup-notes", "Standup Notes"))

        val deletedAsUserPlan = repository.delete("standup-notes")

        assertEquals(false, deletedAsUserPlan)
        assertEquals(PlanScope.TEAM, repository.findById("standup-notes")?.scope)
    }

    @Test
    fun `count includes team plans without double-counting shadowed ids`() {
        val baseline = repository.count() // built-in count varies by bundled defaults

        repository.saveTeamPlan(yamlFor("standup-notes", "Team Standup Notes"))
        repository.save(yamlFor("standup-notes", "My Standup Notes"))
        repository.saveTeamPlan(yamlFor("retro-notes", "Retro Notes"))

        // Only 2 distinct new ids were added (standup-notes shadowed, retro-notes new).
        assertEquals(baseline + 2, repository.count())
    }
}
