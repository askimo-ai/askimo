/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.repository.AgentRunHistoryRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.AgentRunCompletedEvent
import io.askimo.core.logging.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Global service managing [AgentRunViewModel] instances across the entire application.
 *
 * ViewModels survive navigation (e.g., to Settings, Projects, Chat) so in-flight agent runs
 * can complete in the background. One instance per application lifetime.
 *
 * Run completion is published via [EventBus] to prevent stale callback issues when ViewModels
 * persist while observers change. Cache is bounded to [MAX_CACHED_VIEWMODELS]; inactive ViewModels
 * are cleaned up on capacity, preventing unbounded memory growth.
 */
object AgentRunManager {
    private val log = logger<AgentRunManager>()

    private const val MAX_CACHED_VIEWMODELS = 10

    // Cache of AgentRunViewModel instances by workspace ID
    private val agentRunViewModels = mutableMapOf<String, AgentRunViewModel>()

    // Shared coroutine scope for all agent runs (not tied to any UI context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Repository for saving agent run history
    private val historyRepo: AgentRunHistoryRepository = DatabaseManager.getInstance().getAgentRunHistoryRepository()

    // Track the currently active workspace to avoid removing it from cache
    private var activeWorkspaceId: String? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                shutdown()
            },
        )
        log.debug("AgentRunManager initialized")
    }

    /**
     * Get or create a cached [AgentRunViewModel]. Cleans up inactive ViewModels at capacity.
     * Run completion is published via [EventBus] so all observers get notified.
     */
    internal fun getOrCreateAgentRunViewModel(
        workspace: Workspace,
        skills: List<SkillDefinition>,
    ): AgentRunViewModel {
        agentRunViewModels[workspace.id]?.let {
            activeWorkspaceId = workspace.id
            return it
        }

        // Clean up inactive ViewModels before creating a new one (mirrors SessionManager pattern)
        if (agentRunViewModels.size >= MAX_CACHED_VIEWMODELS) {
            cleanupInactiveViewModels()
        }

        val viewModel = AgentRunViewModel(
            workspace = workspace,
            skills = skills,
            historyRepo = historyRepo,
            onRunCompleted = { publishRunCompleted(workspace.id) },
        )

        agentRunViewModels[workspace.id] = viewModel
        activeWorkspaceId = workspace.id
        log.debug("Created AgentRunViewModel for workspace: ${workspace.id} (total cached: ${agentRunViewModels.size})")
        return viewModel
    }

    /**
     * Publish a run completion event to the EventBus.
     * Called internally by AgentRunViewModel when a run finishes.
     */
    private fun publishRunCompleted(workspaceId: String) {
        EventBus.post(AgentRunCompletedEvent(workspaceId))
    }

    /**
     * Close and remove a cached [AgentRunViewModel].
     * Only call this for explicit cleanup (workspace deletion).
     *
     * @param workspaceId The workspace ID to close the ViewModel for
     */
    internal fun closeAgentRun(workspaceId: String) {
        agentRunViewModels.remove(workspaceId)?.let { viewModel ->
            viewModel.close()
            if (activeWorkspaceId == workspaceId) {
                activeWorkspaceId = null
            }
            log.debug("Closed AgentRunViewModel for workspace: $workspaceId (total cached: ${agentRunViewModels.size})")
        }
    }

    /**
     * Remove inactive ViewModels (not active, not running) at capacity.
     * If none are inactive, remove oldest to make room. Mirrors SessionManager.
     */
    private fun cleanupInactiveViewModels() {
        val inactiveViewModels = agentRunViewModels.filter { (workspaceId, viewModel) ->
            workspaceId != activeWorkspaceId && !viewModel.isRunning
        }

        if (inactiveViewModels.isEmpty()) {
            // All ViewModels are either active or running. Remove the oldest one.
            val oldestWorkspace = agentRunViewModels.keys
                .firstOrNull { it != activeWorkspaceId }

            if (oldestWorkspace != null) {
                agentRunViewModels[oldestWorkspace]?.close()
                agentRunViewModels.remove(oldestWorkspace)
                log.warn("Removed oldest ViewModel (at capacity): $oldestWorkspace")
            }
        } else {
            // Remove one inactive ViewModel
            val (workspaceId, viewModel) = inactiveViewModels.entries.first()
            viewModel.close()
            agentRunViewModels.remove(workspaceId)
            log.debug("Removed inactive ViewModel: $workspaceId (total cached: ${agentRunViewModels.size})")
        }
    }

    /**
     * Shutdown hook — cancels all in-flight agent runs and cleans up resources.
     * Called automatically on application shutdown.
     */
    private fun shutdown() {
        log.info("Shutting down AgentRunManager. Closing ${agentRunViewModels.size} agent run ViewModels.")
        agentRunViewModels.values.forEach { it.close() }
        agentRunViewModels.clear()
        activeWorkspaceId = null
        scope.cancel()
    }
}
