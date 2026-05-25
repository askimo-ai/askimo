/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.di
import io.askimo.core.chat.service.ChatDirectiveService
import io.askimo.core.chat.service.ChatSessionExporterService
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.chat.service.ProjectService
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.mcp.McpClientFactory
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.plan.PlanService
import io.askimo.core.plan.repository.PlanDefRepository
import io.askimo.core.tools.ToolProviderImpl
import io.askimo.desktop.project.ProjectViewModel
import io.askimo.desktop.project.ProjectsViewModel
import io.askimo.desktop.settings.SettingsViewModel
import io.askimo.ui.common.monitoring.SystemResourceMonitor
import io.askimo.ui.plan.PlansViewModel
import io.askimo.ui.service.AvatarService
import io.askimo.ui.service.UpdateService
import io.askimo.ui.session.SessionManager
import io.askimo.ui.session.SessionsViewModel
import io.askimo.ui.session.command.DeleteSessionFromProjectCommand
import io.askimo.ui.shell.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/**
 * Koin module for desktop application dependencies.
 */
val desktopModule = module {

    single<AppContext> { AppContext.getInstance() }

    single { DatabaseManager.getInstance() }

    single { get<DatabaseManager>().getChatSessionRepository() }
    single { get<DatabaseManager>().getChatMessageRepository() }
    single { get<DatabaseManager>().getChatDirectiveRepository() }
    single { get<DatabaseManager>().getProjectRepository() }
    single { get<DatabaseManager>().getPlanExecutionRepository() }

    single { ProjectService(projectRepository = get()) }

    // Plan repositories & service
    single { PlanDefRepository() }
    single {
        PlanService(
            planDefRepository = get(),
            planExecutionRepository = get(),
            appContext = get(),
        )
    }

    single {
        ChatSessionService(
            sessionRepository = get(),
            messageRepository = get(),
            appContext = get(),
        )
    }
    single {
        ChatSessionExporterService(
            sessionRepository = get(),
            messageRepository = get(),
        )
    }
    single { ChatDirectiveService(repository = get()) }

    single { McpClientFactory() }
    single { McpInstanceService(mcpClientFactory = get()) }

    single { ToolProviderImpl(mcpInstanceService = get()) }

    single { SystemResourceMonitor() }

    single {
        SessionManager(
            chatSessionService = get(),
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        )
    }

    factory { (scope: CoroutineScope, sessionManager: SessionManager, onCreateNewSession: () -> String, onRenameComplete: () -> Unit) ->
        SessionsViewModel(
            scope = scope,
            sessionService = get(),
            sessionManager = sessionManager,
            onCreateNewSession = onCreateNewSession,
            onRenameComplete = onRenameComplete,
        )
    }

    factory { (scope: CoroutineScope) ->
        ProjectsViewModel(scope = scope)
    }

    factory { (scope: CoroutineScope) ->
        PlansViewModel(scope = scope, planService = get())
    }

    factory { (scope: CoroutineScope, projectId: String) ->
        ProjectViewModel(scope = scope, projectId = projectId, projectIndexer = getOrNull())
    }

    factory { (scope: CoroutineScope) ->
        SettingsViewModel(scope = scope, appContext = get())
    }

    // Commands
    factory { (scope: CoroutineScope) ->
        DeleteSessionFromProjectCommand(
            chatSessionRepository = get(),
            scope = scope,
        )
    }

    single { UpdateService() }
    single { AvatarService() }
    factory { (scope: CoroutineScope) ->
        UpdateViewModel(
            scope = scope,
            updateService = get(),
        )
    }
}

/**
 * All modules for the desktop application.
 */
val allDesktopModules = listOf(
    desktopRagModule,
    desktopModule,
)
