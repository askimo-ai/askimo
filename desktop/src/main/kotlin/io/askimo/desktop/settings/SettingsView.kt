/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.LocalBackgroundActive
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.settings.appearanceSettingsSection
import io.askimo.ui.settings.shortcutsSettingsSection
import io.askimo.ui.settings.skillsSettingsSection
import org.jetbrains.skia.Image
import java.awt.Cursor

enum class SettingsSection {
    APPEARANCE,
    AI_PROVIDER,
    NETWORK,
    WEB_SEARCH,
    SHORTCUTS,
    MCP_SERVERS,
    SKILLS,
    ADVANCED,
    ABOUT,
}

@Composable
fun settingsViewWithSidebar(
    onClose: () -> Unit,
    settingsViewModel: SettingsViewModel,
    selectedSection: SettingsSection = SettingsSection.APPEARANCE,
    onSectionChange: (SettingsSection) -> Unit = {},
) {
    // Sidebar width as a fraction of screen width (0.0 to 1.0) - load from preferences
    var sidebarWidthFraction by remember { mutableStateOf(ThemePreferences.getSettingsSidebarWidthFraction()) }
    val backgroundActive = LocalBackgroundActive.current

    // Settings view - full screen replacement with top header bar
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (backgroundActive) Transparent else MaterialTheme.colorScheme.background),
    ) {
        // Top Header Bar (full width)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppComponents.sidebarHeaderColor())
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Icon(
                    painter = remember {
                        BitmapPainter(
                            Image.makeFromEncoded(
                                object {}.javaClass.getResourceAsStream("/images/askimo_logo_64.png")?.readBytes()
                                    ?: error("Icon not found"),
                            ).toComposeImageBitmap(),
                        )
                    },
                    contentDescription = "Askimo",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Askimo",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource("settings.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource("action.close"),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider()

        // Content area: Sidebar + Main Content
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Calculate actual sidebar width from fraction
            // Min 180dp, max 30% of screen width, default ~18%
            val minSidebarWidth = 180.dp
            val maxSidebarWidthFraction = 0.30f
            val maxSidebarWidth = (maxWidth * maxSidebarWidthFraction).coerceAtLeast(minSidebarWidth)
            val calculatedWidth = (maxWidth * sidebarWidthFraction).coerceIn(
                minSidebarWidth,
                maxSidebarWidth,
            )

            // Capture maxWidth for use in pointerInput
            val containerWidth = maxWidth

            Row(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Left Sidebar
                Column(
                    modifier = Modifier
                        .width(calculatedWidth)
                        .fillMaxHeight()
                        .background(AppComponents.sidebarSurfaceColor()),
                ) {
                    settingsSidebarItem(
                        title = stringResource("settings.appearance"),
                        icon = Icons.Default.Palette,
                        isSelected = selectedSection == SettingsSection.APPEARANCE,
                        onClick = { onSectionChange(SettingsSection.APPEARANCE) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.ai.provider"),
                        icon = Icons.Default.SmartToy,
                        isSelected = selectedSection == SettingsSection.AI_PROVIDER,
                        onClick = { onSectionChange(SettingsSection.AI_PROVIDER) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.network"),
                        icon = Icons.Outlined.NetworkCheck,
                        isSelected = selectedSection == SettingsSection.NETWORK,
                        onClick = { onSectionChange(SettingsSection.NETWORK) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.web_search"),
                        icon = Icons.Outlined.Search,
                        isSelected = selectedSection == SettingsSection.WEB_SEARCH,
                        onClick = { onSectionChange(SettingsSection.WEB_SEARCH) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.shortcuts"),
                        icon = Icons.Outlined.Keyboard,
                        isSelected = selectedSection == SettingsSection.SHORTCUTS,
                        onClick = { onSectionChange(SettingsSection.SHORTCUTS) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.mcp.servers"),
                        icon = Icons.Outlined.Cable,
                        isSelected = selectedSection == SettingsSection.MCP_SERVERS,
                        onClick = { onSectionChange(SettingsSection.MCP_SERVERS) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.skills"),
                        icon = Icons.Outlined.Extension,
                        isSelected = selectedSection == SettingsSection.SKILLS,
                        onClick = { onSectionChange(SettingsSection.SKILLS) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.advanced"),
                        icon = Icons.Outlined.Tune,
                        isSelected = selectedSection == SettingsSection.ADVANCED,
                        onClick = { onSectionChange(SettingsSection.ADVANCED) },
                    )
                    settingsSidebarItem(
                        title = stringResource("settings.about"),
                        icon = Icons.Default.Info,
                        isSelected = selectedSection == SettingsSection.ABOUT,
                        onClick = { onSectionChange(SettingsSection.ABOUT) },
                    )
                } // End Left Sidebar Column

                // Draggable divider
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
                                // Min 10%, max 30%
                                val coercedFraction = newFraction.coerceIn(0.10f, 0.30f)
                                sidebarWidthFraction = coercedFraction
                                // Save preference
                                ThemePreferences.setSettingsSidebarWidthFraction(coercedFraction)
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

                // Main Content Area
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (selectedSection) {
                        SettingsSection.AI_PROVIDER -> aiProviderSettingsSection(settingsViewModel)
                        SettingsSection.APPEARANCE -> appearanceSettingsSection()
                        SettingsSection.NETWORK -> networkSettingsSection()
                        SettingsSection.WEB_SEARCH -> webSearchSettingsSection()
                        SettingsSection.SHORTCUTS -> shortcutsSettingsSection()
                        SettingsSection.MCP_SERVERS -> mcpServerTemplatesSection()
                        SettingsSection.SKILLS -> skillsSettingsSection()
                        SettingsSection.ADVANCED -> advancedSettingsSection()
                        SettingsSection.ABOUT -> aboutSettingsSection()
                    }
                } // End Box (main content)
            } // End Row (sidebar + content)
        }
    }

    // Dialogs

    if (settingsViewModel.showProviderWizard) {
        providerWizardDialog(viewModel = settingsViewModel)
    }

    if (settingsViewModel.showSettingsDialog) {
        settingsConfigDialog(
            viewModel = settingsViewModel,
            onDismiss = { settingsViewModel.closeSettingsDialog() },
        )
    }
} // End settingsViewWithSidebar function

@Composable
private fun settingsSidebarItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Transparent
        },
    ) {
        Row(
            modifier = Modifier.padding(Spacing.large),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
