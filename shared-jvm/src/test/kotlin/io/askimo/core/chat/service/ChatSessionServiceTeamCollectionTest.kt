/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.chat.domain.ResourceCollectionOrigin
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.ResourceCollectionRepository
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.nio.file.Path

/**
 * Regression coverage for [ChatSessionService.getActiveServerManagedCollectionIds] — must share
 * [ChatSessionService]'s exact "project → chip" precedence rule with
 * [ChatSessionService.getOrCreateContextForSession] (via the shared private
 * `resolveActiveContainer` helper) so the two never drift, and must only ever return ids for
 * team-managed collections (never local ones, never when a project is active).
 */
class ChatSessionServiceTeamCollectionTest {

    private lateinit var service: ChatSessionService

    @AfterEach
    fun tearDown() {
        sessionRepository.deleteAll()
        resourceCollectionRepository.getAllCollections().forEach { resourceCollectionRepository.deleteCollection(it.id) }
        projectRepository.getAllProjects().forEach { projectRepository.deleteProject(it.id) }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var projectRepository: ProjectRepository
        private lateinit var resourceCollectionRepository: ResourceCollectionRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            sessionRepository = databaseManager.getChatSessionRepository()
            projectRepository = databaseManager.getProjectRepository()
            resourceCollectionRepository = databaseManager.getResourceCollectionRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    private fun newService(): ChatSessionService = ChatSessionService(
        sessionRepository = sessionRepository,
        messageRepository = databaseManager.getChatMessageRepository(),
        sessionMemoryRepository = databaseManager.getSessionMemoryRepository(),
        projectRepository = projectRepository,
        resourceCollectionRepository = resourceCollectionRepository,
        appContext = mock<AppContext>(),
    )

    @Test
    fun `returns empty when no collection is active`() {
        service = newService()
        val session = sessionRepository.createSession(ChatSession(id = "", title = "No collection"))

        assertTrue(service.getActiveServerManagedCollectionIds(session.id).isEmpty())
    }

    @Test
    fun `returns empty when the active chip-selected collection is local`() {
        service = newService()
        val local = resourceCollectionRepository.createCollection(
            ResourceCollection(id = "", name = "Local Docs", origin = ResourceCollectionOrigin.LOCAL),
        )
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Local chip"))
        sessionRepository.updateSessionActiveResourceCollections(session.id, listOf(local.id))

        assertTrue(service.getActiveServerManagedCollectionIds(session.id).isEmpty())
    }

    @Test
    fun `returns the id when the active chip-selected collection is team-managed`() {
        service = newService()
        val teamManaged = resourceCollectionRepository.createCollection(
            ResourceCollection(id = "", name = "HR", origin = ResourceCollectionOrigin.TEAM_MANAGED),
        )
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Team chip"))
        sessionRepository.updateSessionActiveResourceCollections(session.id, listOf(teamManaged.id))

        assertEquals(listOf(teamManaged.id), service.getActiveServerManagedCollectionIds(session.id))
    }

    @Test
    fun `returns empty when a project is active, even if a team-managed collection is also chip-selected`() {
        service = newService()
        val teamManaged = resourceCollectionRepository.createCollection(
            ResourceCollection(id = "", name = "HR", origin = ResourceCollectionOrigin.TEAM_MANAGED),
        )
        val project = projectRepository.createProject(Project(id = "", name = "My Project", knowledgeSources = emptyList()))
        val session = sessionRepository.createSession(
            ChatSession(id = "", title = "Project session", projectId = project.id),
        )
        sessionRepository.updateSessionActiveResourceCollections(session.id, listOf(teamManaged.id))

        // Project takes precedence over the chip, per resolveActiveContainer's precedence rule —
        // same rule getOrCreateContextForSession applies when building the local retriever.
        assertTrue(service.getActiveServerManagedCollectionIds(session.id).isEmpty())
    }
}
