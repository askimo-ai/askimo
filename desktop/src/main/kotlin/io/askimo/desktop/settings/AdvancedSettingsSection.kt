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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.askimo.core.analytics.Analytics
import io.askimo.core.config.AppConfig
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.LogLevel
import io.askimo.core.logging.LoggingService
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.util.NumberFormatUtil
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.AccountPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.shell.DeveloperModePreferences
import io.askimo.ui.shell.logViewerDialog
import io.askimo.ui.util.Platform
import kotlinx.coroutines.delay
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
                    .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 36.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("settings.advanced"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                // Log Level Section
                Text(
                    text = stringResource("settings.log.level"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                logLevelCard()

                // Log Viewer Section
                logViewerCard(
                    onViewLogs = { showLogViewerDialog = true },
                )

                // RAG Configuration Section
                ragConfigurationSection()

                // Hardware Acceleration Section
                hardwareAccelerationSection()

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
        colors = AppComponents.bannerCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource("settings.log.level.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Box(modifier = Modifier.fillMaxWidth()) {
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
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource("log.level.${currentLogLevel.name.lowercase()}"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource("log.level.${currentLogLevel.name.lowercase()}.description"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Change log level",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                AppComponents.dropdownMenu(
                    expanded = logLevelDropdownExpanded,
                    onDismissRequest = { logLevelDropdownExpanded = false },
                ) {
                    LogLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = stringResource("log.level.${level.name.lowercase()}"),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = stringResource("log.level.${level.name.lowercase()}.description"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                ThemePreferences.setLogLevel(level)
                                logLevelDropdownExpanded = false
                            },
                            leadingIcon = if (level == currentLogLevel) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            } else {
                                null
                            },
                        )
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
        colors = AppComponents.bannerCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                text = stringResource("settings.log.viewer"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Text(
                text = stringResource("settings.log.viewer.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
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
                    Spacer(modifier = Modifier.width(8.dp))
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource("settings.log.viewer.open_folder"))
                }
            }

            // Show log file path
            LoggingService.getLogFilePath()?.let { logPath ->
                Text(
                    text = stringResource("settings.log.viewer.path", logPath.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
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
private fun hardwareAccelerationSection() {
    var isEnabled by remember { mutableStateOf(AccountPreferences.device().getHardwareAccelerationEnabled()) }
    var showRestartNotice by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.bannerCardColors(),
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
                        text = stringResource("settings.hardware.acceleration.title"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = if (Platform.isWindows) {
                            stringResource("settings.hardware.acceleration.description.windows")
                        } else {
                            stringResource("settings.hardware.acceleration.description")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        AccountPreferences.device().setHardwareAccelerationEnabled(checked)
                        showRestartNotice = true
                    },
                )
            }

            // Restart notice — shown after toggling
            if (showRestartNotice) {
                Text(
                    text = stringResource("settings.hardware.acceleration.restart.notice"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun analyticsSection() {
    var isEnabled by remember { mutableStateOf(Analytics.isEnabled) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.bannerCardColors(),
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
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource("settings.analytics.description"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
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
        colors = AppComponents.bannerCardColors(),
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
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource("settings.developer.mode.description"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
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
        colors = AppComponents.bannerCardColors(),
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
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            // Description
            Text(
                text = stringResource("settings.rag.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )

            // Vector Search Max Results
            ragIntField(
                label = stringResource("settings.rag.vector.max.results"),
                hint = stringResource("settings.rag.vector.max.results.hint"),
                value = AppConfig.rag.vectorSearchMaxResults,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.vectorSearchMaxResults", newValue)
                },
            )

            // Vector Search Min Score
            ragDoubleField(
                label = stringResource("settings.rag.vector.min.score"),
                hint = stringResource("settings.rag.vector.min.score.hint"),
                value = AppConfig.rag.vectorSearchMinScore,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.vectorSearchMinScore", newValue)
                },
            )

            // Hybrid Max Results
            ragIntField(
                label = stringResource("settings.rag.hybrid.max.results"),
                hint = stringResource("settings.rag.hybrid.max.results.hint"),
                value = AppConfig.rag.hybridMaxResults,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.hybridMaxResults", newValue)
                },
            )

            // Rank Fusion Constant
            ragIntField(
                label = stringResource("settings.rag.rank.fusion.constant"),
                hint = stringResource("settings.rag.rank.fusion.constant.hint"),
                value = AppConfig.rag.rankFusionConstant,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.rankFusionConstant", newValue)
                },
            )

            // Use Absolute Paths in Citations
            ragBooleanField(
                label = stringResource("settings.rag.use.absolute.paths"),
                hint = stringResource("settings.rag.use.absolute.paths.hint"),
                value = AppConfig.rag.useAbsolutePathInCitations,
                onValueChange = { newValue ->
                    AppConfig.updateField("rag.useAbsolutePathInCitations", newValue)
                },
            )

            // Divider before embedding configuration
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
            )

            // Embedding Configuration Section
            Text(
                text = stringResource("settings.rag.embedding.title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Text(
                text = stringResource("settings.rag.embedding.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )

            // Max Characters Per Chunk
            ragIntField(
                label = stringResource("settings.rag.embedding.max.chars.per.chunk"),
                hint = stringResource("settings.rag.embedding.max.chars.per.chunk.hint"),
                value = AppConfig.embedding.maxCharsPerChunk,
                onValueChange = { newValue ->
                    AppConfig.updateField("embedding.maxCharsPerChunk", newValue)
                },
            )

            // Chunk Overlap
            ragIntField(
                label = stringResource("settings.rag.embedding.chunk.overlap"),
                hint = stringResource("settings.rag.embedding.chunk.overlap.hint"),
                value = AppConfig.embedding.chunkOverlap,
                onValueChange = { newValue ->
                    AppConfig.updateField("embedding.chunkOverlap", newValue)
                },
            )

            // Divider before indexing configuration
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
            )

            // Indexing Configuration Section
            Text(
                text = stringResource("settings.rag.indexing.title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Text(
                text = stringResource("settings.rag.indexing.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )

            // Max File Bytes
            ragLongField(
                label = stringResource("settings.rag.indexing.max.file.bytes"),
                hint = stringResource("settings.rag.indexing.max.file.bytes.hint"),
                value = AppConfig.indexing.maxFileBytes,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.maxFileBytes", newValue)
                },
            )

            // Supported Extensions
            ragStringSetField(
                label = stringResource("settings.rag.indexing.supported.extensions"),
                hint = stringResource("settings.rag.indexing.supported.extensions.hint"),
                value = AppConfig.indexing.supportedExtensions,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.supportedExtensions", newValue)
                },
            )

            // Excluded File Names
            ragStringSetField(
                label = stringResource("settings.rag.indexing.exclude.file.names"),
                hint = stringResource("settings.rag.indexing.exclude.file.names.hint"),
                value = AppConfig.indexing.excludeFileNames,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.excludeFileNames", newValue)
                },
            )

            // Binary Extensions
            ragStringSetField(
                label = stringResource("settings.rag.indexing.binary.extensions"),
                hint = stringResource("settings.rag.indexing.binary.extensions.hint"),
                value = AppConfig.indexing.binaryExtensions,
                onValueChange = { newValue ->
                    AppConfig.updateField("indexing.binaryExtensions", newValue)
                },
            )
        }
    }
}

@Composable
private fun ragBooleanField(
    label: String,
    hint: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
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
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                )
            }
            Switch(
                checked = value,
                onCheckedChange = onValueChange,
            )
        }
    }
}

@Composable
private fun ragIntField(
    label: String,
    hint: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var lastValidValue by remember { mutableStateOf(value) }
    var textValue by remember { mutableStateOf(LocalizationManager.formatNumber(value)) }
    var isEditing by remember { mutableStateOf(false) }
    var showSavedIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value != lastValidValue) {
            lastValidValue = value
            if (!isEditing) textValue = LocalizationManager.formatNumber(value)
        }
    }

    LaunchedEffect(showSavedIndicator) {
        if (showSavedIndicator) {
            delay(2000.milliseconds)
            showSavedIndicator = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        // Switch to raw number for easy editing
                        isEditing = true
                        textValue = lastValidValue.toString()
                    } else {
                        isEditing = false
                        textValue.toIntOrNull()?.let { validInt ->
                            if (validInt != lastValidValue) {
                                lastValidValue = validInt
                                onValueChange(validInt)
                                showSavedIndicator = true
                            }
                        }
                        // Always reformat on blur (even if unchanged)
                        textValue = LocalizationManager.formatNumber(lastValidValue)
                    }
                },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            isError = isEditing && textValue.toIntOrNull() == null,
            trailingIcon = {
                AnimatedVisibility(visible = showSavedIndicator, enter = fadeIn(), exit = fadeOut()) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            colors = AppComponents.outlinedTextFieldColors(),
        )
        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
    }
}

@Composable
private fun ragDoubleField(
    label: String,
    hint: String,
    value: Double,
    onValueChange: (Double) -> Unit,
) {
    var lastValidValue by remember { mutableStateOf(value) }
    var textValue by remember { mutableStateOf(NumberFormatUtil.formatDecimal(value)) }
    var isEditing by remember { mutableStateOf(false) }
    var showSavedIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value != lastValidValue) {
            lastValidValue = value
            if (!isEditing) textValue = NumberFormatUtil.formatDecimal(value)
        }
    }

    LaunchedEffect(showSavedIndicator) {
        if (showSavedIndicator) {
            delay(2000.milliseconds)
            showSavedIndicator = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        isEditing = true
                        textValue = lastValidValue.toString()
                    } else {
                        isEditing = false
                        textValue.toDoubleOrNull()?.let { validDouble ->
                            if (validDouble != lastValidValue) {
                                lastValidValue = validDouble
                                onValueChange(validDouble)
                                showSavedIndicator = true
                            }
                        }
                        textValue = NumberFormatUtil.formatDecimal(lastValidValue)
                    }
                },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            isError = isEditing && textValue.toDoubleOrNull() == null,
            trailingIcon = {
                AnimatedVisibility(visible = showSavedIndicator, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Default.Check, contentDescription = "Saved", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            },
            colors = AppComponents.outlinedTextFieldColors(),
        )
        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
    }
}

@Composable
private fun ragOptionalIntField(
    label: String,
    hint: String,
    value: Int?,
    onValueChange: (Int?) -> Unit,
) {
    var textValue by remember { mutableStateOf(value?.toString() ?: "") }
    var lastValidValue by remember { mutableStateOf(value) }
    var showSavedIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        // Update text when external value changes (e.g., reload)
        if (value != lastValidValue) {
            textValue = value?.toString() ?: ""
            lastValidValue = value
        }
    }

    LaunchedEffect(showSavedIndicator) {
        if (showSavedIndicator) {
            delay(2000.milliseconds)
            showSavedIndicator = false
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                        // Only save when losing focus if value changed
                        val newValidValue = if (textValue.isBlank()) {
                            null
                        } else {
                            textValue.toIntOrNull()
                        }

                        if (newValidValue != lastValidValue && (textValue.isBlank() || textValue.toIntOrNull() != null)) {
                            lastValidValue = newValidValue
                            onValueChange(newValidValue)
                            showSavedIndicator = true
                        }
                    }
                },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            isError = textValue.isNotBlank() && textValue.toIntOrNull() == null,
            trailingIcon = {
                AnimatedVisibility(
                    visible = showSavedIndicator,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            colors = AppComponents.outlinedTextFieldColors(),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ragLongField(
    label: String,
    hint: String,
    value: Long,
    onValueChange: (Long) -> Unit,
) {
    var lastValidValue by remember { mutableStateOf(value) }
    var textValue by remember { mutableStateOf(LocalizationManager.formatNumber(value)) }
    var isEditing by remember { mutableStateOf(false) }
    var showSavedIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value != lastValidValue) {
            lastValidValue = value
            if (!isEditing) textValue = LocalizationManager.formatNumber(value)
        }
    }

    LaunchedEffect(showSavedIndicator) {
        if (showSavedIndicator) {
            delay(2000.milliseconds)
            showSavedIndicator = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        isEditing = true
                        textValue = lastValidValue.toString()
                    } else {
                        isEditing = false
                        textValue.toLongOrNull()?.let { validLong ->
                            if (validLong != lastValidValue) {
                                lastValidValue = validLong
                                onValueChange(validLong)
                                showSavedIndicator = true
                            }
                        }
                        textValue = LocalizationManager.formatNumber(lastValidValue)
                    }
                },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            isError = isEditing && textValue.toLongOrNull() == null,
            trailingIcon = {
                AnimatedVisibility(visible = showSavedIndicator, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Default.Check, contentDescription = "Saved", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            },
            colors = AppComponents.outlinedTextFieldColors(),
        )
        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
    }
}

/**
 * Editable comma-separated [Set<String>] field.
 * Displays items joined by ", " and parses on focus-lost by splitting on commas.
 */
@Composable
private fun ragStringSetField(
    label: String,
    hint: String,
    value: Set<String>,
    onValueChange: (Set<String>) -> Unit,
) {
    var textValue by remember(value) { mutableStateOf(value.joinToString(", ")) }
    var showSavedIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(showSavedIndicator) {
        if (showSavedIndicator) {
            delay(2000.milliseconds)
            showSavedIndicator = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                        val parsed = textValue
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                        if (parsed != value) {
                            onValueChange(parsed)
                            showSavedIndicator = true
                        }
                    }
                },
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 2,
            maxLines = 5,
            trailingIcon = {
                AnimatedVisibility(visible = showSavedIndicator, enter = fadeIn(), exit = fadeOut()) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            colors = AppComponents.outlinedTextFieldColors(),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
        )
    }
}
