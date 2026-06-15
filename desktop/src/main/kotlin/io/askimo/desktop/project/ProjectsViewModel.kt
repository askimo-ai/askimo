/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.service.ProjectService
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectsRefreshEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.ui.shell.ProjectsSidebarState
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * ViewModel for managing projects in the desktop application.
 */
class ProjectsViewModel(
    private val scope: CoroutineScope,
    private val projectService: ProjectService = GlobalContext.get().get(),
) : ProjectsSidebarState {
    private val log = logger<ProjectsViewModel>()
    private val projectRepository = DatabaseManager.getInstance().getProjectRepository()

    override var projects by mutableStateOf<List<Project>>(emptyList())
        private set

    override val starredProjects: List<Project> get() = projects.filter { it.isStarred }

    var pagedProjects by mutableStateOf<Pageable<Project>?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var deleteProjectSuccessfulBannerMessage by mutableStateOf<String?>(null)
        private set

    var searchQuery by mutableStateOf("")
        private set

    private var searchDebounceJob: Job? = null

    private var projectsPerPage = 10

    fun setPageSize(size: Int) {
        projectsPerPage = size
        loadProjectsPaged(1)
    }

    init {
        loadProjects()
        loadProjectsPaged(1)
        subscribeToProjectEvents()
    }

    /**
     * Subscribe to internal events to keep project list updated.
     */
    private fun subscribeToProjectEvents() {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectsRefreshEvent>()
                .collect { event ->
                    log.debug("Projects refresh requested: ${event.reason ?: "no reason specified"}")
                    refresh()
                }
        }
    }

    /**
     * Load all projects from the database.
     */
    fun loadProjects() {
        scope.launch {
            try {
                isLoading = true
                projects = withContext(Dispatchers.IO) {
                    projectRepository.getAllProjects()
                }
                log.debug("Loaded ${projects.size} projects")
            } catch (e: Exception) {
                log.error("Failed to load projects", e)
                projects = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Load projects for a specific page, respecting any active [searchQuery].
     */
    fun loadProjectsPaged(page: Int = 1) {
        isLoading = true
        errorMessage = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (searchQuery.isBlank()) {
                        projectRepository.getProjectsPaged(page, projectsPerPage)
                    } else {
                        projectRepository.searchProjectsPaged(searchQuery, page, projectsPerPage)
                    }
                }
                pagedProjects = result
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "loading projects",
                    LocalizationManager.getString("projects.error.loading"),
                )
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Update the search query and reload page 1 with a 300 ms debounce.
     */
    fun updateSearch(query: String) {
        searchQuery = query
        searchDebounceJob?.cancel()
        searchDebounceJob = scope.launch {
            delay(300)
            loadProjectsPaged(1)
        }
    }

    /**
     * Clear the search query and reload.
     */
    fun clearSearch() {
        searchQuery = ""
        searchDebounceJob?.cancel()
        loadProjectsPaged(1)
    }

    /**
     * Reload the current page.
     */
    fun refresh() {
        loadProjectsPaged(pagedProjects?.currentPage ?: 1)
        loadProjects()
    }

    /**
     * Go to the next page.
     */
    fun nextPage() {
        pagedProjects?.let {
            if (it.hasNextPage) {
                loadProjectsPaged(it.currentPage + 1)
            }
        }
    }

    /**
     * Go to the previous page.
     */
    fun previousPage() {
        pagedProjects?.let {
            if (it.hasPreviousPage) {
                loadProjectsPaged(it.currentPage - 1)
            }
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        errorMessage = null
    }

    /**
     * Dismiss success message.
     */
    fun dismissSuccessMessage() {
        deleteProjectSuccessfulBannerMessage = null
    }

    /**
     * Toggle the starred status of a project.
     */
    fun starProject(projectId: String, isStarred: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                projectRepository.starProject(projectId, isStarred)
            }
            refresh()
        }
    }

    /**
     * Delete a project by ID.
     */
    fun deleteProject(projectId: String) {
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    projectService.deleteProject(projectId)
                }
                if (deleted) {
                    refresh()
                } else {
                    errorMessage = LocalizationManager.getString("projects.error.not.found")
                }
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "deleting project",
                    LocalizationManager.getString("projects.error.deleting"),
                )
            }
        }
    }

    /**
     * Update an existing project.
     */
    fun updateProject(projectId: String, name: String, description: String?, knowledgeSources: List<KnowledgeSourceConfig>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    projectRepository.updateProject(
                        projectId = projectId,
                        name = name,
                        description = description,
                        knowledgeSources = knowledgeSources,
                    )
                }
                log.debug("Updated project $projectId")
                refresh()
            } catch (e: Exception) {
                log.error("Failed to update project $projectId", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "updating project",
                    LocalizationManager.getString("projects.error.updating"),
                )
            }
        }
    }
}
