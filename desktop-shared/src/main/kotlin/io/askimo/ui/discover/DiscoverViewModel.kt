/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.agent.repository.AgentRunHistoryRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.ResourceCollectionRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.plan.repository.PlanDefRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverViewModel(
    scope: CoroutineScope,
    private val chatSessionRepository: ChatSessionRepository,
    private val projectRepository: ProjectRepository,
    private val planDefRepository: PlanDefRepository,
    private val agentRunHistoryRepository: AgentRunHistoryRepository = DatabaseManager.getInstance().getAgentRunHistoryRepository(),
    private val resourceCollectionRepository: ResourceCollectionRepository = DatabaseManager.getInstance().getResourceCollectionRepository(),
) {
    var totalChats by mutableStateOf<Int?>(null)
        private set

    var totalProjects by mutableStateOf<Int?>(null)
        private set

    var totalPlans by mutableStateOf<Int?>(null)
        private set

    var totalAgentRuns by mutableStateOf<Int?>(null)
        private set

    var totalResourceCollections by mutableStateOf<Int?>(null)
        private set

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                totalChats = chatSessionRepository.countAll()
                totalProjects = projectRepository.countAll()
                totalPlans = planDefRepository.count()
                totalAgentRuns = agentRunHistoryRepository.countAll()
                totalResourceCollections = resourceCollectionRepository.countAll()
            }
        }
    }
}
