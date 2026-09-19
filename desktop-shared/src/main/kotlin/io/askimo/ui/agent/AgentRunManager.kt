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
 * ViewModels are cached by workspace ID and survive navigation away from the agent view.
 * This allows in-flight agent runs to complete even when the user navigates to Settings,
 * Projects, or Chat.
 *
 * One instance per application lifetime; no lifecycle tied to any composable.
 *
 * Run completion is published via [EventBus] (see [AgentRunCompletedEvent]) to avoid
 * stale callback issues when ViewModels persist across navigation while observers change.
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
     * Get or create a cached [AgentRunViewModel] for a workspace.
     * Publishes run completion via [EventBus], so observers attached after creation still get notified.
     */
    internal fun getOrCreateAgentRunViewModel(
        workspace: Workspace,
        skills: List<SkillDefinition>,
    ): AgentRunViewModel {
        agentRunViewModels[workspace.id]?.let { return it }

        val viewModel = AgentRunViewModel(
            workspace = workspace,
            skills = skills,
            historyRepo = historyRepo,
            onRunCompleted = { publishRunCompleted(workspace.id) },
        )

        agentRunViewModels[workspace.id] = viewModel
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
