/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectDeletedEvent
import io.askimo.core.event.internal.ProjectIndexingRequestedEvent
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.time.Instant

/**
 * Service for managing projects.
 */
class ProjectService(
    private val projectRepository: ProjectRepository,
) {
    private val log = logger<ProjectService>()

    /**
     * Creates a new project and triggers indexing if knowledge sources are present.
     *
     * @param name Project name (will be trimmed).
     * @param description Optional project description.
     * @param knowledgeSources Knowledge source configurations to attach to the project.
     * @return The persisted [Project].
     */
    fun createProject(
        name: String,
        description: String?,
        knowledgeSources: List<KnowledgeSourceConfig> = emptyList(),
    ): Project {
        val project = Project(
            id = "",
            name = name.trim(),
            description = description?.takeIf { it.isNotBlank() },
            knowledgeSources = knowledgeSources,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        val createdProject = projectRepository.createProject(project)

        val projectDir = AskimoHome.projectsDir().resolve(createdProject.id)
        projectDir.toFile().mkdirs()
        log.debug("Created project directory at {}", projectDir)

        // Emit indexing event if the project has knowledge sources
        if (createdProject.knowledgeSources.isNotEmpty()) {
            EventBus.post(
                ProjectIndexingRequestedEvent(
                    projectId = createdProject.id,
                    watchForChanges = true,
                ),
            )
            log.debug("Emitted indexing event for project ${createdProject.id}")
        }

        return createdProject
    }

    /**
     * Deletes a project by ID and emits a [ProjectDeletedEvent].
     *
     * @return `true` if the project was deleted, `false` if it was not found.
     */
    fun deleteProject(projectId: String): Boolean {
        val deleted = projectRepository.deleteProject(projectId)
        if (deleted) {
            val projectDir = AskimoHome.projectsDir().resolve(projectId)
            projectDir.toFile().deleteRecursively()
            log.debug("Deleted project directory at {}", projectDir)
            EventBus.post(ProjectDeletedEvent(projectId = projectId))
            log.debug("Deleted project $projectId and emitted ProjectDeletedEvent")
        }
        return deleted
    }
}
