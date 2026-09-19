/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.repository.AgentRunHistoryRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Global service managing [AgentRunViewModel] instances across the entire application.
 *
 * Mirrors [io.askimo.ui.session.SessionManager]'s caching pattern: ViewModels are cached
 * by workspace ID and survive navigation away from the agent view (e.g., switching to
 * Settings, Projects, or other views). This ensures in-flight agent runs complete and
 * persist to history regardless of where the user navigates.
 *
 * One instance per application lifetime; no lifecycle tied to any composable.
 */
object AgentRunManager {
    private val log = logger<AgentRunManager>()

    // Cache of AgentRunViewModel instances by workspace ID
    private val agentRunViewModels = mutableMapOf<String, AgentRunViewModel>()

    // Shared coroutine scope for all agent runs (not tied to any UI context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Repository for saving agent run history
    private val historyRepo: AgentRunHistoryRepository = DatabaseManager.getInstance().getAgentRunHistoryRepository()

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                shutdown()
            },
        )
        log.debug("AgentRunManager initialized")
    }

    /**
     * Get or create an [AgentRunViewModel] for a workspace.
     * The ViewModel persists across navigation and is reused on subsequent access.
     *
     * @param workspace The workspace to manage agent runs for
     * @param skills The skill definitions available for this workspace
     * @param onRunCompleted Callback to invoke when an agent run completes
     * @return Cached or newly-created AgentRunViewModel for this workspace
     */
    internal fun getOrCreateAgentRunViewModel(
        workspace: Workspace,
        skills: List<SkillDefinition>,
        onRunCompleted: () -> Unit = {},
    ): AgentRunViewModel {
        agentRunViewModels[workspace.id]?.let { return it }

        val viewModel = AgentRunViewModel(
            workspace = workspace,
            skills = skills,
            historyRepo = historyRepo,
            onRunCompleted = onRunCompleted,
        )

        agentRunViewModels[workspace.id] = viewModel
        log.debug("Created AgentRunViewModel for workspace: ${workspace.id} (total cached: ${agentRunViewModels.size})")
        return viewModel
    }

    /**
     * Close and remove a cached [AgentRunViewModel].
     * Call this when a workspace is deleted or no longer needed.
     *
     * @param workspaceId The workspace ID to close the ViewModel for
     */
    internal fun closeAgentRun(workspaceId: String) {
        agentRunViewModels.remove(workspaceId)?.let { viewModel ->
            viewModel.close()
            log.debug("Closed AgentRunViewModel for workspace: $workspaceId (total cached: ${agentRunViewModels.size})")
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
        scope.cancel()
    }
}

