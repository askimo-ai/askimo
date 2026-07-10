/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import io.askimo.core.backup.BackupManager
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.context.getConfigInfo
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.Event
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.LanguageDirectiveChangedEvent
import io.askimo.core.event.internal.RunCodeEvent
import io.askimo.core.event.system.InvalidateCacheEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.LogbackConfigurator
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.providers.ModelProvider
import io.askimo.core.user.domain.UserProfile
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.PersonalAskimoHome
import io.askimo.desktop.chat.communityProjectSidePanel
import io.askimo.desktop.di.allDesktopModules
import io.askimo.desktop.plan.planEditorView
import io.askimo.desktop.project.ProjectsViewModel
import io.askimo.desktop.project.editProjectDialog
import io.askimo.desktop.project.newProjectDialog
import io.askimo.desktop.project.projectView
import io.askimo.desktop.project.projectsView
import io.askimo.desktop.settings.SettingsSection
import io.askimo.desktop.settings.SettingsViewModel
import io.askimo.desktop.settings.aboutDialog
import io.askimo.desktop.settings.fileViewerDialog
import io.askimo.desktop.settings.providerSelectionDialog
import io.askimo.desktop.settings.settingsViewWithSidebar
import io.askimo.desktop.shell.footerBar
import io.askimo.desktop.shell.navigationSidebar
import io.askimo.desktop.shell.telemetryPanel
import io.askimo.desktop.user.userProfileDialog
import io.askimo.ui.chat.ChatViewModel
import io.askimo.ui.chat.chatView
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.components.successIcon
import io.askimo.ui.common.dialog.errorDialog
import io.askimo.ui.common.dialog.updateCheckDialog
import io.askimo.ui.common.i18n.provideLocalization
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.keymap.KeyMapManager.AppShortcut
import io.askimo.ui.common.preferences.AccountPreferences
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents.alertDialog
import io.askimo.ui.common.theme.BackgroundImage
import io.askimo.ui.common.theme.LocalBackgroundActive
import io.askimo.ui.common.theme.LocalCodeFontFamily
import io.askimo.ui.common.theme.LocalFontScale
import io.askimo.ui.common.theme.LocalLayoutDensity
import io.askimo.ui.common.theme.LocalSpacing
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.SpacingValues
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.theme.appBackground
import io.askimo.ui.common.theme.applyAccentToColorScheme
import io.askimo.ui.common.theme.createCustomTypography
import io.askimo.ui.common.theme.generateAppColorScheme
import io.askimo.ui.common.theme.loadCodeFontFamily
import io.askimo.ui.common.theme.parseAccentColor
import io.askimo.ui.common.ui.util.CustomUriHandler
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.discover.DiscoverViewModel
import io.askimo.ui.discover.discoverView
import io.askimo.ui.onboarding.onboardingWizardDialog
import io.askimo.ui.plan.PlansViewModel
import io.askimo.ui.plan.planDetailView
import io.askimo.ui.plan.plansGalleryView
import io.askimo.ui.service.TelemetryExportService
import io.askimo.ui.session.SessionManager
import io.askimo.ui.session.SessionsViewModel
import io.askimo.ui.session.command.DeleteSessionFromProjectCommand
import io.askimo.ui.session.exportSessionDialog
import io.askimo.ui.session.renameSessionDialog
import io.askimo.ui.session.sessionMemoryDialog
import io.askimo.ui.session.sessionsView
import io.askimo.ui.shell.ChatViewState
import io.askimo.ui.shell.ErrorDialogState
import io.askimo.ui.shell.EventLogDockPosition
import io.askimo.ui.shell.NativeMenuBar
import io.askimo.ui.shell.UpdateViewModel
import io.askimo.ui.shell.eventLogPanel
import io.askimo.ui.shell.eventLogWindow
import io.askimo.ui.shell.feedbackPromptDialog
import io.askimo.ui.shell.globalErrorHandler
import io.askimo.ui.shell.globalSearchDialog
import io.askimo.ui.shell.happinessGateDialog
import io.askimo.ui.shell.rememberPersistedWindowState
import io.askimo.ui.shell.rememberThemeState
import io.askimo.ui.shell.splashScreen
import io.askimo.ui.shell.starPromptDialog
import io.askimo.ui.shell.systemResourcesDialog
import io.askimo.ui.skills.skillsView
import io.askimo.ui.terminal.PendingTerminalCommand
import io.askimo.ui.terminal.terminalPanel
import io.askimo.ui.util.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.koin.core.context.GlobalContext.get
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import java.awt.Cursor
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private val log = currentFileLogger()

fun main() {
    // Apply software rendering flags BEFORE any Compose/Skia initialisation.
    // These properties have no effect once the JVM has started, so they must be
    // set here. When hardware acceleration is disabled (user opted out, e.g. to
    // fix VRR/G-Sync frame-rate conflicts on Windows) we force Skiko's software
    // rasteriser and disable Java2D hardware pipelines.
    if (!AccountPreferences.device().getHardwareAccelerationEnabled()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("sun.java2d.opengl", "false")
        log.info("Hardware acceleration disabled — running in software rendering mode")
    }

    AskimoHome.register(PersonalAskimoHome)

    // Reconfigure the FILE log appender to use the resolved Askimo home path.
    // logback.xml cannot reliably resolve ASKIMO_HOME at parse time (before JVM startup),
    // so we redirect the appender programmatically here, after AskimoHome is registered.
    LogbackConfigurator.configureLogDirectory(AskimoHome.base().resolve("logs"))

    AppContext.initialize(ExecutionMode.STATEFUL_TOOLS_MODE)

    startKoin {
        modules(allDesktopModules)
    }

    if (AppConfig.developer.enabled &&
        AppConfig.developer.active
    ) {
        LogbackConfigurator.registerEventBusAppender()
    }

    application {
        val savedLocale = ThemePreferences.locale.value
        LocalizationManager.setLocale(savedLocale)

        val icon = BitmapPainter(
            Image.makeFromEncoded(
                object {}.javaClass.getResourceAsStream("/images/askimo_512.png")?.readBytes()
                    ?: error("Icon not found"),
            ).toComposeImageBitmap(),
        )

        // Load saved window state or use defaults (persisted automatically via rememberPersistedWindowState)
        val windowState = rememberPersistedWindowState()

        Window(
            icon = icon,
            onCloseRequest = {
                val messageCount = runCatching {
                    AppContext.getInstance().telemetry.metricsFlow.value.llmCallsByProvider.values.sum()
                }.getOrDefault(0)
                Analytics.trackSessionEnd(messageCount)
                Analytics.shutdown()
                exitApplication()
            },
            title = "Askimo",
            state = windowState,
        ) {
            app(frameWindowScope = this@Window, windowState = windowState)
        }
    }
}

@Composable
@Preview
fun app(frameWindowScope: FrameWindowScope? = null, windowState: WindowState? = null) {
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isReady = true }

    var currentView by remember { mutableStateOf(View.DISCOVER) }
    var previousView by remember { mutableStateOf(View.CHAT) }
    var settingsSection by remember { mutableStateOf(SettingsSection.GENERAL) }
    var isSidebarExpanded by remember { mutableStateOf(true) }
    var isProjectsExpanded by remember { mutableStateOf(true) }
    var isSessionsExpanded by remember { mutableStateOf(true) }
    var showPlansInSidebar by remember { mutableStateOf(ApplicationPreferences.getShowPlansInSidebar()) }
    var showSkillsInSidebar by remember { mutableStateOf(ApplicationPreferences.getShowSkillsInSidebar()) }
    var showProjectsInSidebar by remember { mutableStateOf(ApplicationPreferences.getShowProjectsInSidebar()) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var sidebarWidthFraction by remember { mutableStateOf(ThemePreferences.getMainSidebarWidthFraction()) }
    var showQuitDialog by remember { mutableStateOf(false) }
    var showInvalidateCacheDialog by remember { mutableStateOf(false) }
    var showCacheDeletedDialog by remember { mutableStateOf(false) }
    var showClearPreferencesDialog by remember { mutableStateOf(false) }
    var showPreferencesClearedDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showEventLogWindow by remember { mutableStateOf(false) }
    var showEventLogPanel by remember { mutableStateOf(false) }
    var showSystemDiagnosticsDialog by remember { mutableStateOf(false) }
    var eventLogDockPosition by remember { mutableStateOf(EventLogDockPosition.BOTTOM) }
    var eventLogPanelSize by remember { mutableStateOf(300.dp) } // Default size
    var showTerminalPanel by remember { mutableStateOf(false) }
    var terminalPanelSize by remember { mutableStateOf(300.dp) } // Default size
    var pendingTerminalCommand by remember { mutableStateOf<PendingTerminalCommand?>(null) }
    var showStarPromptDialog by remember { mutableStateOf(false) }
    var showHappinessGateDialog by remember { mutableStateOf(false) }
    var showFeedbackPromptDialog by remember { mutableStateOf(false) }
    var feedbackSentiment by remember { mutableStateOf("neutral") }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showEditProjectDialog by remember { mutableStateOf(false) }
    var showGlobalSearchDialog by remember { mutableStateOf(false) }
    var editingProjectId by remember { mutableStateOf<String?>(null) }
    var errorDialogState by remember { mutableStateOf(ErrorDialogState()) }
    var showFileViewerDialog by remember { mutableStateOf(false) }
    var fileViewerPath by remember { mutableStateOf("") }
    var fileViewerContent by remember { mutableStateOf("") }
    var fileViewerTitle by remember { mutableStateOf("File Viewer") }
    var showImportBackupConfirm by remember { mutableStateOf(false) }
    var pendingImportBackupPath by remember { mutableStateOf<Path?>(null) }

    // VRR / G-Sync hint (Windows only, shown once for users with high-refresh displays)
    var showVrrHintDialog by remember { mutableStateOf(false) }

    // User Profile
    var userProfile by remember { mutableStateOf<UserProfile?>(null) }
    var showUserProfileDialog by remember { mutableStateOf(false) }

    // Onboarding
    var showOnboardingWizard by remember { mutableStateOf(false) }

    // Store chat state per session for restoration when switching
    val sessionChatStates = remember { mutableStateMapOf<String, ChatViewState>() }
    val eventLogEvents = remember { mutableStateListOf<Event>() }

    // Load user profile on startup and detect first run
    LaunchedEffect(Unit) {
        val profileRepo = DatabaseManager.getInstance().getUserProfileRepository()
        userProfile = withContext(Dispatchers.IO) {
            profileRepo.getProfile()
        }

        if (!ApplicationPreferences.isOnboardingCompleted()) {
            // Seed default directives on first launch
            withContext(Dispatchers.IO) {
                DatabaseManager.getInstance().getChatDirectiveRepository().seedDefaultDirectives()
            }
            showOnboardingWizard = true
        }

        // Set user profile directive in AppContext for AI personalization
        val personalizationContext = withContext(Dispatchers.IO) {
            profileRepo.getPersonalizationContext()
        }
        AppContext.getInstance().setUserProfileDirective(personalizationContext)
    }

    LaunchedEffect(Unit) {
        EventBus.developerEvents.collect { event ->
            eventLogEvents.add(0, event)
            if (eventLogEvents.size > 100) {
                eventLogEvents.removeAt(100)
            }
        }
    }

    LaunchedEffect(Unit) {
        EventBus.internalEvents.filterIsInstance<RunCodeEvent>().collect { event ->
            if (!showTerminalPanel) Analytics.track(AnalyticsEvent.TERMINAL_OPENED, mapOf("source" to "run_code"))
            showTerminalPanel = true
            pendingTerminalCommand = PendingTerminalCommand(
                code = event.code,
                couldExecute = event.couldExecute,
            )
        }
    }

    // Listen for errors – handled by the dedicated GlobalErrorHandler
    globalErrorHandler { state -> errorDialogState = state }

    val scope = rememberCoroutineScope()

    // Backup and restore helper functions
    fun exportBackup() {
        scope.launch {
            try {
                val timestamp = withContext(Dispatchers.IO) {
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                }
                val defaultFileName = "askimo_backup_$timestamp.zip"

                val backupFile = FileDialogUtils.pickSavePath(
                    suggestedName = defaultFileName.substringBeforeLast('.'),
                    extension = "zip",
                    title = LocalizationManager.getString("backup.export.title"),
                ) ?: return@launch

                val savedFile = withContext(Dispatchers.IO) {
                    BackupManager.exportBackup(Paths.get(backupFile.absolutePath))
                }

                errorDialogState = ErrorDialogState(
                    show = true,
                    title = LocalizationManager.getString("backup.export.success.title"),
                    message = LocalizationManager.getString("backup.export.success.message", savedFile.toString()),
                )
            } catch (e: Exception) {
                errorDialogState = ErrorDialogState(
                    show = true,
                    title = LocalizationManager.getString("backup.export.error.title"),
                    message = LocalizationManager.getString("backup.export.error.message", e.message ?: "Unknown error"),
                )
                log.error("Error exporting backup", e)
            }
        }
    }

    fun importBackup() {
        scope.launch {
            try {
                val selectedPath = FileDialogUtils.pickFilePath(
                    title = LocalizationManager.getString("backup.import.title"),
                    extensions = listOf("zip"),
                ) ?: return@launch

                pendingImportBackupPath = Paths.get(selectedPath)
                showImportBackupConfirm = true
            } catch (e: Exception) {
                errorDialogState = ErrorDialogState(
                    show = true,
                    title = LocalizationManager.getString("backup.import.error.title"),
                    message = LocalizationManager.getString("backup.import.error.message", e.message ?: "Unknown error"),
                )
                log.error("Error importing backup", e)
            }
        }
    }

    val koin = get()

    val appContext = remember { koin.get<AppContext>() }
    val chatSessionService = remember { koin.get<ChatSessionService>() }

    val sessionManager = remember { koin.get<SessionManager>() }
    val sessionsViewModel = remember {
        koin.get<SessionsViewModel> {
            parametersOf(
                scope,
                sessionManager,
                {
                    chatSessionService.createSession(
                        ChatSession(
                            id = "",
                            title = "New Chat",
                            directiveId = null,
                        ),
                    ).id
                },
                {
                    sessionManager.activeSessionId?.let { sessionId ->
                        sessionManager.getOrCreateChatViewModel(sessionId).refreshSessionTitle()
                    }
                },
            )
        }
    }
    val projectsViewModel = remember { koin.get<ProjectsViewModel> { parametersOf(scope) } }
    val plansViewModel = remember { koin.get<PlansViewModel> { parametersOf(scope) } }
    val discoverViewModel = remember { koin.get<DiscoverViewModel> { parametersOf(scope) } }
    val settingsViewModel = remember { koin.get<SettingsViewModel> { parametersOf(scope) } }
    val updateViewModel = remember { koin.get<UpdateViewModel> { parametersOf(scope) } }

    val deleteSessionCommand = remember {
        koin.get<DeleteSessionFromProjectCommand> { parametersOf(scope) }
    }

    LaunchedEffect(Unit) {
        val newSessionId = UUID.randomUUID().toString()
        sessionManager.createNewSession(newSessionId)
    }

    LaunchedEffect(Unit) {
        delay(5000.milliseconds)
        updateViewModel.checkForUpdates(silent = true)

        while (true) {
            delay((24 * 60 * 60 * 1000L).milliseconds) // 24 hours
            updateViewModel.checkForUpdates(silent = true)
        }
    }

    val activeSessionId = sessionManager.activeSessionId
    val chatViewModel = remember(activeSessionId) {
        activeSessionId?.let { sessionId ->
            sessionManager.getOrCreateChatViewModel(sessionId)
        }
    }

    var showProviderSetupDialog by remember { mutableStateOf(false) }
    var showSessionMemoryDialog by remember { mutableStateOf(false) }
    var sessionMemorySessionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (appContext.getActiveProvider() == ModelProvider.UNKNOWN) {
            showProviderSetupDialog = true
        }
    }

    // Detect VRR/high-refresh display on Windows and show a one-time hint to
    // consider disabling hardware acceleration if the user experiences flickering.
    LaunchedEffect(Unit) {
        if (Platform.isWindows &&
            AccountPreferences.device().getHardwareAccelerationEnabled() &&
            !AccountPreferences.device().isVrrHintDismissed()
        ) {
            val refreshRate = withContext(Dispatchers.IO) {
                runCatching {
                    GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .defaultScreenDevice
                        .displayMode
                        .refreshRate
                }.getOrDefault(60)
            }
            if (refreshRate > 60) {
                showVrrHintDialog = true
            }
        }
    }

    // Initialize analytics first, then track retention and show star prompt
    LaunchedEffect(Unit) {
        Analytics.initialize()
        Analytics.sendInstallPingIfNeeded()
        val hasRag = withContext(Dispatchers.IO) {
            AskimoHome.projectsDir().toFile().let { it.exists() && it.listFiles()?.isNotEmpty() == true }
        }
        val hasMcp = withContext(Dispatchers.IO) {
            runCatching { koin.get<McpInstanceService>().getInstances().isNotEmpty() }.getOrDefault(false)
        }
        Analytics.track(
            AnalyticsEvent.APP_STARTED,
            mapOf(
                "mode" to "desktop",
                "has_rag" to hasRag.toString(),
                "has_mcp" to hasMcp.toString(),
                "language" to ThemePreferences.locale.value.language.take(5).ifBlank { "unknown" },
                "theme" to ThemePreferences.themeMode.value.name.lowercase(),
            ),
        )

        AccountPreferences.device().recordFirstUseIfNeeded()
        val launchCount = AccountPreferences.device().incrementLaunchCount()
        Analytics.trackRetentionMilestone(launchCount)

        if (AccountPreferences.device().shouldShowStarPrompt()) {
            showHappinessGateDialog = true
        }
    }

    // Theme state
    val themeState = rememberThemeState()
    val fontSettings = themeState.fontSettings
    val layoutDensity = themeState.layoutDensity
    val locale = themeState.locale
    val backgroundImage = themeState.backgroundImage
    val useDarkMode = themeState.useDarkMode
    val themePaletteStyle = themeState.themePaletteStyle
    val accentColor = remember(themeState.accentColorHex) { parseAccentColor(themeState.accentColorHex) }

    // Watch AI response language config and update directive
    var preferredResponseAILocale by remember { mutableStateOf(AppConfig.chat.defaultResponseAILocale) }

    // Listen for language directive changes from settings
    LaunchedEffect(Unit) {
        EventBus.internalEvents
            .filterIsInstance<LanguageDirectiveChangedEvent>()
            .collect { event ->
                preferredResponseAILocale = event.localeString
            }
    }

    LaunchedEffect(preferredResponseAILocale, locale) {
        val localeString = preferredResponseAILocale
        val aiLocale = if (localeString.isNullOrBlank()) {
            null // Let AI auto-detect language
        } else {
            val parts = localeString.split("_")
            if (parts.size == 2) {
                Locale.Builder().setLanguage(parts[0]).setRegion(parts[1]).build()
            } else {
                Locale.Builder().setLanguage(parts[0]).build()
            }
        }
        appContext.setLanguageDirective(aiLocale)
    }

    // React to locale changes - update menu bar and language settings
    LaunchedEffect(locale, frameWindowScope) {
        LocalizationManager.setLocale(locale)

        frameWindowScope?.let { scope ->
            NativeMenuBar.setup(
                frameWindowScope = scope,
                onShowAbout = { showAboutDialog = true },
                onShowEventLog = {
                    // Toggle between attached panel and detached window
                    if (!showEventLogPanel && !showEventLogWindow) {
                        showEventLogPanel = true // Default to attached
                    } else if (showEventLogPanel) {
                        showEventLogPanel = false // Close if already open
                    } else {
                        // If detached window is open, bring it to focus (handled by window manager)
                    }
                },
                onNewChat = {
                    sessionManager.clearActiveSession()
                    chatViewModel?.clearChat()
                    currentView = View.CHAT
                },
                onNewProject = {
                    showNewProjectDialog = true
                },
                onSearchInSessions = {
                    showGlobalSearchDialog = true
                },
                onShowSettings = {
                    currentView = View.SETTINGS
                },
                onCheckForUpdates = {
                    updateViewModel.checkForUpdates(silent = false)
                },
                onEnterFullScreen = {
                    windowState?.let { state ->
                        state.placement = if (state.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                    }
                },
                onNavigateToSessions = {
                    currentView = View.SESSIONS
                },
                onNavigateToProjects = {
                    currentView = View.PROJECTS
                    Analytics.track(AnalyticsEvent.RAG_PANEL_OPENED)
                },
                onNavigateToDiscover = {
                    currentView = View.DISCOVER
                },
                onToggleSidebar = {
                    isSidebarExpanded = !isSidebarExpanded
                    NativeMenuBar.updateSidebarMenuLabel(isSidebarExpanded)
                },
                onInvalidateCaches = {
                    showInvalidateCacheDialog = true
                },
                onExportBackup = {
                    exportBackup()
                },
                onImportBackup = {
                    importBackup()
                },
                onShowGettingStarted = {
                    showOnboardingWizard = true
                },
                onOpenTerminal = {
                    val opening = !showTerminalPanel
                    showTerminalPanel = opening
                    if (opening) Analytics.track(AnalyticsEvent.TERMINAL_OPENED, mapOf("source" to "toolbar"))
                },
                onClearPreferences = {
                    showClearPreferencesDialog = true
                },
                onTogglePlans = {
                    showPlansInSidebar = !showPlansInSidebar
                    ApplicationPreferences.setShowPlansInSidebar(showPlansInSidebar)
                    NativeMenuBar.updatePlansMenuLabel(showPlansInSidebar)
                },
                onToggleSkills = {
                    showSkillsInSidebar = !showSkillsInSidebar
                    ApplicationPreferences.setShowSkillsInSidebar(showSkillsInSidebar)
                    NativeMenuBar.updateSkillsMenuLabel(showSkillsInSidebar)
                },
                onToggleProjects = {
                    showProjectsInSidebar = !showProjectsInSidebar
                    ApplicationPreferences.setShowProjectsInSidebar(showProjectsInSidebar)
                    NativeMenuBar.updateProjectsMenuLabel(showProjectsInSidebar)
                },
                isPlansVisible = showPlansInSidebar,
                isSkillsVisible = showSkillsInSidebar,
                isProjectsVisible = showProjectsInSidebar,
                onShowSystemDiagnostics = { showSystemDiagnosticsDialog = true },
            )
        }
    }

    val baseColorScheme = generateAppColorScheme(
        isDark = useDarkMode,
        paletteStyle = themePaletteStyle,
    )
    val colorScheme = if (accentColor != null) {
        applyAccentToColorScheme(
            accent = accentColor,
            isDark = useDarkMode,
            paletteStyle = themePaletteStyle,
        )
    } else {
        baseColorScheme
    }

    val customTypography = remember(fontSettings) {
        createCustomTypography(fontSettings)
    }
    val customCodeFontFamily = remember(fontSettings.codeFontFamily) {
        loadCodeFontFamily(fontSettings.codeFontFamily)
    }

    val handleResumeSession: (String) -> Unit = { sessionId ->
        sessionManager.switchToSession(sessionId)
        selectedProjectId = null
        currentView = View.CHAT
    }

    provideLocalization(locale = locale) {
        CompositionLocalProvider(
            LocalFontScale provides fontSettings.fontSize.scale,
            LocalCodeFontFamily provides customCodeFontFamily,
            LocalLayoutDensity provides layoutDensity,
            LocalSpacing provides SpacingValues(scale = layoutDensity.scale),
            LocalUriHandler provides CustomUriHandler(
                onShowFileViewer = { title, filePath, content ->
                    fileViewerTitle = title
                    fileViewerPath = filePath
                    fileViewerContent = content
                    showFileViewerDialog = true
                },
                onShowError = { title, message ->
                    errorDialogState = ErrorDialogState(show = true, title = title, message = message)
                },
            ),
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = customTypography,
            ) {
                if (!isReady) {
                    splashScreen()
                } else {
                    appBackground(
                        backgroundImage = backgroundImage,
                        useDarkMode = useDarkMode,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Main application structure: MenuBar → Body (Stack) → Footer
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (backgroundImage is BackgroundImage.None) {
                                        MaterialTheme.colorScheme.background
                                    } else {
                                        Transparent
                                    },
                                ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                if (currentView == View.SETTINGS) {
                                    settingsViewWithSidebar(
                                        onClose = {
                                            currentView = previousView
                                        },
                                        settingsViewModel = settingsViewModel,
                                        selectedSection = settingsSection,
                                        onSectionChange = { section ->
                                            settingsSection = section
                                            if (section == SettingsSection.MCP_SERVERS) {
                                                Analytics.track(AnalyticsEvent.MCP_SETTINGS_OPENED)
                                            }
                                        },
                                    )
                                } else {
                                    // Main View - With sidebar and content
                                    // Main content area - supports event log docking at left/right/bottom
                                    // Event Log Panel - LEFT position
                                    if (showEventLogPanel && eventLogDockPosition == EventLogDockPosition.LEFT) {
                                        eventLogPanel(
                                            events = eventLogEvents,
                                            onDetach = {
                                                showEventLogPanel = false
                                                showEventLogWindow = true
                                            },
                                            onClose = {
                                                showEventLogPanel = false
                                            },
                                            onClearEvents = {
                                                eventLogEvents.clear()
                                            },
                                            onDockPositionChange = { newPosition ->
                                                eventLogDockPosition = newPosition
                                            },
                                            currentDockPosition = eventLogDockPosition,
                                            size = eventLogPanelSize,
                                            onSizeChange = { newSize -> eventLogPanelSize = newSize },
                                            modifier = Modifier.fillMaxHeight(),
                                        )
                                    }

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .onPreviewKeyEvent { keyEvent ->
                                                    val shortcut = KeyMapManager.handleKeyEvent(keyEvent)

                                                    when (shortcut) {
                                                        AppShortcut.NEW_CHAT -> {
                                                            chatViewModel?.clearChat()
                                                            currentView = View.CHAT
                                                            true
                                                        }

                                                        AppShortcut.CREATE_PROJECT -> {
                                                            showNewProjectDialog = true
                                                            true
                                                        }

                                                        AppShortcut.SEARCH_IN_CHAT -> {
                                                            if (currentView == View.CHAT && chatViewModel?.isSearchMode == false) {
                                                                chatViewModel.enableSearchMode()
                                                            }
                                                            true
                                                        }

                                                        AppShortcut.GLOBAL_SEARCH -> {
                                                            showGlobalSearchDialog = true
                                                            true
                                                        }

                                                        AppShortcut.TOGGLE_CHAT_HISTORY -> {
                                                            isSessionsExpanded = !isSessionsExpanded
                                                            true
                                                        }

                                                        AppShortcut.OPEN_SETTINGS -> {
                                                            previousView = currentView
                                                            currentView = View.SETTINGS
                                                            true
                                                        }

                                                        AppShortcut.STOP_AI_RESPONSE -> {
                                                            if (chatViewModel?.isLoading == true) {
                                                                chatViewModel.cancelResponse()
                                                                true
                                                            } else {
                                                                false
                                                            }
                                                        }

                                                        AppShortcut.QUIT_APPLICATION -> {
                                                            showQuitDialog = true
                                                            true
                                                        }

                                                        AppShortcut.ENTER_FULLSCREEN -> {
                                                            windowState?.let { state ->
                                                                state.placement = if (state.placement == WindowPlacement.Fullscreen) {
                                                                    WindowPlacement.Floating
                                                                } else {
                                                                    WindowPlacement.Fullscreen
                                                                }
                                                            }
                                                            true
                                                        }

                                                        AppShortcut.NAVIGATE_TO_SESSIONS -> {
                                                            currentView = View.SESSIONS
                                                            true
                                                        }

                                                        AppShortcut.NAVIGATE_TO_PROJECTS -> {
                                                            currentView = View.PROJECTS
                                                            Analytics.track(AnalyticsEvent.RAG_PANEL_OPENED)
                                                            true
                                                        }

                                                        else -> false
                                                    }
                                                },
                                        ) {
                                            Row(modifier = Modifier.fillMaxSize()) {
                                                BoxWithConstraints {
                                                    // Calculate actual sidebar width from fraction
                                                    // Min 200dp, max 35% of screen width, default ~20%
                                                    val minSidebarWidth = 200.dp
                                                    val maxSidebarWidthFraction = 0.35f
                                                    // Guard: max must never be less than min (happens when
                                                    // the window is resized smaller than ~571dp wide)
                                                    val maxSidebarWidth = maxOf(maxWidth * maxSidebarWidthFraction, minSidebarWidth)
                                                    val calculatedWidth = (maxWidth * sidebarWidthFraction).coerceIn(
                                                        minSidebarWidth,
                                                        maxSidebarWidth,
                                                    )

                                                    val effectiveSidebarExpanded = isSidebarExpanded

                                                    navigationSidebar(
                                                        isExpanded = effectiveSidebarExpanded,
                                                        width = calculatedWidth,
                                                        currentView = currentView,
                                                        isProjectsExpanded = isProjectsExpanded,
                                                        isSessionsExpanded = isSessionsExpanded,
                                                        projectsViewModel = projectsViewModel,
                                                        sessionsViewModel = sessionsViewModel,
                                                        currentSessionId = activeSessionId,
                                                        currentProjectId = selectedProjectId,
                                                        userProfile = userProfile,
                                                        showPlansInSidebar = showPlansInSidebar,
                                                        showSkillsInSidebar = showSkillsInSidebar,
                                                        showProjectsInSidebar = showProjectsInSidebar,
                                                        onToggleExpand = { isSidebarExpanded = !isSidebarExpanded },
                                                        onNewChat = {
                                                            chatViewModel?.clearChat()
                                                            currentView = View.CHAT
                                                        },
                                                        onToggleProjects = { currentView = View.PROJECTS },
                                                        onNewProject = {
                                                            showNewProjectDialog = true
                                                        },
                                                        onSelectProject = { projectId ->
                                                            selectedProjectId = projectId
                                                            currentView = View.PROJECT_DETAIL
                                                        },
                                                        onToggleSessions = { isSessionsExpanded = !isSessionsExpanded },
                                                        onNavigateToSessions = { currentView = View.SESSIONS },
                                                        onResumeSession = handleResumeSession,
                                                        onDeleteSession = { sessionId ->
                                                            sessionsViewModel.deleteSessionWithCleanup(sessionId)
                                                        },
                                                        onStarSession = { sessionId, isStarred ->
                                                            sessionsViewModel.updateSessionStarred(sessionId, isStarred)
                                                        },
                                                        onStarProject = { projectId, isStarred ->
                                                            projectsViewModel.starProject(projectId, isStarred)
                                                        },
                                                        onRenameSession = { sessionId, _ ->
                                                            sessionsViewModel.showRenameDialog(sessionId)
                                                        },
                                                        onExportSession = { sessionId ->
                                                            sessionsViewModel.exportSession(sessionId)
                                                        },
                                                        onShowSessionSummary = { sessionId ->
                                                            sessionMemorySessionId = sessionId
                                                            showSessionMemoryDialog = true
                                                        },
                                                        onEditProject = { projectId ->
                                                            editingProjectId = projectId
                                                            showEditProjectDialog = true
                                                        },
                                                        onDeleteProject = { projectId ->
                                                            projectsViewModel.deleteProject(projectId)
                                                            if (selectedProjectId == projectId) {
                                                                currentView = View.SESSIONS
                                                            }
                                                        },
                                                        onEditUserProfile = {
                                                            showUserProfileDialog = true
                                                        },
                                                        onNavigateToSettings = {
                                                            previousView = currentView
                                                            currentView = View.SETTINGS
                                                        },
                                                        onNavigateToAbout = {
                                                            showAboutDialog = true
                                                        },
                                                        onNavigateToPlans = {
                                                            currentView = View.PLANS
                                                            Analytics.track(AnalyticsEvent.PLAN_VIEW_OPENED)
                                                        },
                                                        onNavigateToSkills = {
                                                            currentView = View.SKILLS
                                                        },
                                                        onNavigateToDiscover = {
                                                            currentView = View.DISCOVER
                                                        },
                                                    )
                                                } // End BoxWithConstraints

                                                // Draggable divider
                                                if (isSidebarExpanded) {
                                                    // Need to recalculate containerWidth for the divider
                                                    BoxWithConstraints {
                                                        val containerWidth = maxWidth

                                                        Box(
                                                            modifier = Modifier
                                                                .width(8.dp)
                                                                .fillMaxHeight()
                                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                                .pointerInput(containerWidth) {
                                                                    detectDragGestures { change, dragAmount ->
                                                                        change.consume()
                                                                        // Calculate new fraction based on drag
                                                                        val dragWidthDp = (dragAmount.x / density).dp
                                                                        val newFraction = sidebarWidthFraction + (dragWidthDp / containerWidth)
                                                                        // Min 10%, max 35%
                                                                        val coercedFraction = newFraction.coerceIn(0.10f, 0.35f)
                                                                        sidebarWidthFraction = coercedFraction
                                                                        // Save preference
                                                                        ThemePreferences.setMainSidebarWidthFraction(coercedFraction)
                                                                    }
                                                                },
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            Column(
                                                                modifier = Modifier
                                                                    .width(2.dp)
                                                                    .fillMaxHeight(0.1f),
                                                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                                            ) {
                                                                repeat(3) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(2.dp)
                                                                            .background(
                                                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                                                shape = CircleShape,
                                                                            ),
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    } // End BoxWithConstraints for divider
                                                }

                                                // Main content - only show when chatViewModel exists
                                                if (chatViewModel != null) {
                                                    mainContent(
                                                        currentView = currentView,
                                                        chatViewModel = chatViewModel,
                                                        sessionsViewModel = sessionsViewModel,
                                                        projectsViewModel = projectsViewModel,
                                                        plansViewModel = plansViewModel,
                                                        appContext = appContext,
                                                        sessionManager = sessionManager,
                                                        deleteSessionCommand = deleteSessionCommand,
                                                        onResumeSession = handleResumeSession,
                                                        onNavigateToChat = {
                                                            currentView = View.CHAT
                                                        },
                                                        onNavigateToSessions = {
                                                            currentView = View.SESSIONS
                                                        },
                                                        onSelectProject = { projectId ->
                                                            selectedProjectId = projectId
                                                            currentView = View.PROJECT_DETAIL
                                                        },
                                                        onEditProject = { projectId ->
                                                            editingProjectId = projectId
                                                            showEditProjectDialog = true
                                                        },
                                                        onNewProject = {
                                                            showNewProjectDialog = true
                                                        },
                                                        onNavigateToProjects = {
                                                            currentView = View.PROJECTS
                                                        },
                                                        onNavigateToPlans = {
                                                            currentView = View.PLANS
                                                            Analytics.track(AnalyticsEvent.PLAN_VIEW_OPENED)
                                                        },
                                                        onNavigateToSkills = {
                                                            currentView = View.SKILLS
                                                        },
                                                        onNavigateToSkillsSettings = {
                                                            settingsSection = SettingsSection.SKILLS
                                                            previousView = currentView
                                                            currentView = View.SETTINGS
                                                        },
                                                        onNavigateToPlanDetail = {
                                                            currentView = View.PLAN_DETAIL
                                                        },
                                                        onNavigateToPlanEditor = {
                                                            currentView = View.PLAN_EDITOR
                                                        },
                                                        activeSessionId = activeSessionId,
                                                        sessionChatState = sessionChatStates[activeSessionId],
                                                        onChatStateChange = { inputText, attachments, editingMessage ->
                                                            activeSessionId?.let { sessionId ->
                                                                sessionChatStates[sessionId] = ChatViewState(
                                                                    inputText = inputText,
                                                                    attachments = attachments,
                                                                    editingMessage = editingMessage,
                                                                )
                                                            }
                                                        },
                                                        selectedProjectId = selectedProjectId,
                                                        userAvatarPath = userProfile?.preferences?.get("avatarPath"),
                                                        userProfile = userProfile,
                                                        discoverViewModel = discoverViewModel,
                                                        onNavigateToMcpSettings = {
                                                            settingsSection = SettingsSection.MCP_SERVERS
                                                            previousView = currentView
                                                            currentView = View.SETTINGS
                                                        },
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = stringResource("chat.no.active.session"),
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            } // End Row (sidebar + divider + content)
                                        }
                                    } // End of main content column (chat/sessions)

                                    // Event Log Panel - RIGHT position
                                    if (showEventLogPanel && eventLogDockPosition == EventLogDockPosition.RIGHT) {
                                        eventLogPanel(
                                            events = eventLogEvents,
                                            onDetach = {
                                                showEventLogPanel = false
                                                showEventLogWindow = true
                                            },
                                            onClose = {
                                                showEventLogPanel = false
                                            },
                                            onClearEvents = {
                                                eventLogEvents.clear()
                                            },
                                            onDockPositionChange = { newPosition ->
                                                eventLogDockPosition = newPosition
                                            },
                                            currentDockPosition = eventLogDockPosition,
                                            size = eventLogPanelSize,
                                            onSizeChange = { newSize -> eventLogPanelSize = newSize },
                                            modifier = Modifier.fillMaxHeight(),
                                        )
                                    }
                                } // End of if-else (Settings OR Chat/Sessions)
                            } // End of Row (Stack body)

                            // Footer - Always visible at bottom
                            footerBar(
                                onShowUpdateDetails = {
                                    updateViewModel.showUpdateDialogForExistingRelease()
                                },
                                onConfigureAiProvider = {
                                    settingsViewModel.onChangeProvider()
                                },
                                onShowAbout = {
                                    showAboutDialog = true
                                },
                            )

                            // Event Log Panel - BOTTOM position
                            if (showEventLogPanel && eventLogDockPosition == EventLogDockPosition.BOTTOM) {
                                eventLogPanel(
                                    events = eventLogEvents,
                                    onDetach = {
                                        showEventLogPanel = false
                                        showEventLogWindow = true
                                    },
                                    onClose = {
                                        showEventLogPanel = false
                                    },
                                    onClearEvents = {
                                        eventLogEvents.clear()
                                    },
                                    onDockPositionChange = { newPosition ->
                                        eventLogDockPosition = newPosition
                                    },
                                    currentDockPosition = eventLogDockPosition,
                                    size = eventLogPanelSize,
                                    onSizeChange = { newSize -> eventLogPanelSize = newSize },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            // Terminal Panel - BOTTOM position
                            if (showTerminalPanel) {
                                terminalPanel(
                                    onClose = { showTerminalPanel = false },
                                    panelHeight = terminalPanelSize,
                                    onHeightChange = { newSize -> terminalPanelSize = newSize },
                                    pendingCommand = pendingTerminalCommand,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } // End of main Column
                    } // End appBackground

                    // Quit confirmation dialog
                    if (showQuitDialog) {
                        alertDialog(
                            onDismissRequest = { showQuitDialog = false },
                            title = { Text(stringResource("menu.quit") + "?") },
                            text = { Text(stringResource("session.delete.confirm")) },
                            confirmButton = {
                                dangerButton(
                                    onClick = {
                                        showQuitDialog = false
                                        exitProcess(0)
                                    },
                                ) {
                                    Text(stringResource("action.yes"))
                                }
                            },
                            dismissButton = {
                                secondaryButton(
                                    onClick = { showQuitDialog = false },
                                ) {
                                    Text(stringResource("action.no"))
                                }
                            },
                        )
                    }

                    // Invalidate cache confirmation dialog
                    if (showInvalidateCacheDialog) {
                        alertDialog(
                            onDismissRequest = { showInvalidateCacheDialog = false },
                            title = { Text(stringResource("menu.invalidate.caches.title")) },
                            text = { Text(stringResource("menu.invalidate.caches.message")) },
                            confirmButton = {
                                dangerButton(
                                    onClick = {
                                        showInvalidateCacheDialog = false
                                        // Post the invalidate cache event
                                        EventBus.post(InvalidateCacheEvent())
                                        // Show success dialog
                                        showCacheDeletedDialog = true
                                    },
                                ) {
                                    Text(stringResource("action.yes"))
                                }
                            },
                            dismissButton = {
                                secondaryButton(
                                    onClick = { showInvalidateCacheDialog = false },
                                ) {
                                    Text(stringResource("action.cancel"))
                                }
                            },
                        )
                    }

                    // Cache deleted success dialog
                    if (showCacheDeletedDialog) {
                        alertDialog(
                            onDismissRequest = { showCacheDeletedDialog = false },
                            title = { Text(stringResource("menu.invalidate.caches.success.title")) },
                            text = { Text(stringResource("menu.invalidate.caches.success.message")) },
                            confirmButton = {
                                primaryButton(
                                    onClick = { showCacheDeletedDialog = false },
                                ) {
                                    Text(stringResource("action.ok"))
                                }
                            },
                        )
                    }

                    // Clear preferences confirmation dialog
                    if (showClearPreferencesDialog) {
                        alertDialog(
                            onDismissRequest = { showClearPreferencesDialog = false },
                            title = { Text(stringResource("menu.clear.preferences.title")) },
                            text = { Text(stringResource("menu.clear.preferences.message")) },
                            confirmButton = {
                                dangerButton(
                                    onClick = {
                                        showClearPreferencesDialog = false
                                        ApplicationPreferences.clearAll()
                                        showPreferencesClearedDialog = true
                                    },
                                ) {
                                    Text(stringResource("action.yes"))
                                }
                            },
                            dismissButton = {
                                secondaryButton(
                                    onClick = { showClearPreferencesDialog = false },
                                ) {
                                    Text(stringResource("action.cancel"))
                                }
                            },
                        )
                    }

                    // Preferences cleared success dialog
                    if (showPreferencesClearedDialog) {
                        alertDialog(
                            onDismissRequest = { showPreferencesClearedDialog = false },
                            title = { Text(stringResource("menu.clear.preferences.success.title")) },
                            text = { Text(stringResource("menu.clear.preferences.success.message")) },
                            confirmButton = {
                                primaryButton(
                                    onClick = { showPreferencesClearedDialog = false },
                                ) {
                                    Text(stringResource("action.ok"))
                                }
                            },
                        )
                    }

                    // Import backup confirmation dialog
                    if (showImportBackupConfirm) {
                        alertDialog(
                            onDismissRequest = {
                                showImportBackupConfirm = false
                                pendingImportBackupPath = null
                            },
                            title = { Text(stringResource("backup.import.confirm.title")) },
                            text = { Text(stringResource("backup.import.confirm.message")) },
                            confirmButton = {
                                primaryButton(
                                    onClick = {
                                        showImportBackupConfirm = false
                                        pendingImportBackupPath?.let { path ->
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        BackupManager.importBackup(path)
                                                    }

                                                    errorDialogState = ErrorDialogState(
                                                        show = true,
                                                        title = LocalizationManager.getString("backup.import.success.title"),
                                                        message = LocalizationManager.getString("backup.import.success.message"),
                                                    )
                                                } catch (e: Exception) {
                                                    errorDialogState = ErrorDialogState(
                                                        show = true,
                                                        title = LocalizationManager.getString("backup.import.error.title"),
                                                        message = LocalizationManager.getString("backup.import.error.message", e.message ?: "Unknown error"),
                                                    )
                                                    log.error("Error importing backup.", e)
                                                } finally {
                                                    pendingImportBackupPath = null
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource("action.yes"))
                                }
                            },
                            dismissButton = {
                                secondaryButton(
                                    onClick = {
                                        showImportBackupConfirm = false
                                        pendingImportBackupPath = null
                                    },
                                ) {
                                    Text(stringResource("action.cancel"))
                                }
                            },
                        )
                    }

                    // Provider setup required dialog
                    if (showProviderSetupDialog) {
                        alertDialog(
                            onDismissRequest = { },
                            title = {
                                Text(
                                    text = stringResource("provider.setup.required.title"),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            },
                            text = {
                                Text(
                                    text = stringResource("provider.setup.required.message"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            confirmButton = {
                                primaryButton(
                                    onClick = {
                                        showProviderSetupDialog = false
                                        settingsViewModel.onChangeProvider(isInitialSetup = true)
                                    },
                                ) {
                                    Text(stringResource("provider.setup.required.button"))
                                }
                            },
                        )
                    }

                    if (settingsViewModel.showProviderDialog) {
                        providerSelectionDialog(
                            viewModel = settingsViewModel,
                            onDismiss = { settingsViewModel.closeProviderDialog() },
                            onSave = { settingsViewModel.saveProvider() },
                        )
                    }

                    // About Dialog
                    if (showAboutDialog) {
                        aboutDialog(onDismiss = { showAboutDialog = false })
                    }

                    // System Diagnostics Dialog
                    if (showSystemDiagnosticsDialog) {
                        val metrics by appContext.telemetry.metricsFlow.collectAsState()
                        systemResourcesDialog(
                            onDismiss = { showSystemDiagnosticsDialog = false },
                            onExportTelemetry = {
                                scope.launch {
                                    val timestamp = withContext(Dispatchers.IO) {
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                                    }
                                    val targetFile = FileDialogUtils.pickSavePath(
                                        suggestedName = "telemetry_$timestamp",
                                        extension = "zip",
                                        title = LocalizationManager.getString("telemetry.export.dialog.title"),
                                    ) ?: return@launch

                                    val result = TelemetryExportService.export(metrics, targetFile)
                                    if (result.isFailure) {
                                        errorDialogState = ErrorDialogState(
                                            show = true,
                                            title = LocalizationManager.getString("telemetry.export.error.title"),
                                            message = LocalizationManager.getString(
                                                "telemetry.export.error.message",
                                                result.exceptionOrNull()?.message ?: "Unknown error",
                                            ),
                                        )
                                    }
                                }
                            },
                            telemetryContent = { telemetryPanel(metrics = metrics, maxHeight = 480.dp) },
                        )
                    }

                    // User Profile Dialog
                    if (showUserProfileDialog) {
                        userProfile?.let { currentProfile ->
                            userProfileDialog(
                                profile = currentProfile,
                                onDismiss = { showUserProfileDialog = false },
                                onSave = { updatedProfile ->
                                    scope.launch {
                                        val profileRepo = DatabaseManager.getInstance().getUserProfileRepository()
                                        withContext(Dispatchers.IO) {
                                            profileRepo.saveProfile(updatedProfile)
                                        }
                                        userProfile = updatedProfile

                                        // Update AppContext with new personalization directive
                                        val personalizationContext = withContext(Dispatchers.IO) {
                                            profileRepo.getPersonalizationContext()
                                        }
                                        AppContext.getInstance().setUserProfileDirective(personalizationContext)

                                        showUserProfileDialog = false
                                    }
                                },
                            )
                        }
                    }

                    // Onboarding Wizard Dialog
                    if (showOnboardingWizard) {
                        onboardingWizardDialog(
                            initialName = userProfile?.name ?: "",
                            initialOccupation = userProfile?.occupation ?: "",
                            onPersonaSelected = { plans, skills, projects ->
                                showPlansInSidebar = plans
                                showSkillsInSidebar = skills
                                showProjectsInSidebar = projects
                                NativeMenuBar.updatePlansMenuLabel(plans)
                                NativeMenuBar.updateSkillsMenuLabel(skills)
                                NativeMenuBar.updateProjectsMenuLabel(projects)
                            },
                            onComplete = { locale, analyticsAccepted, name, occupation ->
                                scope.launch {
                                    ThemePreferences.setLocale(locale)
                                    if (analyticsAccepted) Analytics.optIn() else Analytics.optOut()
                                    if (name.isNotBlank() || occupation.isNotBlank()) {
                                        val profileRepo = DatabaseManager.getInstance().getUserProfileRepository()
                                        val updated = (
                                            userProfile ?: UserProfile(
                                                id = UUID.randomUUID().toString(),
                                            )
                                            ).copy(
                                            name = name.takeIf { it.isNotBlank() },
                                            occupation = occupation.takeIf { it.isNotBlank() },
                                        )
                                        withContext(Dispatchers.IO) { profileRepo.saveProfile(updated) }
                                        userProfile = updated
                                        val ctx = withContext(Dispatchers.IO) { profileRepo.getPersonalizationContext() }
                                        AppContext.getInstance().setUserProfileDirective(ctx)
                                    }
                                    ApplicationPreferences.markOnboardingCompleted()
                                    showOnboardingWizard = false
                                }
                            },
                        )
                    }

                    if (updateViewModel.showUpdateDialog || updateViewModel.errorMessage != null) {
                        updateCheckDialog(
                            viewModel = updateViewModel,
                            onDismiss = {
                                updateViewModel.dismissUpdateDialog()
                            },
                        )
                    }

                    if (sessionsViewModel.showExportDialog) {
                        exportSessionDialog(
                            sessionTitle = sessionsViewModel.exportSessionTitle,
                            selectedFormat = sessionsViewModel.exportFormat,
                            defaultFilename = sessionsViewModel.exportDefaultFilename,
                            onFormatChange = { format ->
                                sessionsViewModel.updateExportFormat(format)
                            },
                            onDismiss = { sessionsViewModel.dismissExportDialog() },
                            onExport = { fullPath ->
                                sessionsViewModel.executeExport(fullPath)
                            },
                        )
                    }

                    sessionsViewModel.successMessage?.let { message ->
                        Dialog(onDismissRequest = { sessionsViewModel.dismissSuccessMessage() }) {
                            Surface(
                                modifier = Modifier.width(450.dp),
                                shape = MaterialTheme.shapes.large,
                                tonalElevation = 8.dp,
                            ) {
                                Column(
                                    modifier = Modifier.padding(Spacing.extraLarge),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.large),
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        successIcon(size = 32.dp)
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(
                                                text = stringResource("session.export.success.title"),
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = message,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    primaryButton(
                                        onClick = { sessionsViewModel.dismissSuccessMessage() },
                                        modifier = Modifier.align(Alignment.End),
                                    ) {
                                        Text(stringResource("action.ok"))
                                    }
                                }
                            }
                        }
                    }

                    // File Overwrite Confirmation Dialog
                    if (sessionsViewModel.showOverwriteConfirmation) {
                        Dialog(onDismissRequest = { sessionsViewModel.cancelOverwrite() }) {
                            Surface(
                                modifier = Modifier.width(450.dp),
                                shape = MaterialTheme.shapes.large,
                                tonalElevation = 8.dp,
                            ) {
                                Column(
                                    modifier = Modifier.padding(Spacing.extraLarge),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.large),
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(32.dp),
                                        )
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(
                                                text = stringResource("file.overwrite.title"),
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = stringResource("file.overwrite.message"),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            sessionsViewModel.pendingExportPath?.let { path ->
                                                Text(
                                                    text = path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = Spacing.extraSmall),
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        secondaryButton(
                                            onClick = { sessionsViewModel.cancelOverwrite() },
                                        ) {
                                            Text(stringResource("action.cancel"))
                                        }

                                        dangerButton(
                                            onClick = { sessionsViewModel.confirmOverwrite() },
                                        ) {
                                            Text(stringResource("file.overwrite.confirm"))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Rename Session Dialog
                    if (sessionsViewModel.showRenameDialog) {
                        renameSessionDialog(
                            currentTitle = sessionsViewModel.renameCurrentTitle,
                            onDismiss = { sessionsViewModel.dismissRenameDialog() },
                            onRename = { newTitle ->
                                sessionsViewModel.executeRename(newTitle)
                                // Refresh the session title in ChatViewModel if currently viewing this session
                                chatViewModel?.refreshSessionTitle()
                            },
                        )
                    }

                    // Session Memory Dialog (Developer Mode)
                    if (showSessionMemoryDialog) {
                        sessionMemoryDialog(
                            sessionId = sessionMemorySessionId,
                            onLoadMemory = { sid ->
                                withContext(Dispatchers.IO) {
                                    DatabaseManager.getInstance().getSessionMemoryRepository().getBySessionId(sid)
                                }
                            },
                            onDismiss = {
                                showSessionMemoryDialog = false
                                sessionMemorySessionId = null
                            },
                        )
                    }

                    // Star Prompt Dialog (One-time after usage)
                    if (showStarPromptDialog) {
                        starPromptDialog(
                            onDismiss = {
                                Analytics.track(AnalyticsEvent.STAR_PROMPT_DISMISSED)
                                AccountPreferences.device().snoozeStarPrompt()
                                showStarPromptDialog = false
                            },
                            onStar = {
                                Analytics.track(AnalyticsEvent.STAR_PROMPT_ACCEPTED)
                                AccountPreferences.device().dismissStarPromptPermanently()
                                showStarPromptDialog = false
                                runCatching {
                                    if (Desktop.isDesktopSupported()) {
                                        Desktop.getDesktop().browse(
                                            URI("https://github.com/askimo-ai/askimo"),
                                        )
                                    }
                                }.onFailure { log.error("Can not open the browser", it) }
                            },
                            onAlreadyStarred = {
                                Analytics.track(AnalyticsEvent.STAR_PROMPT_ACCEPTED)
                                AccountPreferences.device().dismissStarPromptPermanently()
                                showStarPromptDialog = false
                            },
                        )
                    }

                    // Happiness Gate Dialog — shown first; only happy users see the star prompt
                    if (showHappinessGateDialog) {
                        happinessGateDialog(
                            onHappy = {
                                showHappinessGateDialog = false
                                showStarPromptDialog = true
                                Analytics.track(AnalyticsEvent.STAR_PROMPT_SHOWN)
                            },
                            onNeutral = {
                                showHappinessGateDialog = false
                                feedbackSentiment = "neutral"
                                showFeedbackPromptDialog = true
                            },
                            onUnhappy = {
                                showHappinessGateDialog = false
                                feedbackSentiment = "unhappy"
                                showFeedbackPromptDialog = true
                            },
                        )
                    }

                    // Feedback prompt — shown after neutral/unhappy; user opts in to open contact form
                    if (showFeedbackPromptDialog) {
                        feedbackPromptDialog(
                            onConfirm = {
                                AccountPreferences.device().dismissStarPromptPermanently()
                                showFeedbackPromptDialog = false
                                runCatching {
                                    if (Desktop.isDesktopSupported()) {
                                        Desktop.getDesktop().browse(
                                            URI("https://$DOMAIN/feedback/?sentiment=$feedbackSentiment"),
                                        )
                                    }
                                }.onFailure { log.error("Cannot open browser for feedback", it) }
                            },
                            onDecline = {
                                AccountPreferences.device().dismissStarPromptPermanently()
                                showFeedbackPromptDialog = false
                            },
                        )
                    }

                    // VRR / G-Sync hint dialog (Windows, one-time, high-refresh displays)
                    if (showVrrHintDialog) {
                        alertDialog(
                            onDismissRequest = {
                                AccountPreferences.device().dismissVrrHint()
                                showVrrHintDialog = false
                            },
                            title = { Text(stringResource("settings.hardware.acceleration.vrr.hint.title")) },
                            text = { Text(stringResource("settings.hardware.acceleration.vrr.hint.message")) },
                            confirmButton = {
                                primaryButton(
                                    onClick = {
                                        AccountPreferences.device().setHardwareAccelerationEnabled(false)
                                        AccountPreferences.device().dismissVrrHint()
                                        showVrrHintDialog = false
                                        errorDialogState = ErrorDialogState(
                                            show = true,
                                            title = LocalizationManager.getString("settings.hardware.acceleration.restart.title"),
                                            message = LocalizationManager.getString("settings.hardware.acceleration.restart.message"),
                                        )
                                    },
                                ) {
                                    Text(stringResource("settings.hardware.acceleration.vrr.hint.disable"))
                                }
                            },
                            dismissButton = {
                                secondaryButton(
                                    onClick = {
                                        AccountPreferences.device().dismissVrrHint()
                                        showVrrHintDialog = false
                                    },
                                ) {
                                    Text(stringResource("settings.hardware.acceleration.vrr.hint.keep"))
                                }
                            },
                        )
                    }

                    if (errorDialogState.show) {
                        errorDialog(
                            title = errorDialogState.title,
                            message = errorDialogState.message,
                            linkText = errorDialogState.linkText,
                            linkUrl = errorDialogState.linkUrl,
                            details = errorDialogState.details,
                            onDismiss = {
                                errorDialogState = ErrorDialogState()
                            },
                        )
                    }

                    // File Viewer Dialog (fallback when system cannot open files)
                    if (showFileViewerDialog) {
                        fileViewerDialog(
                            title = fileViewerTitle,
                            filePath = fileViewerPath,
                            content = fileViewerContent,
                            onDismiss = {
                                showFileViewerDialog = false
                            },
                        )
                    }

                    // New Project Dialog
                    if (showNewProjectDialog) {
                        newProjectDialog(
                            onDismiss = { showNewProjectDialog = false },
                            onCreateProject = { _, _ ->
                                projectsViewModel.refresh()
                            },
                            onNavigateToProject = { projectId ->
                                showNewProjectDialog = false
                                projectsViewModel.refresh()
                                selectedProjectId = projectId
                                currentView = View.PROJECT_DETAIL
                            },
                        )
                    }

                    // Edit Project Dialog
                    if (showEditProjectDialog && editingProjectId != null) {
                        editProjectDialog(
                            projectId = editingProjectId!!,
                            onDismiss = {
                                showEditProjectDialog = false
                                editingProjectId = null
                            },
                            onSave = { projectId, name, description, knowledgeSources ->
                                projectsViewModel.updateProject(projectId, name, description, knowledgeSources)
                                showEditProjectDialog = false
                                editingProjectId = null
                            },
                        )
                    }

                    if (showGlobalSearchDialog) {
                        globalSearchDialog(
                            onDismiss = { showGlobalSearchDialog = false },
                            onNavigateToMessage = { sessionId, messageId ->
                                log.debug("Navigate to message: sessionId='$sessionId', messageId='$messageId'")

                                showGlobalSearchDialog = false

                                if (currentView != View.CHAT) {
                                    log.debug("Switching from {} to CHAT view", currentView)
                                    currentView = View.CHAT
                                }

                                sessionManager.switchToSession(sessionId)

                                scope.launch {
                                    val chatViewModel = sessionManager.getOrCreateChatViewModel(sessionId)

                                    snapshotFlow { chatViewModel.messages }
                                        .first { messages ->
                                            messages.isNotEmpty() && messages.any { it.id == messageId }
                                        }

                                    val message = chatViewModel.messages.find { it.id == messageId }

                                    if (message != null && message.timestamp != null) {
                                        log.debug(
                                            "Messages loaded, jumping to message with timestamp: {}",
                                            message.timestamp,
                                        )
                                        chatViewModel.jumpToMessage(messageId, message.timestamp!!)
                                    } else {
                                        log.warn("Message not found or has no timestamp after loading: messageId='$messageId'")
                                    }
                                }
                            },
                        )
                    }

                    // Event Log Window (Developer Mode - Detached)
                    if (showEventLogWindow) {
                        eventLogWindow(
                            events = eventLogEvents,
                            onCloseRequest = { showEventLogWindow = false },
                            onReattach = {
                                showEventLogWindow = false
                                showEventLogPanel = true
                            },
                        )
                    }
                } // MaterialTheme
            } // CompositionLocalProvider
        } // ProvideLocalization
    }
}

@Composable
fun mainContent(
    currentView: View,
    chatViewModel: ChatViewModel,
    sessionsViewModel: SessionsViewModel,
    projectsViewModel: ProjectsViewModel,
    plansViewModel: PlansViewModel,
    appContext: AppContext,
    sessionManager: SessionManager,
    deleteSessionCommand: DeleteSessionFromProjectCommand,
    onResumeSession: (String) -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onSelectProject: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onNewProject: () -> Unit = {},
    onNavigateToProjects: () -> Unit = {},
    onNavigateToPlans: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToSkillsSettings: () -> Unit = {},
    onNavigateToPlanDetail: () -> Unit = {},
    onNavigateToPlanEditor: () -> Unit = {},
    onNavigateToMcpSettings: () -> Unit = {},
    activeSessionId: String?,
    sessionChatState: ChatViewState?,
    onChatStateChange: (TextFieldValue, List<FileAttachmentDTO>, ChatMessageDTO?) -> Unit,
    selectedProjectId: String?,
    userAvatarPath: String? = null,
    userProfile: UserProfile? = null,
    discoverViewModel: DiscoverViewModel,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (LocalBackgroundActive.current) {
                    Transparent
                } else {
                    MaterialTheme.colorScheme.background
                },
            ),
    ) {
        when (currentView) {
            View.DISCOVER -> discoverView(
                userProfile = userProfile,
                recentSessions = sessionsViewModel.recentSessions.take(10),
                viewModel = discoverViewModel,
                onNewChat = onNavigateToChat,
                onResumeSession = onResumeSession,
                onNavigateToSessions = onNavigateToSessions,
                onNavigateToProjects = onNavigateToProjects,
                onNavigateToPlans = onNavigateToPlans,
                onNavigateToSkills = onNavigateToSkills,
                onNavigateToMcpSettings = onNavigateToMcpSettings,
                modifier = Modifier.fillMaxSize(),
            )

            View.CHAT, View.NEW_CHAT -> {
                val configInfo = appContext.getConfigInfo()
                chatView(
                    state = chatViewModel.state,
                    actions = chatViewModel,
                    provider = configInfo.provider.name,
                    model = configInfo.model,
                    onJumpToMessage = { messageId, timestamp ->
                        chatViewModel.jumpToMessage(messageId, timestamp)
                    },
                    initialInputText = sessionChatState?.inputText ?: TextFieldValue(""),
                    initialAttachments = sessionChatState?.attachments ?: emptyList(),
                    initialEditingMessage = sessionChatState?.editingMessage,
                    onStateChange = onChatStateChange,
                    sessionId = activeSessionId,
                    onRenameSession = { sessionId ->
                        sessionsViewModel.showRenameDialog(sessionId)
                    },
                    onExportSession = { sessionId ->
                        sessionsViewModel.exportSession(sessionId)
                    },
                    onDeleteSession = { sessionId ->
                        sessionsViewModel.deleteSessionWithCleanup(sessionId)
                    },
                    onStarSession = { sessionId, isStarred ->
                        sessionsViewModel.updateSessionStarred(sessionId, isStarred)
                    },
                    onNavigateToProject = onSelectProject,
                    onNavigateToMcpSettings = onNavigateToMcpSettings,
                    onMoveSessionToNewProject = { _ -> onNewProject() },
                    userAvatarPath = userAvatarPath,
                    projectSidePanelSlot = { proj, ragStatus, ragPct, indexedPaths, expanded, onExpandedChange, onAddToChat ->
                        communityProjectSidePanel(
                            project = proj,
                            ragIndexingStatus = ragStatus,
                            ragIndexingPercentage = ragPct,
                            indexedPaths = indexedPaths,
                            isExpanded = expanded,
                            onExpandedChange = onExpandedChange,
                            onAddToChat = onAddToChat,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    },
                )
            }

            View.SESSIONS -> sessionsView(
                viewModel = sessionsViewModel,
                onResumeSession = onResumeSession,
                onNewChat = onNavigateToChat,
                modifier = Modifier.fillMaxSize(),
            )

            View.PROJECTS -> projectsView(
                viewModel = projectsViewModel,
                onSelectProject = onSelectProject,
                onEditProject = onEditProject,
                onNewProject = onNewProject,
                modifier = Modifier.fillMaxSize(),
            )

            View.PROJECT_DETAIL -> {
                if (selectedProjectId != null) {
                    val project = projectsViewModel.projects.find { it.id == selectedProjectId }
                    if (project != null) {
                        projectView(
                            project = project,
                            onBack = onNavigateToProjects,
                            onStartChat = { projId, mode, message, attachments, enabledServerIds ->
                                sessionManager.createProjectSessionAndSendMessage(
                                    projectId = projId,
                                    mode = mode,
                                    message = message,
                                    attachments = attachments,
                                    enabledServerIds = enabledServerIds,
                                    onComplete = { onNavigateToChat() },
                                )
                            },
                            onResumeSession = onResumeSession,
                            onDeleteSession = deleteSessionCommand::execute,
                            onRenameSession = { sessionId, _ ->
                                sessionsViewModel.showRenameDialog(sessionId)
                            },
                            onExportSession = { sessionId ->
                                sessionsViewModel.exportSession(sessionId)
                            },
                            onEditProject = onEditProject,
                            onDeleteProject = { projectId ->
                                projectsViewModel.deleteProject(projectId)
                                onNavigateToSessions()
                            },
                            onNavigateToMcpSettings = onNavigateToMcpSettings,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource("project.not.found"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource("project.not.selected"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            View.SETTINGS -> {
            }

            View.PLANS -> plansGalleryView(
                viewModel = plansViewModel,
                onSelectPlan = { plan ->
                    plansViewModel.selectPlan(plan)
                    onNavigateToPlanDetail()
                },
                onNewPlan = {
                    plansViewModel.startNewPlan()
                    onNavigateToPlanEditor()
                },
                onEditPlan = { planId ->
                    plansViewModel.startEditPlan(planId)
                    onNavigateToPlanEditor()
                },
                onDuplicatePlan = { planId ->
                    plansViewModel.startDuplicatePlan(planId)
                    onNavigateToPlanEditor()
                },
                modifier = Modifier.fillMaxSize(),
            )

            View.PLAN_DETAIL -> planDetailView(
                viewModel = plansViewModel,
                onBack = onNavigateToPlans,
                onEditPlan = {
                    plansViewModel.selectedPlan?.id?.let { planId ->
                        plansViewModel.startEditPlan(planId)
                        onNavigateToPlanEditor()
                    }
                },
                onDuplicatePlan = {
                    plansViewModel.selectedPlan?.id?.let { planId ->
                        plansViewModel.startDuplicatePlan(planId)
                        onNavigateToPlanEditor()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            View.PLAN_EDITOR -> planEditorView(
                viewModel = plansViewModel,
                onBack = onNavigateToPlans,
                modifier = Modifier.fillMaxSize(),
            )

            View.SKILLS -> skillsView(
                onNavigateToSkillsSettings = onNavigateToSkillsSettings,
            )
        }
    }
}
