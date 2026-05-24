/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatSessionsTable
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.KnowledgeSourceSerializer
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.domain.ProjectsTable
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * Extension function to map an Exposed ResultRow to a Project object.
 */
private fun ResultRow.toProject(): Project = Project(
    id = this[ProjectsTable.id],
    name = this[ProjectsTable.name],
    description = this[ProjectsTable.description],
    knowledgeSources = KnowledgeSourceSerializer.deserialize(this[ProjectsTable.knowledgeSourcesConfig]),
    createdAt = this[ProjectsTable.createdAt],
    updatedAt = this[ProjectsTable.updatedAt],
    isStarred = this[ProjectsTable.isStarred] == 1,
)

/**
 * Repository for managing projects.
 * Projects group chat sessions and provide RAG context through indexed files.
 */
class ProjectRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {
    private val log = logger<ProjectRepository>()

    /**
     * Create a new project.
     * @param project The project to create (id will be auto-generated if blank)
     * @return The created project with generated id
     */
    fun createProject(project: Project): Project {
        val projectWithInjectedFields = project.copy(
            id = project.id.ifBlank { UUID.randomUUID().toString() },
        )

        transaction(database) {
            ProjectsTable.insert {
                it[id] = projectWithInjectedFields.id
                it[name] = projectWithInjectedFields.name
                it[description] = projectWithInjectedFields.description
                it[knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(projectWithInjectedFields.knowledgeSources)
                it[createdAt] = projectWithInjectedFields.createdAt
                it[updatedAt] = projectWithInjectedFields.updatedAt
            }
        }

        log.debug("Created project ${projectWithInjectedFields.id} with name '${projectWithInjectedFields.name}'")
        EventBus.post(PushDataToServerEvent(reason = "project created"))
        return projectWithInjectedFields
    }

    /**
     * Returns the total number of projects using a SQL COUNT(*) query.
     */
    fun countAll(): Int = transaction(database) {
        val count = ProjectsTable.id.count()
        ProjectsTable.select(count).first()[count].toInt()
    }

    /**
     * Get all projects ordered by updated time (most recent first).
     * @return List of all projects
     */
    fun getAllProjects(): List<Project> = transaction(database) {
        ProjectsTable
            .selectAll()
            .orderBy(ProjectsTable.isStarred, SortOrder.DESC)
            .orderBy(ProjectsTable.updatedAt, SortOrder.DESC)
            .map { it.toProject() }
    }

    /**
     * Get a project by id.
     * @param projectId The project id
     * @return The project or null if not found
     */
    fun getProject(projectId: String): Project? = transaction(database) {
        ProjectsTable
            .selectAll()
            .where { ProjectsTable.id eq projectId }
            .map { it.toProject() }
            .firstOrNull()
    }

    /**
     * Find a project by name.
     * @param name The project name
     * @return The project or null if not found
     */
    fun findProjectByName(name: String): Project? = transaction(database) {
        ProjectsTable
            .selectAll()
            .where { ProjectsTable.name eq name }
            .orderBy(ProjectsTable.createdAt, SortOrder.DESC)
            .map { it.toProject() }
            .firstOrNull()
    }

    /**
     * Find a project by session ID.
     * Joins the sessions table to find which project a session belongs to.
     *
     * @param sessionId The chat session id
     * @return The project that the session belongs to, or null if session has no project or not found
     */
    fun findProjectBySessionId(sessionId: String): Project? = transaction(database) {
        ProjectsTable
            .join(ChatSessionsTable, JoinType.INNER, ProjectsTable.id, ChatSessionsTable.projectId)
            .select(ProjectsTable.columns)
            .where { ChatSessionsTable.id eq sessionId }
            .singleOrNull()
            ?.toProject()
    }

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
    ): Boolean = transaction(database) {
        val updated = ProjectsTable.update({ ProjectsTable.id eq projectId }) {
            it[ProjectsTable.name] = name
            it[ProjectsTable.description] = description
            it[ProjectsTable.knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(knowledgeSources)
            it[updatedAt] = Instant.now()
        } > 0

        if (updated) {
            log.debug("Updated project $projectId")
            EventBus.post(PushDataToServerEvent(reason = "project updated"))
        }
        updated
    }

    /**
     * Get projects with pagination.
     * @param page The page number (1-based)
     * @param pageSize Number of projects per page
     * @return Paginated project results
     */
    fun getProjectsPaged(page: Int = 1, pageSize: Int = 10): Pageable<Project> = transaction(database) {
        val countExpr = ProjectsTable.id.count()
        val totalItems = ProjectsTable.select(countExpr).first()[countExpr].toInt()

        if (totalItems == 0) {
            return@transaction Pageable(
                items = emptyList(),
                currentPage = 1,
                totalPages = 0,
                totalItems = 0,
                pageSize = pageSize,
            )
        }

        val totalPages = (totalItems + pageSize - 1) / pageSize
        val validPage = page.coerceIn(1, totalPages)
        val offset = ((validPage - 1) * pageSize).toLong()

        val pageProjects = ProjectsTable
            .selectAll()
            .orderBy(ProjectsTable.updatedAt, SortOrder.DESC)
            .limit(pageSize)
            .offset(offset)
            .map { it.toProject() }

        Pageable(
            items = pageProjects,
            currentPage = validPage,
            totalPages = totalPages,
            totalItems = totalItems,
            pageSize = pageSize,
        )
    }

    /**
     * Delete a project and all its associated sessions.
     *
     * @param projectId The project id to delete
     * @return true if deleted successfully
     */
    fun starProject(projectId: String, isStarred: Boolean): Boolean = transaction(database) {
        ProjectsTable.update({ ProjectsTable.id eq projectId }) {
            it[ProjectsTable.isStarred] = if (isStarred) 1 else 0
        } > 0
    }.also { if (it) EventBus.post(PushDataToServerEvent(reason = "project starred")) }

    fun deleteProject(projectId: String): Boolean {
        log.debug("Deleting project $projectId")
        return transaction(database) {
            ProjectsTable.deleteWhere { ProjectsTable.id eq projectId } > 0
        }
    }

    /**
     * Returns projects that have never been pushed to the sync server
     * (`syncedAt IS NULL`) or were locally modified after the last push
     * (`updatedAt > syncedAt`).
     */
    fun getUnsyncedProjects(limit: Int = 50): List<Project> = transaction(database) {
        ProjectsTable
            .selectAll()
            .orderBy(ProjectsTable.updatedAt, SortOrder.ASC)
            .mapNotNull { row ->
                val syncedAt = row[ProjectsTable.syncedAt]
                val updatedAt = row[ProjectsTable.updatedAt].toString()
                if (syncedAt == null || updatedAt > syncedAt) row.toProject() else null
            }
            .take(limit)
    }

    /**
     * Mark a project as successfully synced to the server.
     */
    fun markSynced(projectId: String): Boolean = transaction(database) {
        ProjectsTable.update({ ProjectsTable.id eq projectId }) {
            it[syncedAt] = Instant.now().toString()
        } > 0
    }

    /**
     * Upsert projects
     */
    fun upsertFromServer(projects: List<Project>) {
        if (projects.isEmpty()) return
        transaction(database) {
            val nowStr = Instant.now().toString()
            val existingById = ProjectsTable
                .selectAll()
                .where { ProjectsTable.id inList projects.map { it.id } }
                .associate { row -> row[ProjectsTable.id] to row[ProjectsTable.updatedAt] }

            for (project in projects) {
                val storedUpdatedAt = existingById[project.id]
                if (storedUpdatedAt == null) {
                    ProjectsTable.insert {
                        it[id] = project.id
                        it[name] = project.name
                        it[description] = project.description
                        it[knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(project.knowledgeSources)
                        it[createdAt] = project.createdAt
                        it[updatedAt] = project.updatedAt
                        it[syncedAt] = nowStr
                    }
                    log.debug("upsertFromServer: inserted project {}", project.id)
                } else if (project.updatedAt.isAfter(storedUpdatedAt)) {
                    ProjectsTable.update({ ProjectsTable.id eq project.id }) {
                        it[name] = project.name
                        it[description] = project.description
                        it[knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(project.knowledgeSources)
                        it[updatedAt] = project.updatedAt
                        it[syncedAt] = nowStr
                    }
                    log.debug("upsertFromServer: updated project {} (server newer)", project.id)
                } else {
                    log.debug("upsertFromServer: skipped project {} (local is same age or newer)", project.id)
                }
            }
        }
    }
}
