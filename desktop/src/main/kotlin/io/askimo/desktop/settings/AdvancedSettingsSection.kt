/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import io.askimo.ui.shell.logViewerDialog
import io.askimo.ui.util.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.awt.Desktop
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private val log = currentFileLogger()

@Composable
fun advancedSettingsSection() {
    var showLogViewerDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = Spacing.extraLarge, top = Spacing.extraLarge, bottom = Spacing.extraLarge, end = Spacing.scrollbarGutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("settings.advanced"),
                    style = AppTextStyles.pageTitle,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                // Log Level Section
                Text(
                    text = stringResource("settings.log.level"),
                    style = AppTextStyles.sectionTitle,
                )
                logLevelCard()

                // Log Viewer Section
                logViewerCard(
                    onViewLogs = { showLogViewerDialog = true },
                )

                // RAG Configuration Section
                ragConfigurationSection()

                // Models Configuration Section
                modelsConfigurationSection()

                // Memory Configuration Section
                memoryConfigurationSection()

                // Typing Suggestions Section
                typingSuggestionsSection()

                // Analytics Section
                analyticsSection()

                // Developer Mode Section
                developerModeSection()
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            style = AppComponents.scrollbarStyle(),
        )
    }

    // Log Viewer Dialog
    if (showLogViewerDialog) {
        logViewerDialog(
            onDismiss = { showLogViewerDialog = false },
        )
    }
}

@Composable
private fun logLevelCard() {
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
private fun logViewerCard(
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
                        openInFileManager(logDir.toFile())
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

/**
 * Opens a file or directory in the system's file manager.
 * Platform-aware implementation for macOS, Linux, and Windows.
 */
private fun openInFileManager(file: File) {
    try {
        when {
            Platform.isMac -> {
                Runtime.getRuntime().exec(arrayOf("open", file.absolutePath))
            }

            Platform.isLinux -> {
                Runtime.getRuntime().exec(arrayOf("xdg-open", file.absolutePath))
            }

            Platform.isWindows -> {
                Runtime.getRuntime().exec(arrayOf("explorer", file.absolutePath))
            }

            else -> {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file)
                }
            }
        }
    } catch (e: Exception) {
        log.error("Failed to open log directory: ${file.absolutePath}", e)
    }
}

@Composable
private fun analyticsSection() {
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
private fun developerModeSection() {
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
private fun ragConfigurationSection() {
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
            // Title
            Text(
                text = stringResource("settings.rag.title"),
                style = AppTextStyles.sectionTitle,
            )

            // Description
            Text(
                text = stringResource("settings.rag.description"),
                style = AppTextStyles.caption,
            )

            // Vector Search Max Results
            AppComponents.settingsIntField(
                label = stringResource("settings.rag.vector.max.results"),
                hint = stringResource("settings.rag.vector.max.results.hint"),
                value = AppConfig.rag.vectorSearchMaxResults,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.vectorSearchMaxResults", newValue)
                },
            )

            // Vector Search Min Score
            AppComponents.settingsDoubleField(
                label = stringResource("settings.rag.vector.min.score"),
                hint = stringResource("settings.rag.vector.min.score.hint"),
                value = AppConfig.rag.vectorSearchMinScore,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.vectorSearchMinScore", newValue)
                },
            )

            // Hybrid Max Results
            AppComponents.settingsIntField(
                label = stringResource("settings.rag.hybrid.max.results"),
                hint = stringResource("settings.rag.hybrid.max.results.hint"),
                value = AppConfig.rag.hybridMaxResults,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.hybridMaxResults", newValue)
                },
            )

            // Rank Fusion Constant
            AppComponents.settingsIntField(
                label = stringResource("settings.rag.rank.fusion.constant"),
                hint = stringResource("settings.rag.rank.fusion.constant.hint"),
                value = AppConfig.rag.rankFusionConstant,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.rankFusionConstant", newValue)
                },
            )

            // Use Absolute Paths in Citations
            AppComponents.settingsBooleanField(
                label = stringResource("settings.rag.use.absolute.paths"),
                hint = stringResource("settings.rag.use.absolute.paths.hint"),
                value = AppConfig.rag.useAbsolutePathInCitations,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.useAbsolutePathInCitations", newValue)
                },
            )

            // Divider before embedding configuration
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.small),
            )

            // Embedding Configuration Section
            Text(
                text = stringResource("settings.rag.embedding.title"),
                style = AppTextStyles.sectionTitle,
            )

            Text(
                text = stringResource("settings.rag.embedding.description"),
                style = AppTextStyles.caption,
            )

            // Max Characters Per Chunk
            AppComponents.settingsIntField(
                label = stringResource("settings.rag.embedding.max.chars.per.chunk"),
                hint = stringResource("settings.rag.embedding.max.chars.per.chunk.hint"),
                value = AppConfig.embedding.maxCharsPerChunk,
                onValueChange = { newValue ->
                    AppConfig.updateField("embedding.maxCharsPerChunk", newValue)
                },
            )

            // Chunk Overlap
            AppComponents.settingsIntField(
                label = stringResource("settings.rag.embedding.chunk.overlap"),
                hint = stringResource("settings.rag.embedding.chunk.overlap.hint"),
                value = AppConfig.embedding.chunkOverlap,
                onValueChange = { newValue ->
                    AppConfig.updateField("embedding.chunkOverlap", newValue)
                },
            )

            // Divider before indexing configuration
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.small),
            )

            // Indexing Configuration Section
            Text(
                text = stringResource("settings.rag.indexing.title"),
                style = AppTextStyles.sectionTitle,
            )

            Text(
                text = stringResource("settings.rag.indexing.description"),
                style = AppTextStyles.caption,
            )

            // Max File Bytes
            AppComponents.settingsLongField(
                label = stringResource("settings.rag.indexing.max.file.bytes"),
                hint = stringResource("settings.rag.indexing.max.file.bytes.hint"),
                value = AppConfig.indexing.maxFileBytes,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.maxFileBytes", newValue)
                },
            )

            // Supported Extensions
            AppComponents.settingsStringSetField(
                label = stringResource("settings.rag.indexing.supported.extensions"),
                hint = stringResource("settings.rag.indexing.supported.extensions.hint"),
                value = AppConfig.indexing.supportedExtensions,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.supportedExtensions", newValue)
                },
            )

            // Excluded File Names
            AppComponents.settingsStringSetField(
                label = stringResource("settings.rag.indexing.exclude.file.names"),
                hint = stringResource("settings.rag.indexing.exclude.file.names.hint"),
                value = AppConfig.indexing.excludeFileNames,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.excludeFileNames", newValue)
                },
            )

            // Binary Extensions
            AppComponents.settingsStringSetField(
                label = stringResource("settings.rag.indexing.binary.extensions"),
                hint = stringResource("settings.rag.indexing.binary.extensions.hint"),
                value = AppConfig.indexing.binaryExtensions,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.binaryExtensions", newValue)
                },
            )

            // Embedding Batch Size
            AppComponents.settingsLongField(
                label = stringResource("settings.rag.indexing.embedding.batch.size"),
                hint = stringResource("settings.rag.indexing.embedding.batch.size.hint"),
                value = AppConfig.indexing.embeddingBatchSize.toLong(),
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.embeddingBatchSize", newValue.toInt())
                },
            )
        }
    }
}

@Composable
private fun modelsConfigurationSection() {
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
private fun memoryConfigurationSection() {
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
private fun typingSuggestionsSection() {
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
