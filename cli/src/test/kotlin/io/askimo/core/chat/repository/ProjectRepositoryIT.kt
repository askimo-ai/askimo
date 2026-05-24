/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.domain.SessionMemory
import io.askimo.core.context.MessageRole
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class ProjectRepositoryIT {

    @AfterEach
    fun tearDown() {
        // Clean up projects and sessions after each test
        projectRepository.getAllProjects().forEach { project ->
            projectRepository.deleteProject(project.id)
        }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var projectRepository: ProjectRepository
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var messageRepository: ChatMessageRepository
        private lateinit var sessionMemoryRepository: SessionMemoryRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            // Use file-based database to match production behavior
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            projectRepository = databaseManager.getProjectRepository()
            sessionRepository = databaseManager.getChatSessionRepository()
            messageRepository = databaseManager.getChatMessageRepository()
            sessionMemoryRepository = databaseManager.getSessionMemoryRepository()
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

    @Test
    fun `should create and retrieve a project`() {
        val project = projectRepository.createProject(
            Project(
                id = "",
                name = "Test Project",
                description = "Test Description",
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        assertNotNull(project.id)
        assertEquals("Test Project", project.name)
        assertEquals("Test Description", project.description)
        assertEquals(emptyList<LocalFoldersKnowledgeSourceConfig>(), project.knowledgeSources)
        assertNotNull(project.createdAt)
        assertNotNull(project.updatedAt)
    }

    @Test
    fun `should get all projects`() {
        val project1 = projectRepository.createProject(
            Project(
                id = "",
                name = "Project 1",
                description = null,
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
        val project2 = projectRepository.createProject(
            Project(
                id = "",
                name = "Project 2",
                description = "Description 2",
                knowledgeSources = listOf(LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/to/folder")),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        val projects = projectRepository.getAllProjects()

        assertEquals(2, projects.size)
        assertTrue(projects.any { it.id == project1.id })
        assertTrue(projects.any { it.id == project2.id })
    }

    @Test
    fun `should return empty list when no projects exist`() {
        val projects = projectRepository.getAllProjects()

        assertTrue(projects.isEmpty())
    }

    @Test
    fun `should update project`() {
        val project = projectRepository.createProject(
            Project(
                id = "",
                name = "Original Name",
                description = "Original Description",
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        Thread.sleep(10) // Ensure timestamp difference

        val updated = projectRepository.updateProject(
            projectId = project.id,
            name = "Updated Name",
            description = "Updated Description",
            knowledgeSources = listOf(LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/new/path")),
        )

        assertTrue(updated)

        val projects = projectRepository.getAllProjects()
        val updatedProject = projects.find { it.id == project.id }
        assertNotNull(updatedProject)
        assertEquals("Updated Name", updatedProject.name)
        assertEquals("Updated Description", updatedProject.description)
        assertEquals(1, updatedProject.knowledgeSources.size)
        assertEquals("/new/path", (updatedProject.knowledgeSources[0] as LocalFoldersKnowledgeSourceConfig).resourceIdentifier)
        assertTrue(updatedProject.updatedAt.isAfter(project.updatedAt))
    }

    @Test
    fun `should update project with null description`() {
        val project = projectRepository.createProject(
            Project(
                id = "",
                name = "Test Project",
                description = "Some Description",
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        val updated = projectRepository.updateProject(
            projectId = project.id,
            name = "Updated Name",
            description = null,
            knowledgeSources = emptyList(),
        )

        assertTrue(updated)

        val projects = projectRepository.getAllProjects()
        val updatedProject = projects.find { it.id == project.id }
        assertNull(updatedProject!!.description)
    }

    @Test
    fun `should return false when updating non-existent project`() {
        val updated = projectRepository.updateProject(
            projectId = "non-existent-id",
            name = "Some Name",
            description = null,
            knowledgeSources = emptyList(),
        )

        assertFalse(updated)
    }

    @Test
    fun `should cascade delete sessions when project is deleted`() {
        // Create a project
        val project = projectRepository.createProject(
            Project(
                id = "",
                name = "Test Project",
                description = "Test Description",
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        // Create sessions associated with the project
        val session1 = sessionRepository.createSession(
            ChatSession(id = "", title = "Project Session 1", projectId = project.id),
        )
        val session2 = sessionRepository.createSession(
            ChatSession(id = "", title = "Project Session 2", projectId = project.id),
        )

        // Create messages for session 1
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = session1.id,
                role = MessageRole.USER,
                content = "Message 1 for Session 1",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = session1.id,
                role = MessageRole.ASSISTANT,
                content = "Message 2 for Session 1",
            ),
        )

        // Create messages for session 2
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = session2.id,
                role = MessageRole.USER,
                content = "Message 1 for Session 2",
            ),
        )

        // Create session memory for session 1 to test CASCADE with session_memory table
        sessionMemoryRepository.saveMemory(
            SessionMemory(
                sessionId = session1.id,
                memorySummary = "Summary for session 1",
                memoryMessages = "[]",
                lastUpdated = Instant.now(),
            ),
        )

        // Create session memory for session 2
        sessionMemoryRepository.saveMemory(
            SessionMemory(
                sessionId = session2.id,
                memorySummary = "Summary for session 2",
                memoryMessages = "[]",
                lastUpdated = Instant.now(),
            ),
        )

        // Verify project exists before deletion
        assertNotNull(projectRepository.getProject(project.id))

        // Delete the project - this should trigger CASCADE deletes
        val deleted = projectRepository.deleteProject(project.id)
        assertTrue(deleted)

        // Verify project is deleted
        assertNull(projectRepository.getProject(project.id))
    }

    @Test
    fun `should create project with indexed paths`() {
        val knowledgeSources = listOf(
            LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/to/folder1"),
            LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/to/folder2"),
        )
        val project = projectRepository.createProject(
            Project(
                id = "",
                name = "Project with Paths",
                description = null,
                knowledgeSources = knowledgeSources,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        val projects = projectRepository.getAllProjects()
        val createdProject = projects.find { it.id == project.id }
        assertEquals(knowledgeSources, createdProject!!.knowledgeSources)
    }

    @Test
    fun `should order projects by updated_at descending`() {
        val now = Instant.now()
        val project1 = projectRepository.createProject(
            Project(
                id = "",
                name = "First",
                description = null,
                knowledgeSources = emptyList(),
                createdAt = now.minus(Duration.ofHours(2)),
                updatedAt = now.minus(Duration.ofHours(2)),
            ),
        )
        Thread.sleep(10)
        val project2 = projectRepository.createProject(
            Project(
                id = "",
                name = "Second",
                description = null,
                knowledgeSources = emptyList(),
                createdAt = now.minus(Duration.ofHours(1)),
                updatedAt = now.minus(Duration.ofHours(1)),
            ),
        )
        Thread.sleep(10)
        val project3 = projectRepository.createProject(
            Project(
                id = "",
                name = "Third",
                description = null,
                knowledgeSources = emptyList(),
                createdAt = now,
                updatedAt = now,
            ),
        )

        val projects = projectRepository.getAllProjects()

        assertEquals(3, projects.size)
        assertEquals(project3.id, projects[0].id) // Most recent
        assertEquals(project2.id, projects[1].id)
        assertEquals(project1.id, projects[2].id) // Oldest
    }

    @Test
    fun `should handle project names with special characters`() {
        val project = projectRepository.createProject(
            Project(
                id = "",
                name = "Test Project: \"Special\" & <Characters>",
                description = "Description with 'quotes' and \"escapes\"",
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        val projects = projectRepository.getAllProjects()
        val createdProject = projects.find { it.id == project.id }
        assertEquals("Test Project: \"Special\" & <Characters>", createdProject!!.name)
        assertEquals("Description with 'quotes' and \"escapes\"", createdProject.description)
    }

    @Test
    fun `should not affect other projects when deleting one`() {
        val project1 = projectRepository.createProject(
            Project(
                id = "",
                name = "Project 1",
                description = null,
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
        val project2 = projectRepository.createProject(
            Project(
                id = "",
                name = "Project 2",
                description = null,
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        projectRepository.deleteProject(project1.id)

        val projects = projectRepository.getAllProjects()
        assertEquals(1, projects.size)
        assertEquals(project2.id, projects[0].id)
    }
}
