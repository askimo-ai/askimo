/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.ui.window.FrameWindowScope
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.config.AppConfig
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.util.AskimoHome
import io.askimo.ui.common.theme.ThemeMode
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.util.Platform
import java.awt.Desktop
import java.awt.Frame
import java.awt.Menu
import java.awt.MenuBar
import java.awt.MenuItem
import java.awt.MenuShortcut
import java.awt.Window
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.net.URI

/**
 * Native menu bar handler that provides OS-specific menu implementations.
 * - macOS: Uses system menu bar at the top of screen
 * - Windows/Linux: Uses AWT MenuBar with native look and feel
 */
object NativeMenuBar {
    private var updateSidebarMenuItem: ((Boolean) -> Unit)? = null

    fun updateSidebarMenuLabel(isSidebarExpanded: Boolean) {
        updateSidebarMenuItem?.invoke(isSidebarExpanded)
    }

    private var updatePlansMenuItem: ((Boolean) -> Unit)? = null
    private var updateSkillsMenuItem: ((Boolean) -> Unit)? = null
    private var updateProjectsMenuItem: ((Boolean) -> Unit)? = null

    fun updatePlansMenuLabel(isVisible: Boolean) {
        updatePlansMenuItem?.invoke(isVisible)
    }

    fun updateSkillsMenuLabel(isVisible: Boolean) {
        updateSkillsMenuItem?.invoke(isVisible)
    }

    fun updateProjectsMenuLabel(isVisible: Boolean) {
        updateProjectsMenuItem?.invoke(isVisible)
    }

    fun setup(
        frameWindowScope: FrameWindowScope,
        onShowAbout: () -> Unit,
        onNewChat: () -> Unit,
        onNewProject: () -> Unit,
        onSearchInSessions: () -> Unit,
        onShowSettings: () -> Unit,
        onShowEventLog: () -> Unit,
        onCheckForUpdates: () -> Unit,
        onEnterFullScreen: () -> Unit,
        onNavigateToSessions: () -> Unit,
        onNavigateToProjects: () -> Unit,
        onNavigateToDiscover: () -> Unit,
        onToggleSidebar: () -> Unit,
        onInvalidateCaches: () -> Unit,
        onExportBackup: () -> Unit,
        onImportBackup: () -> Unit,
        onShowGettingStarted: () -> Unit,
        onOpenTerminal: () -> Unit,
        onClearPreferences: () -> Unit = {},
        onClearAccountPreferences: () -> Unit = {},
        onTogglePlans: (() -> Unit)? = null,
        onToggleSkills: (() -> Unit)? = null,
        onToggleProjects: (() -> Unit)? = null,
        isPlansVisible: Boolean = true,
        isSkillsVisible: Boolean = true,
        isProjectsVisible: Boolean = true,
        onShowSystemDiagnostics: () -> Unit = {},
    ) {
        val window = frameWindowScope.window

        // Setup AWT menu bar for all platforms (includes Documentation)
        setupAWTMenuBar(window, onShowAbout, onNewChat, onNewProject, onSearchInSessions, onShowSettings, onShowEventLog, onCheckForUpdates, onEnterFullScreen, onNavigateToSessions, onNavigateToProjects, onNavigateToDiscover, onToggleSidebar, onInvalidateCaches, onExportBackup, onImportBackup, onShowGettingStarted, onOpenTerminal, onClearPreferences, onClearAccountPreferences, onTogglePlans, onToggleSkills, onToggleProjects, isPlansVisible, isSkillsVisible, isProjectsVisible, onShowSystemDiagnostics)

        // On macOS, also register the About handler for the app menu
        if (Platform.isMac) {
            setupMacAboutHandler(onShowAbout)
        }
    }

    private fun setupMacAboutHandler(onShowAbout: () -> Unit) {
        try {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()

                // Set About handler (appears in app menu on macOS)
                if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                    desktop.setAboutHandler { onShowAbout() }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun setupAWTMenuBar(
        window: Window,
        onShowAbout: () -> Unit,
        onNewChat: () -> Unit,
        onNewProject: () -> Unit,
        onSearchInSessions: () -> Unit,
        onShowSettings: () -> Unit,
        onShowEventLog: () -> Unit,
        onCheckForUpdates: () -> Unit,
        onEnterFullScreen: () -> Unit,
        onNavigateToSessions: () -> Unit,
        onNavigateToProjects: () -> Unit,
        onNavigateToDiscover: () -> Unit,
        onToggleSidebar: () -> Unit,
        onInvalidateCaches: () -> Unit,
        onExportBackup: () -> Unit,
        onImportBackup: () -> Unit,
        onShowGettingStarted: () -> Unit,
        onOpenTerminal: () -> Unit,
        onClearPreferences: () -> Unit,
        onClearAccountPreferences: () -> Unit,
        onTogglePlans: (() -> Unit)?,
        onToggleSkills: (() -> Unit)?,
        onToggleProjects: (() -> Unit)?,
        isPlansVisible: Boolean,
        isSkillsVisible: Boolean,
        isProjectsVisible: Boolean,
        onShowSystemDiagnostics: () -> Unit,
    ) {
        if (window is Frame) {
            val menuBar = MenuBar()

            // File Menu
            val fileMenu = Menu(LocalizationManager.getString("menu.file"))

            val newChatItem = MenuItem(
                LocalizationManager.getString("chat.new"),
                MenuShortcut(KeyEvent.VK_N),
            )
            newChatItem.addActionListener(
                ActionListener {
                    onNewChat()
                },
            )
            fileMenu.add(newChatItem)

            val newProjectItem = MenuItem(
                LocalizationManager.getString("menu.new.project"),
                MenuShortcut(KeyEvent.VK_N, true), // Shift+Ctrl+N (or Shift+Cmd+N on Mac)
            )
            newProjectItem.addActionListener(
                ActionListener {
                    onNewProject()
                },
            )
            fileMenu.add(newProjectItem)

            fileMenu.addSeparator()

            val searchSessionsItem = MenuItem(
                LocalizationManager.getString("menu.search.sessions"),
                MenuShortcut(KeyEvent.VK_F, true), // Shift+Ctrl+F (or Shift+Cmd+F on Mac)
            )
            searchSessionsItem.addActionListener(
                ActionListener {
                    onSearchInSessions()
                },
            )
            fileMenu.add(searchSessionsItem)

            fileMenu.addSeparator()

            val exportBackupItem = MenuItem(
                LocalizationManager.getString("menu.export.backup"),
                MenuShortcut(KeyEvent.VK_E, true), // Shift+Ctrl+E (or Shift+Cmd+E on Mac)
            )
            exportBackupItem.addActionListener(
                ActionListener {
                    onExportBackup()
                },
            )
            fileMenu.add(exportBackupItem)

            val importBackupItem = MenuItem(
                LocalizationManager.getString("menu.import.backup"),
                MenuShortcut(KeyEvent.VK_I, true), // Shift+Ctrl+I (or Shift+Cmd+I on Mac)
            )
            importBackupItem.addActionListener(
                ActionListener {
                    onImportBackup()
                },
            )
            fileMenu.add(importBackupItem)

            fileMenu.addSeparator()

            val invalidateCachesItem = MenuItem(
                LocalizationManager.getString("menu.invalidate.caches"),
            )
            invalidateCachesItem.addActionListener(
                ActionListener {
                    onInvalidateCaches()
                },
            )
            fileMenu.add(invalidateCachesItem)

            val clearPreferencesItem = MenuItem(
                LocalizationManager.getString("menu.clear.preferences"),
            )
            clearPreferencesItem.addActionListener(
                ActionListener {
                    onClearPreferences()
                },
            )
            fileMenu.add(clearPreferencesItem)

            fileMenu.addSeparator()

            val settingsItem = MenuItem(
                LocalizationManager.getString("settings.title"),
                MenuShortcut(KeyEvent.VK_COMMA),
            )
            settingsItem.addActionListener(
                ActionListener {
                    onShowSettings()
                },
            )
            fileMenu.add(settingsItem)

            menuBar.add(fileMenu)

            val viewMenu = Menu(LocalizationManager.getString("menu.view"))

            val appearanceMenu = Menu(LocalizationManager.getString("menu.view.appearance"))

            val systemThemeItem = MenuItem("")
            val lightThemeItem = MenuItem("")
            val darkThemeItem = MenuItem("")
            val sepiaThemeItem = MenuItem("")
            val oceanThemeItem = MenuItem("")
            val nordThemeItem = MenuItem("")
            val sageThemeItem = MenuItem("")
            val roseThemeItem = MenuItem("")
            val indigoThemeItem = MenuItem("")

            // Helper function to update all theme menu items
            fun updateThemeMenuItems() {
                val currentTheme = ThemePreferences.themeMode.value
                systemThemeItem.label = (if (currentTheme == ThemeMode.SYSTEM) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.system")
                lightThemeItem.label = (if (currentTheme == ThemeMode.LIGHT) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.light")
                darkThemeItem.label = (if (currentTheme == ThemeMode.DARK) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.dark")
                sepiaThemeItem.label = (if (currentTheme == ThemeMode.SEPIA) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.sepia")
                oceanThemeItem.label = (if (currentTheme == ThemeMode.OCEAN) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.ocean")
                nordThemeItem.label = (if (currentTheme == ThemeMode.NORD) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.nord")
                sageThemeItem.label = (if (currentTheme == ThemeMode.SAGE) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.sage")
                roseThemeItem.label = (if (currentTheme == ThemeMode.ROSE) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.rose")
                indigoThemeItem.label = (if (currentTheme == ThemeMode.INDIGO) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.appearance.indigo")
            }

            // Initialize labels
            updateThemeMenuItems()

            systemThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.SYSTEM)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(systemThemeItem)

            lightThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.LIGHT)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(lightThemeItem)

            darkThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.DARK)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(darkThemeItem)

            sepiaThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.SEPIA)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(sepiaThemeItem)

            oceanThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.OCEAN)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(oceanThemeItem)

            nordThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.NORD)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(nordThemeItem)

            sageThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.SAGE)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(sageThemeItem)

            roseThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.ROSE)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(roseThemeItem)

            indigoThemeItem.addActionListener(
                ActionListener {
                    ThemePreferences.setThemeMode(ThemeMode.INDIGO)
                    updateThemeMenuItems()
                },
            )
            appearanceMenu.add(indigoThemeItem)

            viewMenu.add(appearanceMenu)

            // Separator after Appearance group
            viewMenu.addSeparator()

            // Discover — standalone menu item
            val discoverItemGo = MenuItem(
                LocalizationManager.getString("menu.view.discover"),
                MenuShortcut(KeyEvent.VK_D),
            )
            discoverItemGo.addActionListener(ActionListener { onNavigateToDiscover() })
            viewMenu.add(discoverItemGo)

            // Plans toggle
            val plansToggleItem = MenuItem("")
            val updatePlansMenuItemFunc: (Boolean) -> Unit = { visible ->
                plansToggleItem.label = (if (visible) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.plans")
            }
            updatePlansMenuItem = updatePlansMenuItemFunc
            updatePlansMenuItemFunc(isPlansVisible)
            plansToggleItem.addActionListener(ActionListener { onTogglePlans?.invoke() })
            viewMenu.add(plansToggleItem)

            // Skills toggle
            val skillsToggleItem = MenuItem("")
            val updateSkillsMenuItemFunc: (Boolean) -> Unit = { visible ->
                skillsToggleItem.label = (if (visible) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.skills")
            }
            updateSkillsMenuItem = updateSkillsMenuItemFunc
            updateSkillsMenuItemFunc(isSkillsVisible)
            skillsToggleItem.addActionListener(ActionListener { onToggleSkills?.invoke() })
            viewMenu.add(skillsToggleItem)

            // Projects toggle
            val projectsToggleItem = MenuItem("")
            val updateProjectsMenuItemFunc: (Boolean) -> Unit = { visible ->
                projectsToggleItem.label = (if (visible) "✓ " else "  ") +
                    LocalizationManager.getString("menu.view.projects")
            }
            updateProjectsMenuItem = updateProjectsMenuItemFunc
            updateProjectsMenuItemFunc(isProjectsVisible)
            projectsToggleItem.addActionListener(ActionListener { onToggleProjects?.invoke() })
            viewMenu.add(projectsToggleItem)

            viewMenu.addSeparator()

            val toggleSidebarItem = MenuItem("")

            val updateSidebarMenuItemFunc: (Boolean) -> Unit = { isSidebarExpanded ->
                toggleSidebarItem.label = if (isSidebarExpanded) {
                    LocalizationManager.getString("menu.view.hide.sidebar")
                } else {
                    LocalizationManager.getString("menu.view.show.sidebar")
                }
            }

            updateSidebarMenuItem = updateSidebarMenuItemFunc

            updateSidebarMenuItemFunc(true)

            toggleSidebarItem.addActionListener(
                ActionListener {
                    onToggleSidebar()
                },
            )
            viewMenu.add(toggleSidebarItem)

            val fullScreenItem = MenuItem(
                LocalizationManager.getString("menu.view.fullscreen"),
                MenuShortcut(KeyEvent.VK_F, true), // Ctrl+Cmd+F on Mac, Ctrl+F on others
            )
            fullScreenItem.addActionListener(
                ActionListener {
                    onEnterFullScreen()
                },
            )
            viewMenu.add(fullScreenItem)

            menuBar.add(viewMenu)

            // Terminal Menu
            val terminalMenu = Menu(LocalizationManager.getString("menu.terminal"))

            val newTerminalItem = MenuItem(
                LocalizationManager.getString("menu.terminal.new"),
                MenuShortcut(KeyEvent.VK_T, true), // Shift+Ctrl+T (or Shift+Cmd+T on Mac)
            )
            newTerminalItem.addActionListener(
                ActionListener {
                    onOpenTerminal()
                },
            )
            terminalMenu.add(newTerminalItem)

            menuBar.add(terminalMenu)

            // Help Menu
            val helpMenu = Menu(LocalizationManager.getString("menu.help"))

            val docsItem = MenuItem(LocalizationManager.getString("menu.documentation"))
            docsItem.addActionListener(
                ActionListener {
                    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/")) }
                },
            )
            helpMenu.add(docsItem)

            // Getting started
            val gettingStartedItem = MenuItem(LocalizationManager.getString("menu.help.gettingstarted"))
            gettingStartedItem.addActionListener(
                ActionListener {
                    onShowGettingStarted()
                },
            )
            helpMenu.add(gettingStartedItem)

            // Share Feedback — moved here from the footer status bar for better discoverability
            val shareFeedbackItem = MenuItem(LocalizationManager.getString("system.share.feedback"))
            shareFeedbackItem.addActionListener(
                ActionListener {
                    runCatching {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(URI("https://$DOMAIN/contact/"))
                        }
                    }
                },
            )
            helpMenu.add(shareFeedbackItem)

            helpMenu.addSeparator()

            // Release Notes
            val releaseNotesItem = MenuItem(LocalizationManager.getString("menu.help.release.notes"))
            releaseNotesItem.addActionListener(
                ActionListener {
                    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/changelogs/")) }
                },
            )
            helpMenu.add(releaseNotesItem)

            // Star on GitHub
            val starGitHubText = LocalizationManager.getString("menu.help.star.github")
            // On Windows, replace emoji with Unicode star character that renders better in AWT
            val starGitHubDisplayText = if (Platform.isWindows) {
                starGitHubText.replace("⭐", "")
            } else {
                starGitHubText
            }
            val starGitHubItem = MenuItem(starGitHubDisplayText)
            starGitHubItem.addActionListener(
                ActionListener {
                    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://github.com/askimo-ai/askimo")) }
                },
            )
            helpMenu.add(starGitHubItem)

            // Share Askimo submenu
            val shareMenu = Menu(LocalizationManager.getString("menu.help.share"))

            ShareTarget.entries.forEach { target ->
                val item = MenuItem(ShareUtils.labelFor(target))
                item.addActionListener(ActionListener { ShareUtils.share(target) })
                shareMenu.add(item)
            }

            helpMenu.add(shareMenu)

            helpMenu.addSeparator()

            // Check for Updates
            val checkUpdatesItem = MenuItem(LocalizationManager.getString("menu.help.check.updates"))
            checkUpdatesItem.addActionListener(
                ActionListener {
                    onCheckForUpdates()
                },
            )
            helpMenu.add(checkUpdatesItem)

            // Event Log (Developer Tools)
            val eventLogItem = MenuItem(LocalizationManager.getString("menu.eventlog"))
            eventLogItem.addActionListener(
                ActionListener {
                    onShowEventLog()
                },
            )
            helpMenu.add(eventLogItem)

            // Open Model Capabilities File
            val modelCapabilitiesItem = MenuItem(LocalizationManager.getString("menu.help.model.capabilities"))
            modelCapabilitiesItem.addActionListener(
                ActionListener {
                    runCatching {
                        val file = AskimoHome.base().resolve("model-capabilities-cache.json").toFile()
                        if (file.exists() && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(file)
                        }
                    }
                },
            )
            helpMenu.add(modelCapabilitiesItem)

            // System Diagnostics — live CPU/memory and session telemetry
            val systemDiagnosticsItem = MenuItem(LocalizationManager.getString("menu.help.diagnostics"))
            systemDiagnosticsItem.addActionListener(
                ActionListener {
                    onShowSystemDiagnostics()
                },
            )
            helpMenu.add(systemDiagnosticsItem)

            // Clear Account Preferences (Developer Tools — only shown when developer mode is active)
            val devConfig = AppConfig.developer
            if (devConfig.enabled && devConfig.active) {
                val clearAccountPrefsItem = MenuItem(LocalizationManager.getString("menu.dev.clear.account.preferences"))
                clearAccountPrefsItem.addActionListener(
                    ActionListener {
                        onClearAccountPreferences()
                    },
                )
                helpMenu.add(clearAccountPrefsItem)
            }

            helpMenu.addSeparator()

            val aboutItem = MenuItem(LocalizationManager.getString("menu.about"))
            aboutItem.addActionListener(
                ActionListener {
                    onShowAbout()
                },
            )
            helpMenu.add(aboutItem)

            menuBar.add(helpMenu)

            // Set the native menu bar
            window.menuBar = menuBar
        }
    }
}
