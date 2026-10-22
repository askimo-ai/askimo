/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.analytics.Analytics
import io.askimo.core.chat.service.PhraseHabitRefreshService
import io.askimo.core.config.AppConfig
import io.askimo.core.config.MIN_MODEL_TIMEOUT_SECONDS
import io.askimo.core.config.MemoryMode
import io.askimo.core.logging.LogLevel
import io.askimo.core.logging.LoggingService
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.user.repository.UserProfileRepository
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.shell.DeveloperModePreferences
import io.askimo.ui.util.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reusable "Advanced settings" sub-sections shared across Askimo editions
 * (community + app). Each composable is self-contained and edition-agnostic —
 * the orchestrating `advancedSettingsSection()` page lives per edition so each
 * one can pick exactly which sections to include (e.g. RAG configuration is
 * community-only and defined in that edition's own module).
 */
private val log = currentFileLogger()

@Composable
fun logLevelCard() {
    val currentLogLevel by ThemePreferences.logLevel.collectAsState()
    var logLevelDropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.log.level.description"),
                    style = AppTextStyles.body,
                    modifier = Modifier.weight(1f).padding(end = Spacing.large),
                )

                Box(modifier = Modifier.widthIn(min = 140.dp, max = 240.dp)) {
                    themedTooltip(
                        text = stringResource("log.level.${currentLogLevel.name.lowercase()}.description"),
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableCard { logLevelDropdownExpanded = true },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.medium),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource("log.level.${currentLogLevel.name.lowercase()}"),
                                    style = AppTextStyles.body,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = Spacing.small),
                                )
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Change log level",
                                    tint = AppTextStyles.primaryContent,
                                )
                            }
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = logLevelDropdownExpanded,
                        onDismissRequest = { logLevelDropdownExpanded = false },
                    ) {
                        val levels = LogLevel.entries
                        levels.forEachIndexed { index, level ->
                            AppComponents.themedDropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = stringResource("log.level.${level.name.lowercase()}"),
                                            style = AppTextStyles.body,
                                        )
                                        Text(
                                            text = stringResource("log.level.${level.name.lowercase()}.description"),
                                            style = AppTextStyles.caption,
                                        )
                                    }
                                },
                                onClick = {
                                    ThemePreferences.setLogLevel(level)
                                    logLevelDropdownExpanded = false
                                },
                                isSelected = level == currentLogLevel,
                                showDivider = index < levels.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun logViewerCard(
    onViewLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                text = stringResource("settings.log.viewer"),
                style = AppTextStyles.sectionTitle,
            )

            Text(
                text = stringResource("settings.log.viewer.description"),
                style = AppTextStyles.body,
            )

            // Buttons Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                // View Logs Button
                primaryButton(
                    onClick = onViewLogs,
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(stringResource("settings.log.viewer.view"))
                }

                // Open Log Folder Button
                secondaryButton(
                    onClick = {
                        val logDir = LoggingService.getLogDirectory()
                        Platform.openInFileManager(logDir.toFile())
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(stringResource("settings.log.viewer.open_folder"))
                }
            }

            // Show log file path
            LoggingService.getLogFilePath()?.let { logPath ->
                Text(
                    text = stringResource("settings.log.viewer.path", logPath.toString()),
                    style = AppTextStyles.caption,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
fun analyticsSection() {
    var isEnabled by remember { mutableStateOf(Analytics.isEnabled) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    Text(
                        text = stringResource("settings.analytics.title"),
                        style = AppTextStyles.sectionTitle,
                    )
                    Text(
                        text = stringResource("settings.analytics.description"),
                        style = AppTextStyles.caption,
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        if (checked) Analytics.optIn() else Analytics.optOut()
                    },
                )
            }
        }
    }
}

@Composable
fun developerModeSection() {
    val isDeveloperModeEnabled = remember { DeveloperModePreferences.isEnabled() }

    // Only show this section if developer mode is enabled in config
    if (!isDeveloperModeEnabled) {
        return
    }

    val isDeveloperModeActive by DeveloperModePreferences.isActive.collectAsState()

    // Developer Mode Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    Text(
                        text = stringResource("settings.developer.mode"),
                        style = AppTextStyles.sectionTitle,
                    )
                    Text(
                        text = stringResource("settings.developer.mode.description"),
                        style = AppTextStyles.caption,
                    )
                }
                Switch(
                    checked = isDeveloperModeActive,
                    onCheckedChange = { DeveloperModePreferences.setActive(it) },
                )
            }
        }
    }
}

@Composable
fun modelsConfigurationSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            Text(
                text = stringResource("settings.models.title"),
                style = AppTextStyles.sectionTitle,
            )

            Text(
                text = stringResource("settings.models.description"),
                style = AppTextStyles.caption,
            )

            AppComponents.settingsIntField(
                label = stringResource("settings.models.max.tool.calling.round.trips"),
                hint = stringResource("settings.models.max.tool.calling.round.trips.hint"),
                value = AppConfig.models.maxToolCallingRoundTrips,
                onValueChange = { newValue ->
                    AppConfig.updateField("models.maxToolCallingRoundTrips", newValue)
                },
            )

            // Unified request timeout — applied to both the utility and default/primary
            // models so slower local models (e.g. Ollama on modest hardware) don't hit
            // HttpTimeoutException before they finish responding.
            AppComponents.settingsLongField(
                label = stringResource("settings.models.timeout.seconds"),
                hint = stringResource("settings.models.timeout.seconds.hint"),
                value = AppConfig.models.requestTimeoutInSeconds,
                minValue = MIN_MODEL_TIMEOUT_SECONDS,
                onValueChange = { newValue ->
                    AppConfig.setGlobalModelTimeoutSeconds(newValue)
                },
            )
        }
    }
}

@Composable
fun memoryConfigurationSection() {
    var currentMode by remember { mutableStateOf(AppConfig.memory.mode) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            Text(
                text = stringResource("settings.memory.title"),
                style = AppTextStyles.sectionTitle,
            )

            Text(
                text = stringResource("settings.memory.description"),
                style = AppTextStyles.caption,
            )

            // Mode selector — label + dropdown (same pattern as web search backend)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.memory.mode"),
                    style = AppTextStyles.fieldLabel,
                    modifier = Modifier.weight(1f).padding(end = Spacing.large),
                )

                Box(modifier = Modifier.widthIn(min = 160.dp, max = 240.dp)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableCard { dropdownExpanded = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource("settings.memory.mode.${currentMode.name.lowercase()}"),
                                style = AppTextStyles.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = Spacing.small),
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change memory mode",
                                tint = AppTextStyles.primaryContent,
                            )
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        MemoryMode.entries.forEachIndexed { index, mode ->
                            AppComponents.themedDropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                        Text(
                                            text = stringResource("settings.memory.mode.${mode.name.lowercase()}"),
                                            style = AppTextStyles.body,
                                        )
                                        Text(
                                            text = stringResource("settings.memory.mode.${mode.name.lowercase()}.description"),
                                            style = AppTextStyles.caption,
                                        )
                                    }
                                },
                                onClick = {
                                    currentMode = mode
                                    AppConfig.updateField("memory.mode", mode)
                                    dropdownExpanded = false
                                },
                                isSelected = mode == currentMode,
                                showDivider = index < MemoryMode.entries.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun typingSuggestionsSection() {
    val phraseHabitRefreshService = remember { GlobalContext.get().get<PhraseHabitRefreshService>() }
    val userProfileRepository = remember { GlobalContext.get().get<UserProfileRepository>() }
    var aiExtractionEnabled by remember { mutableStateOf(AppConfig.suggestions.aiExtractionEnabled) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showResetSuccess by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(showResetSuccess) {
        if (showResetSuccess) {
            delay(2000.milliseconds)
            showResetSuccess = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    Text(
                        text = stringResource("settings.suggestions.title"),
                        style = AppTextStyles.sectionTitle,
                    )
                    Text(
                        text = stringResource("settings.suggestions.description"),
                        style = AppTextStyles.caption,
                    )
                }
                Switch(
                    checked = aiExtractionEnabled,
                    onCheckedChange = { checked ->
                        aiExtractionEnabled = checked
                        AppConfig.updateField("suggestions.aiExtractionEnabled", checked)
                    },
                )
            }

            if (aiExtractionEnabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dangerButton(onClick = { showConfirmDialog = true }) {
                        Text(stringResource("settings.suggestions.reset"))
                    }

                    AnimatedVisibility(visible = showResetSuccess, enter = fadeIn(), exit = fadeOut()) {
                        Text(
                            text = stringResource("settings.suggestions.reset.success"),
                            style = AppTextStyles.caption,
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AppComponents.alertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource("settings.suggestions.reset")) },
            text = { Text(stringResource("settings.suggestions.reset.confirm")) },
            confirmButton = {
                dangerButton(
                    onClick = {
                        showConfirmDialog = false
                        coroutineScope.launch {
                            runCatching {
                                val profileId = userProfileRepository.getProfile().id
                                phraseHabitRefreshService.resetSuggestions(profileId)
                            }.onSuccess {
                                showResetSuccess = true
                            }.onFailure { e ->
                                log.error("Failed to reset phrase suggestions", e)
                            }
                        }
                    },
                ) {
                    Text(stringResource("settings.suggestions.reset"))
                }
            },
            dismissButton = {
                secondaryButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource("settings.suggestions.reset.cancel"))
                }
            },
        )
    }
}
