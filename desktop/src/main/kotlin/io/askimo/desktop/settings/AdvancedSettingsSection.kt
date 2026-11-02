/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.rag.MetadataAwareContentInjector
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.settings.analyticsSection
import io.askimo.ui.settings.developerModeSection
import io.askimo.ui.settings.logLevelCard
import io.askimo.ui.settings.logViewerCard
import io.askimo.ui.settings.memoryConfigurationSection
import io.askimo.ui.settings.modelsConfigurationSection
import io.askimo.ui.settings.typingSuggestionsSection
import io.askimo.ui.shell.logViewerDialog

/**
 * Community-edition "Advanced" settings page.
 *
 * Composes the sections shared with other Askimo editions (log level, log viewer,
 * models, memory, typing suggestions, analytics, developer mode — see
 * [io.askimo.ui.settings] `AdvancedSettingsSection.kt`) alongside the RAG/indexing
 * configuration section below, which is community-only.
 */
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
private fun ragConfigurationSection() {
    var citationStyle by remember { mutableStateOf(AppConfig.rag.citationStyle) }
    var citationStyleDropdownExpanded by remember { mutableStateOf(false) }

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

            // Citation Style
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = Spacing.large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    Text(
                        text = stringResource("settings.rag.citation.style"),
                        style = AppTextStyles.fieldLabel,
                    )
                    Text(
                        text = stringResource("settings.rag.citation.style.hint"),
                        style = AppTextStyles.caption,
                    )
                }

                Box(modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)) {
                    themedTooltip(
                        text = stringResource("settings.rag.citation.style.${citationStyle.name.lowercase()}.description"),
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableCard { citationStyleDropdownExpanded = true },
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
                                    text = stringResource("settings.rag.citation.style.${citationStyle.name.lowercase()}"),
                                    style = AppTextStyles.body,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = Spacing.small),
                                )
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Change citation style",
                                    tint = AppTextStyles.primaryContent,
                                )
                            }
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = citationStyleDropdownExpanded,
                        onDismissRequest = { citationStyleDropdownExpanded = false },
                    ) {
                        val styles = MetadataAwareContentInjector.CitationStyle.entries
                        styles.forEachIndexed { index, style ->
                            AppComponents.themedDropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                        Text(
                                            text = stringResource("settings.rag.citation.style.${style.name.lowercase()}"),
                                            style = AppTextStyles.body,
                                        )
                                        Text(
                                            text = stringResource("settings.rag.citation.style.${style.name.lowercase()}.description"),
                                            style = AppTextStyles.caption,
                                        )
                                    }
                                },
                                onClick = {
                                    citationStyle = style
                                    AppConfig.updateField("rag.citationStyle", style)
                                    citationStyleDropdownExpanded = false
                                },
                                isSelected = style == citationStyle,
                                showDivider = index < styles.lastIndex,
                            )
                        }
                    }
                }
            }

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
