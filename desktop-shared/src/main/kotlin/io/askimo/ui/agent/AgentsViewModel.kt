/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.repository.AgentRunHistoryRepository
import io.askimo.core.agent.service.WorkspaceService
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.AgentRunTitleUpdatedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * ViewModel backing `agentsView` — owns the current [Workspace], the run-history list shown
 * in the side panel, and the pending history record selected for preload. Mirrors
 * [io.askimo.ui.chat.ChatViewModel]'s role for regular chat, keeping this business logic
 * (workspace resolution, history refresh, title-event patching) out of the composable.
 */
internal class AgentsViewModel(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService = GlobalContext.get().get(),
    private val historyRepo: AgentRunHistoryRepository = DatabaseManager.getInstance().getAgentRunHistoryRepository(),
) {
    // User-chosen workspace (persisted across sessions). Resolved off the UI thread since
    // workspaceService.resolveCurrent() does several blocking DB transactions.
    // Runs/history must never be saved before this resolves — the agent_run_history table
    // enforces a NOT NULL foreign key on workspace_id, so a real, persisted Workspace row
    // must exist first.
    var workspace by mutableStateOf<Workspace?>(null)
        private set

    // One row per conversation — the latest turn represents the whole thread in the list;
    // selecting it reconstructs the full multi-turn conversation (see AgentRunViewModel.preload).
    var runHistory by mutableStateOf(listOf<AgentRunRecord>())
        private set

    var pendingHistoryRecord by mutableStateOf<AgentRunRecord?>(null)
        private set

    // Bumped every time a run completes — used by the workspace-files panel to know when
    // to refresh its listing (files may have changed) and auto-switch/expand to that tab.
    var historyRefreshKey by mutableStateOf(0)
        private set

    init {
        resolveWorkspace()
        observeTitleEvents()
    }

    private fun resolveWorkspace() {
        scope.launch {
            workspace = withContext(Dispatchers.IO) { workspaceService.resolveCurrent() }
            refreshHistory()
        }
    }

    /** Live-patches a conversation's title the moment the async AI-generated title lands. */
    private fun observeTitleEvents() {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<AgentRunTitleUpdatedEvent>()
                .collect { event ->
                    runHistory = runHistory.map { record ->
                        if (record.conversationId == event.conversationId) {
                            record.copy(title = event.newTitle)
                        } else {
                            record
                        }
                    }
                }
        }
    }

    fun refreshHistory() {
        val ws = workspace ?: return
        scope.launch {
            val all = withContext(Dispatchers.IO) { historyRepo.findByWorkspaceId(ws.id) }
            runHistory = all
                .groupBy { it.conversationId }
                .map { (_, turns) -> turns.maxBy { it.createdAt } }
                .sortedByDescending { it.createdAt }
        }
    }

    fun selectWorkspace(dir: File) {
        workspace = workspaceService.select(dir)
        historyRefreshKey++
        refreshHistory()
    }

    fun selectHistoryRecord(record: AgentRunRecord) {
        pendingHistoryRecord = record
    }

    fun consumePendingHistoryRecord() {
        pendingHistoryRecord = null
    }

    fun deleteHistoryRecord(record: AgentRunRecord) {
        scope.launch {
            withContext(Dispatchers.IO) { historyRepo.deleteByConversationId(record.conversationId) }
            historyRefreshKey++
            refreshHistory()
        }
    }

    fun onRunCompleted() {
        historyRefreshKey++
        refreshHistory()
    }
}
