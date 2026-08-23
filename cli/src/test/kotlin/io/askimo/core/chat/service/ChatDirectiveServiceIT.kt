/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.repository.ChatDirectiveRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.user.repository.UserProfileRepository
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * Tests for default-directive resolution: project default > global default > none,
 * and graceful fallback when a referenced directive no longer exists.
 */
class ChatDirectiveServiceIT {

    @AfterEach
    fun tearDown() {
        projectRepository.getAllProjects().forEach { projectRepository.deleteProject(it.id) }
        directiveRepository.list().forEach { directiveRepository.delete(it.id) }
        userProfileRepository.clearProfile()
        userProfileRepository.getProfile() // Recreate default profile row for the next test.
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var directiveRepository: ChatDirectiveRepository
        private lateinit var projectRepository: ProjectRepository
        private lateinit var userProfileRepository: UserProfileRepository
        private lateinit var service: ChatDirectiveService

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            directiveRepository = databaseManager.getChatDirectiveRepository()
            projectRepository = databaseManager.getProjectRepository()
            userProfileRepository = databaseManager.getUserProfileRepository()
            userProfileRepository.getProfile() // Ensure the default profile row exists before setPreference calls.

            service = ChatDirectiveService(
                repository = directiveRepository,
                userProfileRepository = userProfileRepository,
                projectRepository = projectRepository,
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    private fun createDirective(name: String): ChatDirective = directiveRepository.save(ChatDirective(name = name, content = "Some instructions"))

    private fun createProject(name: String, defaultDirectiveId: String? = null): Project {
        val project = projectRepository.createProject(
            Project(
                id = "",
                name = name,
                description = null,
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                defaultDirectiveId = defaultDirectiveId,
            ),
        )
        if (defaultDirectiveId != null) {
            projectRepository.setDefaultDirective(project.id, defaultDirectiveId)
        }
        return project
    }

    @Test
    fun `resolves to null when no defaults are set`() {
        assertNull(service.resolveDefaultDirectiveId(projectId = null))
    }

    @Test
    fun `resolves to global default when set and no project given`() {
        val directive = createDirective("global-default")
        service.setGlobalDefaultDirectiveId(directive.id)

        assertEquals(directive.id, service.resolveDefaultDirectiveId(projectId = null))
    }

    @Test
    fun `resolves to project default over global default`() {
        val globalDirective = createDirective("global-default")
        val projectDirective = createDirective("project-default")
        service.setGlobalDefaultDirectiveId(globalDirective.id)
        val project = createProject("Project A", defaultDirectiveId = projectDirective.id)

        assertEquals(projectDirective.id, service.resolveDefaultDirectiveId(projectId = project.id))
    }

    @Test
    fun `falls back to global default when project has no default`() {
        val globalDirective = createDirective("global-default")
        service.setGlobalDefaultDirectiveId(globalDirective.id)
        val project = createProject("Project B")

        assertEquals(globalDirective.id, service.resolveDefaultDirectiveId(projectId = project.id))
    }

    @Test
    fun `falls back to global default when project default directive was deleted`() {
        val globalDirective = createDirective("global-default")
        val projectDirective = createDirective("project-default")
        service.setGlobalDefaultDirectiveId(globalDirective.id)
        val project = createProject("Project C", defaultDirectiveId = projectDirective.id)

        directiveRepository.delete(projectDirective.id)

        assertEquals(globalDirective.id, service.resolveDefaultDirectiveId(projectId = project.id))
    }

    @Test
    fun `resolves to null when global default directive was deleted`() {
        val directive = createDirective("global-default")
        service.setGlobalDefaultDirectiveId(directive.id)
        directiveRepository.delete(directive.id)

        assertNull(service.resolveDefaultDirectiveId(projectId = null))
        assertNull(service.getGlobalDefaultDirectiveId())
    }

    @Test
    fun `clearing global default resolves to null`() {
        val directive = createDirective("global-default")
        service.setGlobalDefaultDirectiveId(directive.id)
        assertEquals(directive.id, service.getGlobalDefaultDirectiveId())

        service.setGlobalDefaultDirectiveId(null)
        assertNull(service.getGlobalDefaultDirectiveId())
    }
}
