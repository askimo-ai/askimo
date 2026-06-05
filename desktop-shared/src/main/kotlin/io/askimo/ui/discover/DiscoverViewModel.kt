/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.plan.repository.PlanDefRepository
import io.askimo.core.skills.SkillRepository
import io.askimo.core.util.AskimoHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverViewModel(
    scope: CoroutineScope,
    private val chatSessionRepository: ChatSessionRepository,
    private val projectRepository: ProjectRepository,
    private val planDefRepository: PlanDefRepository,
    private val mcpInstanceService: McpInstanceService,
) {
    var totalChats by mutableStateOf<Int?>(null)
        private set

    var totalProjects by mutableStateOf<Int?>(null)
        private set

    var totalPlans by mutableStateOf<Int?>(null)
        private set

    var totalSkills by mutableStateOf<Int?>(null)
        private set

    var totalMcpServers by mutableStateOf(0)
        private set

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                totalChats = chatSessionRepository.countAll()
                totalProjects = projectRepository.countAll()
                totalPlans = planDefRepository.count()
                totalSkills = SkillRepository.countSkills(AskimoHome.skillsDir())
                totalMcpServers = runCatching { mcpInstanceService.getInstances().size }.getOrDefault(0)
            }
        }
    }
}
