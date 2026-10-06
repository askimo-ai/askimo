/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.KnowledgeSourceSerializer
import io.askimo.core.chat.domain.Project
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.db.resolvePageParams
import io.askimo.core.db.sqldelight.Projects
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import java.util.UUID
import kotlin.time.Clock

/**
 * Maps a generated [Projects] row to the shared [Project] domain object.
 */
private fun Projects.toProject(): Project = Project(
    id = id,
    name = name,
    description = description,
    knowledgeSources = KnowledgeSourceSerializer.deserialize(indexed_paths),
    createdAt = created_at,
    updatedAt = updated_at,
    isStarred = is_starred == 1L,
    defaultDirectiveId = default_directive_id,
)

/**
 * Repository for managing projects.
 * Projects group chat sessions and provide RAG context through indexed files.
 */
class ProjectRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {
    private val log = logger<ProjectRepository>()
    private val queries get() = db.projectsQueries

    /**
     * Create a new project.
     * @param project The project to create (id will be auto-generated if blank)
     * @return The created project with generated id
     */
    fun createProject(project: Project): Project {
        val projectWithInjectedFields = project.copy(
            id = project.id.ifBlank { UUID.randomUUID().toString() },
        )

        queries.insertProject(
            id = projectWithInjectedFields.id,
            name = projectWithInjectedFields.name,
            description = projectWithInjectedFields.description,
            indexedPaths = KnowledgeSourceSerializer.serialize(projectWithInjectedFields.knowledgeSources),
            createdAt = projectWithInjectedFields.createdAt,
            updatedAt = projectWithInjectedFields.updatedAt,
            defaultDirectiveId = projectWithInjectedFields.defaultDirectiveId,
        )

        log.debug("Created project ${projectWithInjectedFields.id} with name '${projectWithInjectedFields.name}'")
        EventBus.post(PushDataToServerEvent(reason = "project created"))
        return projectWithInjectedFields
    }

    /** Returns the total number of projects using a SQL COUNT(*) query. */
    fun countAll(): Int = queries.countAll().executeAsOne().toInt()

    /**
     * Get all projects ordered by updated time (most recent first).
     * @return List of all projects
     */
    fun getAllProjects(): List<Project> = queries.selectAllOrdered().executeAsList().map { it.toProject() }

    /**
     * Get a project by id.
     * @param projectId The project id
     * @return The project or null if not found
     */
    fun getProject(projectId: String): Project? = queries.selectById(projectId).executeAsOneOrNull()?.toProject()

    /**
     * Find a project by name.
     * @param name The project name
     * @return The project or null if not found
     */
    fun findProjectByName(name: String): Project? = queries.selectByNameOrderedDesc(name).executeAsOneOrNull()?.toProject()

    /**
     * Find a project by session ID.
     * Joins the sessions table to find which project a session belongs to.
     *
     * @param sessionId The chat session id
     * @return The project that the session belongs to, or null if session has no project or not found
     */
    fun findProjectBySessionId(sessionId: String): Project? = queries.findProjectBySessionId(sessionId).executeAsOneOrNull()?.toProject()

    /**
     * Update a project's information.
     * Updates name, description, and knowledge sources. Also updates the updatedAt timestamp.
     *
     * @param projectId The project id
     * @param name The new name
     * @param description The new description (nullable)
     * @param knowledgeSources The new knowledge sources configuration
     * @return true if updated successfully
     */
    fun updateProject(
        projectId: String,
        name: String,
        description: String?,
        knowledgeSources: List<KnowledgeSourceConfig>,
    ): Boolean {
        val updated = queries.updateProject(
            name = name,
            description = description,
            indexedPaths = KnowledgeSourceSerializer.serialize(knowledgeSources),
            updatedAt = Clock.System.now(),
            id = projectId,
        ).value > 0

        if (updated) {
            log.debug("Updated project $projectId")
            EventBus.post(PushDataToServerEvent(reason = "project updated"))
        }
        return updated
    }

    /**
     * Set (or clear) the default directive automatically applied to new chats started
     * within this project. Pass `null` to clear the project-level default.
     *
     * @param projectId The project id
     * @param directiveId The directive id to use as default, or null to clear it
     * @return true if updated successfully
     */
    fun setDefaultDirective(projectId: String, directiveId: String?): Boolean {
        val updated = queries.setDefaultDirective(
            defaultDirectiveId = directiveId,
            updatedAt = Clock.System.now(),
            id = projectId,
        ).value > 0

        if (updated) {
            log.debug("Set default directive for project $projectId to $directiveId")
            EventBus.post(PushDataToServerEvent(reason = "project default directive updated"))
        }
        return updated
    }

    /**
     * Get projects with pagination.
     * @param page The page number (1-based)
     * @param pageSize Number of projects per page
     * @return Paginated project results
     */
    fun getProjectsPaged(page: Int = 1, pageSize: Int = 10): Pageable<Project> {
        val totalItems = queries.countAll().executeAsOne().toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize) ?: return Pageable.empty(pageSize)

        val pageProjects = queries.selectPagedDesc(pageSize.toLong(), pageParams.offset)
            .executeAsList().map { it.toProject() }

        return Pageable(
            items = pageProjects,
            currentPage = pageParams.validPage,
            totalPages = pageParams.totalPages,
            totalItems = totalItems,
            pageSize = pageSize,
        )
    }

    /**
     * Search projects by name (case-insensitive LIKE) with pagination.
     *
     * @param nameQuery Search term matched against project names
     * @param page The page number (1-based)
     * @param pageSize Number of projects per page
     * @return Paginated results matching the query
     */
    fun searchProjectsPaged(nameQuery: String, page: Int = 1, pageSize: Int = 10): Pageable<Project> {
        val pattern = "%${nameQuery.trim()}%"

        val totalItems = queries.countSearch(pattern).executeAsOne().toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize) ?: return Pageable.empty(pageSize)

        val pageProjects = queries.selectSearchPagedDesc(pattern, pageSize.toLong(), pageParams.offset)
            .executeAsList().map { it.toProject() }

        return Pageable(
            items = pageProjects,
            currentPage = pageParams.validPage,
            totalPages = pageParams.totalPages,
            totalItems = totalItems,
            pageSize = pageSize,
        )
    }

    /** Set (or clear) the starred status of a project. */
    fun starProject(projectId: String, isStarred: Boolean): Boolean {
        val updated = queries.starProject(if (isStarred) 1L else 0L, projectId).value > 0
        if (updated) EventBus.post(PushDataToServerEvent(reason = "project starred"))
        return updated
    }

    /**
     * Delete a project and all its associated sessions.
     *
     * @param projectId The project id to delete
     * @return true if deleted successfully
     */
    fun deleteProject(projectId: String): Boolean {
        log.debug("Deleting project $projectId")
        return queries.deleteProject(projectId).value > 0
    }

    /**
     * Returns projects that have never been pushed to the sync server
     * (`syncedAt IS NULL`) or were locally modified after the last push
     * (`updatedAt > syncedAt`).
     */
    fun getUnsyncedProjects(limit: Int = 50): List<Project> = queries.selectAllOrderedByUpdatedAtAsc().executeAsList()
        .mapNotNull { row ->
            if (row.synced_at == null || row.updated_at > row.synced_at) row.toProject() else null
        }
        .take(limit)

    /** Mark a project as successfully synced to the server. */
    fun markSynced(projectId: String): Boolean = queries.markSynced(Clock.System.now(), projectId).value > 0

    /** Upsert projects */
    fun upsertFromServer(projects: List<Project>) {
        if (projects.isEmpty()) return

        db.transaction {
            val now = Clock.System.now()
            val ids = projects.map { it.id }

            val existingById = queries.selectExistingByIds(ids).executeAsList()
                .associate { it.id to it.updated_at }

            for (project in projects) {
                val storedUpdatedAt = existingById[project.id]

                if (storedUpdatedAt == null) {
                    queries.insertFromServer(
                        id = project.id,
                        name = project.name,
                        description = project.description,
                        indexedPaths = KnowledgeSourceSerializer.serialize(project.knowledgeSources),
                        createdAt = project.createdAt,
                        updatedAt = project.updatedAt,
                        syncedAt = now,
                        defaultDirectiveId = project.defaultDirectiveId,
                    )
                    log.debug("upsertFromServer: inserted project {}", project.id)
                } else if (project.updatedAt > storedUpdatedAt) {
                    queries.updateFromServer(
                        name = project.name,
                        description = project.description,
                        indexedPaths = KnowledgeSourceSerializer.serialize(project.knowledgeSources),
                        updatedAt = project.updatedAt,
                        syncedAt = now,
                        defaultDirectiveId = project.defaultDirectiveId,
                        id = project.id,
                    )
                    log.debug("upsertFromServer: updated project {} (server newer)", project.id)
                } else {
                    log.debug("upsertFromServer: skipped project {} (local is same age or newer)", project.id)
                }
            }
        }
    }
}
