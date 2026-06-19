/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import io.askimo.core.logging.logger
import io.askimo.core.rag.state.IndexStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Caches indexed local file paths for the currently active project.
 *
 * Scope policy: one active project only.
 */
class ProjectIndexStateManager {
    private val log = logger<ProjectIndexStateManager>()

    private var activeProjectId: String? = null

    private val _indexedPaths = MutableStateFlow<Set<String>>(emptySet())
    val indexedPaths: StateFlow<Set<String>> = _indexedPaths.asStateFlow()

    suspend fun activateProject(projectId: String?) {
        if (activeProjectId == projectId) return

        activeProjectId = projectId
        if (projectId == null) {
            _indexedPaths.value = emptySet()
            return
        }

        refreshActiveProject()
    }

    suspend fun refreshActiveProject() {
        val projectId = activeProjectId ?: return
        _indexedPaths.value = withContext(Dispatchers.IO) {
            IndexStateManager.getIndexedLocalPathsForProject(projectId)
        }
        log.debug("Loaded ${_indexedPaths.value.size} indexed path(s) for project $projectId")
    }

    fun clear() {
        activeProjectId = null
        _indexedPaths.value = emptySet()
    }

    fun normalizePath(path: String): String = IndexStateManager.normalizePathKey(path)
}
