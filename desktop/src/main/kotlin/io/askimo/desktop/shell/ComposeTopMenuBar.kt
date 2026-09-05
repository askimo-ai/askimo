/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.config.AppConfig
import io.askimo.core.config.FeatureFlags
import io.askimo.core.util.AskimoHome
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemeMode
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import java.awt.Desktop
import java.net.URI

private enum class TopMenu {
    FILE,
    VIEW,
    TERMINAL,
    HELP,
}

@Composable
fun composeTopMenuBar(
    onShowAbout: () -> Unit,
    onNewChat: () -> Unit,
    onNewProject: () -> Unit,
    onSearchInSessions: () -> Unit,
    onShowSettings: () -> Unit,
    onShowEventLog: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onNavigateToDiscover: () -> Unit,
    onToggleSidebar: () -> Unit,
    onInvalidateCaches: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onShowGettingStarted: () -> Unit,
    onOpenTerminal: () -> Unit,
    onClearPreferences: () -> Unit,
    onClearAccountPreferences: (() -> Unit)? = null,
    onTogglePlans: (() -> Unit)?,
    onToggleSkills: (() -> Unit)?,
    onToggleProjects: (() -> Unit)?,
    isPlansVisible: Boolean,
    isSkillsVisible: Boolean,
    isProjectsVisible: Boolean,
    isFullScreen: Boolean,
    onShowSystemDiagnostics: () -> Unit,
    onNavigateToBookmarks: () -> Unit,
    onSupportAskimo: () -> Unit,
    onShareFeedback: () -> Unit,
    isSidebarExpanded: Boolean,
) {
    var expandedMenu by remember { mutableStateOf<TopMenu?>(null) }

    Column(modifier = Modifier.fillMaxWidth().background(AppColors.sidebarSurfaceColor())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            menuAnchor(
                label = stringResource("menu.file"),
                expanded = expandedMenu == TopMenu.FILE,
                onExpandToggle = { expandedMenu = if (expandedMenu == TopMenu.FILE) null else TopMenu.FILE },
                onDismiss = { expandedMenu = null },
            ) {
                menuAction("chat.new") {
                    expandedMenu = null
                    onNewChat()
                }
                menuAction("menu.new.project") {
                    expandedMenu = null
                    onNewProject()
                }
                menuDivider()
                menuAction("menu.search.sessions") {
                    expandedMenu = null
                    onSearchInSessions()
                }
                menuDivider()
                menuAction("menu.export.backup") {
                    expandedMenu = null
                    onExportBackup()
                }
                menuAction("menu.import.backup") {
                    expandedMenu = null
                    onImportBackup()
                }
                menuDivider()
                menuAction("menu.invalidate.caches") {
                    expandedMenu = null
                    onInvalidateCaches()
                }
                menuAction("menu.clear.preferences") {
                    expandedMenu = null
                    onClearPreferences()
                }
                menuDivider()
                menuAction("settings.title") {
                    expandedMenu = null
                    onShowSettings()
                }
            }

            menuAnchor(
                label = stringResource("menu.view"),
                expanded = expandedMenu == TopMenu.VIEW,
                onExpandToggle = { expandedMenu = if (expandedMenu == TopMenu.VIEW) null else TopMenu.VIEW },
                onDismiss = { expandedMenu = null },
            ) {
                val currentTheme = ThemePreferences.themeMode.value
                menuToggleAction("menu.view.appearance.system", currentTheme == ThemeMode.SYSTEM) {
                    expandedMenu = null
                    ThemePreferences.setThemeMode(ThemeMode.SYSTEM)
                }
                menuToggleAction("menu.view.appearance.light", currentTheme == ThemeMode.LIGHT) {
                    expandedMenu = null
                    ThemePreferences.setThemeMode(ThemeMode.LIGHT)
                }
                menuToggleAction("menu.view.appearance.dark", currentTheme == ThemeMode.DARK) {
                    expandedMenu = null
                    ThemePreferences.setThemeMode(ThemeMode.DARK)
                }
                menuDivider()
                menuAction("menu.view.discover") {
                    expandedMenu = null
                    onNavigateToDiscover()
                }
                if (FeatureFlags.plansEnabled) {
                    menuToggleAction("menu.view.plans", isPlansVisible) {
                        expandedMenu = null
                        onTogglePlans?.invoke()
                    }
                }
                if (FeatureFlags.skillsEnabled) {
                    menuToggleAction("menu.view.skills", isSkillsVisible) {
                        expandedMenu = null
                        onToggleSkills?.invoke()
                    }
                }
                if (FeatureFlags.projectsEnabled) {
                    menuToggleAction("menu.view.projects", isProjectsVisible) {
                        expandedMenu = null
                        onToggleProjects?.invoke()
                    }
                }
                menuDivider()
                menuAction(if (isSidebarExpanded) "menu.view.hide.sidebar" else "menu.view.show.sidebar") {
                    expandedMenu = null
                    onToggleSidebar()
                }
                menuAction(if (isFullScreen) "menu.view.exit.fullscreen" else "menu.view.fullscreen") {
                    expandedMenu = null
                    onToggleFullScreen()
                }
                menuDivider()
                menuAction("menu.view.bookmarks") {
                    expandedMenu = null
                    onNavigateToBookmarks()
                }
            }

            menuAnchor(
                label = stringResource("menu.terminal"),
                expanded = expandedMenu == TopMenu.TERMINAL,
                onExpandToggle = { expandedMenu = if (expandedMenu == TopMenu.TERMINAL) null else TopMenu.TERMINAL },
                onDismiss = { expandedMenu = null },
            ) {
                menuAction("menu.terminal.new") {
                    expandedMenu = null
                    onOpenTerminal()
                }
            }

            menuAnchor(
                label = stringResource("menu.help"),
                expanded = expandedMenu == TopMenu.HELP,
                onExpandToggle = { expandedMenu = if (expandedMenu == TopMenu.HELP) null else TopMenu.HELP },
                onDismiss = { expandedMenu = null },
            ) {
                menuAction("menu.documentation") {
                    expandedMenu = null
                    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/")) }
                }
                menuAction("menu.help.gettingstarted") {
                    expandedMenu = null
                    onShowGettingStarted()
                }
                menuAction("system.share.feedback") {
                    expandedMenu = null
                    onShareFeedback()
                }
                menuDivider()
                menuAction("menu.help.release.notes") {
                    expandedMenu = null
                    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/changelogs/")) }
                }
                menuAction("menu.help.discord") {
                    expandedMenu = null
                    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://discord.gg/eXSBR4fNmm")) }
                }
                menuAction("menu.help.support.askimo") {
                    expandedMenu = null
                    onSupportAskimo()
                }
                menuDivider()
                menuAction("menu.help.check.updates") {
                    expandedMenu = null
                    onCheckForUpdates()
                }
                menuAction("menu.eventlog") {
                    expandedMenu = null
                    onShowEventLog()
                }
                menuAction("menu.help.model.capabilities") {
                    expandedMenu = null
                    runCatching {
                        val file = AskimoHome.base().resolve("model-capabilities-cache.json").toFile()
                        if (file.exists() && Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
                    }
                }
                menuAction("menu.help.diagnostics") {
                    expandedMenu = null
                    onShowSystemDiagnostics()
                }
                val devConfig = AppConfig.developer
                if (devConfig.enabled && devConfig.active && onClearAccountPreferences != null) {
                    menuAction("menu.dev.clear.account.preferences") {
                        expandedMenu = null
                        onClearAccountPreferences()
                    }
                }
                menuDivider()
                menuAction("menu.about") {
                    expandedMenu = null
                    onShowAbout()
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * A top-menu anchor label that opens a [Popup] positioned pixel-exactly at the bottom
 * of the label. Using [Popup] (not [DropdownMenu]) gives us full control over placement
 * and avoids the gap that Material3's internal 8 dp top padding introduces.
 */
@Composable
private fun menuAnchor(
    label: String,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    var anchorHeightPx by remember { mutableStateOf(0) }

    Box {
        Text(
            text = label,
            style = AppTextStyles.body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickableCard { onExpandToggle() }
                // Measure height so the popup can be offset to exactly the bottom edge.
                .onSizeChanged { anchorHeightPx = it.height }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )

        if (expanded) {
            // Popup escapes the parent's clipping bounds and renders as a real overlay.
            // alignment = TopStart + offset(0, anchorHeight) puts the top-left corner of
            // the popup panel flush against the bottom edge of the anchor label.
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x = 0, y = anchorHeightPx),
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                val borderColor = AppColors.codeBlockBorderColor()
                Surface(
                    shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp, topStart = 0.dp, topEnd = 4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .shadow(8.dp, RoundedCornerShape(4.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(4.dp)),
                ) {
                    Column(modifier = Modifier.width(IntrinsicSize.Max)) { content() }
                }
            }
        }
    }
}

@Composable
private fun menuAction(key: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(key)) },
        onClick = onClick,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        colors = AppColors.menuItemColors(),
    )
}

@Composable
private fun menuToggleAction(key: String, isSelected: Boolean, onClick: () -> Unit) {
    AppComponents.themedDropdownMenuItem(
        text = { Text(stringResource(key)) },
        onClick = onClick,
        isSelected = isSelected,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun menuDivider() {
    HorizontalDivider(color = AppColors.codeBlockBorderColor())
}
