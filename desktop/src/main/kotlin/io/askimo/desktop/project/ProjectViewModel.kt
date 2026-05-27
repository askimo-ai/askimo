/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectIndexRemovalEvent
import io.askimo.core.event.internal.ProjectReIndexEvent
import io.askimo.core.event.internal.ProjectRefreshEvent
import io.askimo.core.event.internal.ProjectSessionsRefreshEvent
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingInProgressEvent
import io.askimo.core.event.user.IndexingQueuedEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.rag.ProjectIndexer
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStatus
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for managing a single project view.
 * Handles project data, sessions, and business logic.
 */
class ProjectViewModel(
    private val scope: CoroutineScope,
    private val projectId: String,
    private val projectIndexer: ProjectIndexer? = null,
) {
    private val log = logger<ProjectViewModel>()
    private val projectRepository = DatabaseManager.getInstance().getProjectRepository()
    private val sessionRepository = DatabaseManager.getInstance().getChatSessionRepository()

    // State
    var currentProject by mutableStateOf<Project?>(null)
        private set

    var projectSessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set

    var allProjects by mutableStateOf<List<Project>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var successMessage by mutableStateOf<String?>(null)
        private set

    var indexProgress by mutableStateOf(IndexProgress())
        private set

    init {
        loadProject()
        loadSessions()
        loadAllProjects()
        observeProjectEvents()
        observeIndexProgress()
        syncInitialIndexProgress()
    }

    /**
     * Load the current project from database
     */
    fun loadProject() {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null
                val project = withContext(Dispatchers.IO) {
                    projectRepository.getProject(projectId)
                }
                currentProject = project
                log.debug("Loaded project: ${project?.name} (${project?.id})")
            } catch (e: Exception) {
                log.error("Failed to load project", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "loading project",
                    LocalizationManager.getString("project.error.loading"),
                )
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Load sessions associated with this project
     */
    fun loadSessions() {
        scope.launch {
            try {
                val sessions = withContext(Dispatchers.IO) {
                    sessionRepository.getSessionsByProjectId(projectId)
                }
                projectSessions = sessions
                log.debug("Loaded ${sessions.size} sessions for project $projectId")
            } catch (e: Exception) {
                log.error("Failed to load project sessions", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "loading project sessions",
                    LocalizationManager.getString("project.error.loading_sessions"),
                )
            }
        }
    }

    /**
     * Load all projects for move session dropdown
     */
    fun loadAllProjects() {
        scope.launch {
            try {
                val projects = withContext(Dispatchers.IO) {
                    projectRepository.getAllProjects()
                }
                allProjects = projects
                log.debug("Loaded ${projects.size} projects for dropdown")
            } catch (e: Exception) {
                log.error("Failed to load all projects", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "loading projects list",
                    LocalizationManager.getString("projects.error.loading"),
                )
            }
        }
    }

    /**
     * Update project details
     */
    fun updateProject(
        name: String,
        description: String?,
        knowledgeSources: List<KnowledgeSourceConfig>,
    ) {
        scope.launch {
            try {
                val oldKnowledgeSources = currentProject?.knowledgeSources

                withContext(Dispatchers.IO) {
                    projectRepository.updateProject(
                        projectId = projectId,
                        name = name,
                        description = description,
                        knowledgeSources = knowledgeSources,
                    )
                }

                EventBus.post(
                    ProjectRefreshEvent(
                        projectId = projectId,
                        reason = "Project updated by user",
                    ),
                )

                // Trigger re-indexing if knowledge sources changed
                if (oldKnowledgeSources != knowledgeSources) {
                    EventBus.post(
                        ProjectReIndexEvent(
                            projectId = projectId,
                            reason = "Knowledge sources updated",
                        ),
                    )
                }

                successMessage = LocalizationManager.getString("project.update.success")
                loadProject() // Refresh project data
            } catch (e: Exception) {
                log.error("Failed to update project", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "updating project",
                    LocalizationManager.getString("project.error.updating"),
                )
            }
        }
    }

    /**
     * Delete a knowledge source from the project
     */
    fun deleteKnowledgeSource(source: KnowledgeSourceConfig) {
        scope.launch {
            try {
                val project = currentProject ?: return@launch
                val updatedSources = project.knowledgeSources.filter { it != source }

                withContext(Dispatchers.IO) {
                    projectRepository.updateProject(
                        projectId = projectId,
                        name = project.name,
                        description = project.description,
                        knowledgeSources = updatedSources,
                    )
                }

                EventBus.post(
                    ProjectIndexRemovalEvent(
                        projectId = project.id,
                        knowledgeSource = source,
                        reason = "Knowledge source removed by user",
                    ),
                )

                EventBus.post(
                    ProjectRefreshEvent(
                        projectId = projectId,
                        reason = "Knowledge source removed from project",
                    ),
                )

                successMessage = LocalizationManager.getString("project.knowledge_source.delete.success")
                loadProject()
            } catch (e: Exception) {
                log.error("Failed to delete knowledge source", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "deleting knowledge source",
                    LocalizationManager.getString("project.error.deleting_knowledge_source"),
                )
            }
        }
    }

    /**
     * Move a session to another project
     */
    fun moveSessionToProject(sessionId: String, targetProjectId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sessionRepository.updateSessionProject(sessionId, targetProjectId)
                }

                // Refresh both projects' sessions
                EventBus.post(
                    ProjectSessionsRefreshEvent(
                        projectId = projectId,
                        reason = "Session moved to another project",
                    ),
                )
                EventBus.post(
                    ProjectSessionsRefreshEvent(
                        projectId = targetProjectId,
                        reason = "Session moved from another project",
                    ),
                )

                successMessage = LocalizationManager.getString("session.move.success")
                loadSessions()
            } catch (e: Exception) {
                log.error("Failed to move session", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "moving session",
                    LocalizationManager.getString("session.error.moving"),
                )
            }
        }
    }

    /**
     * Remove session from project (set projectId to null)
     */
    fun removeSessionFromProject(sessionId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sessionRepository.updateSessionProject(sessionId, null)
                }

                EventBus.post(
                    ProjectSessionsRefreshEvent(
                        projectId = projectId,
                        reason = "Session removed from project",
                    ),
                )

                successMessage = LocalizationManager.getString("session.remove.success")
                loadSessions()
            } catch (e: Exception) {
                log.error("Failed to remove session from project", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "removing session from project",
                    LocalizationManager.getString("session.error.removing"),
                )
            }
        }
    }

    /**
     * Observe project-related events and update state accordingly
     */
    private fun observeProjectEvents() {
        // Observe ProjectRefreshEvent
        scope.launch {
            EventBus.internalEvents
                .filter { it is ProjectRefreshEvent && it.projectId == projectId }
                .collect {
                    loadProject()
                }
        }

        // Observe ProjectSessionsRefreshEvent
        scope.launch {
            EventBus.internalEvents
                .filter { it is ProjectSessionsRefreshEvent && it.projectId == projectId }
                .collect {
                    loadSessions()
                }
        }
    }

    /**
     * Observe indexing events from EventBus and update [indexProgress] state directly.
     * This is reliably event-driven — no coordinator polling or race conditions.
     */
    private fun observeIndexProgress() {
        scope.launch {
            merge(
                EventBus.internalEvents.filterIsInstance<IndexingQueuedEvent>().filter { it.projectId == projectId },
                EventBus.internalEvents.filterIsInstance<IndexingStartedEvent>().filter { it.projectId == projectId },
                EventBus.internalEvents.filterIsInstance<IndexingInProgressEvent>().filter { it.projectId == projectId },
                EventBus.internalEvents.filterIsInstance<IndexingCompletedEvent>().filter { it.projectId == projectId },
                EventBus.internalEvents.filterIsInstance<IndexingFailedEvent>().filter { it.projectId == projectId },
            ).collect { event ->
                indexProgress = when (event) {
                    is IndexingQueuedEvent -> IndexProgress(status = IndexStatus.QUEUED)

                    is IndexingStartedEvent -> IndexProgress(status = IndexStatus.INDEXING)

                    is IndexingInProgressEvent -> IndexProgress(
                        status = IndexStatus.INDEXING,
                        totalFiles = event.totalFiles,
                        processedFiles = event.filesIndexed,
                        resourceIdentifier = event.resourceId,
                    )

                    is IndexingCompletedEvent -> IndexProgress(
                        status = IndexStatus.READY,
                        processedFiles = event.filesIndexed,
                        totalFiles = event.filesIndexed,
                    )

                    is IndexingFailedEvent -> IndexProgress(
                        status = IndexStatus.FAILED,
                        error = event.errorMessage,
                    )

                    else -> indexProgress
                }
            }
        }
    }

    /**
     * Sync the current index progress from [ProjectIndexer] immediately on init.
     * Without this, navigating back to the project view shows a blank progress bar
     * because the ViewModel is recreated and misses all prior events.
     */
    private fun syncInitialIndexProgress() {
        if (projectIndexer == null) return
        scope.launch {
            projectIndexer.getProgressFlow(projectId).collect { progress ->
                indexProgress = progress
            }
        }
    }
}
