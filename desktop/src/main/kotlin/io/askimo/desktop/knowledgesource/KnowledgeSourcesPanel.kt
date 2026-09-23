/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.knowledgesource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFilesKnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStatus
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.successIcon
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/**
 * Collapsible "Knowledge Sources" panel shared between Project and Resource Collection
 * detail views: a header (source count, "ready" badge, RAG docs link, optional "Add"
 * button), an indexing progress indicator, a skipped-files warning, and — when
 * expanded — the list of knowledge sources grouped by type.
 *
 * Row rendering is delegated to [renderItem] so each caller keeps its own per-item
 * actions (e.g. Project supports rescan + watch-for-changes, Resource Collection only
 * supports delete).
 *
 * @param key Stable identity (e.g. project or collection id) used to reset the
 *   expanded/collapsed state via `remember` when switching between containers.
 */
@Composable
fun knowledgeSourcesPanel(
    key: Any,
    knowledgeSources: List<KnowledgeSourceConfig>,
    indexProgress: IndexProgress,
    modifier: Modifier = Modifier,
    onShowAddDialog: (() -> Unit)? = null,
    renderItem: @Composable (KnowledgeSourceConfig) -> Unit,
) {
    var isExpanded by remember(key) {
        mutableStateOf(knowledgeSources.isEmpty())
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
        ) {
            knowledgeSourcesPanelHeader(
                knowledgeSources = knowledgeSources,
                indexProgress = indexProgress,
                isExpanded = isExpanded,
                onExpandedChange = { isExpanded = it },
                onShowAddDialog = onShowAddDialog,
            )

            // ── Index progress indicator ───────────────────────────────────
            knowledgeSourcesIndexProgressIndicator(
                indexProgress = indexProgress,
                hasKnowledgeSources = knowledgeSources.isNotEmpty(),
            )

            // ── Skipped files warning ──────────────────────────────────────
            skippedFilesWarning(skippedFileNames = indexProgress.skippedFileNames)

            // Expandable content
            if (knowledgeSources.isNotEmpty()) {
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            color = AppColors.codeBlockBorderColor(),
                        )

                        val groupedSources = knowledgeSources.groupBy { source ->
                            when (source) {
                                is LocalFoldersKnowledgeSourceConfig -> stringResource("projects.sources.type.local_folders")
                                is LocalFilesKnowledgeSourceConfig -> stringResource("projects.sources.type.local_files")
                                is UrlKnowledgeSourceConfig -> stringResource("projects.sources.type.urls")
                            }
                        }

                        groupedSources.forEach { (groupName, sources) ->
                            Text(
                                text = groupName,
                                style = AppTextStyles.fieldLabel,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = Spacing.small, bottom = Spacing.extraSmall),
                            )
                            sources.forEach { source ->
                                renderItem(source)
                            }
                        }
                    }
                }
            } else {
                // Empty state description below the header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.codeBlockBorderColor(),
                    )
                    Text(
                        text = stringResource("projects.sources.empty.description"),
                        style = AppTextStyles.caption,
                        color = AppColors.secondaryIconColor(),
                    )
                }
            }
        }
    }
}

/**
 * The collapsible header row shared between [knowledgeSourcesPanel] and
 * [knowledgeSourcesTreePanel] — source count, "ready" badge, guide link,
 * optional "Add" button, and the expand/collapse chevron.
 */
@Composable
private fun knowledgeSourcesPanelHeader(
    knowledgeSources: List<KnowledgeSourceConfig>,
    indexProgress: IndexProgress,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onShowAddDialog: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side - clickable expansion area (only if sources exist)
        if (knowledgeSources.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClick = { onExpandedChange(!isExpanded) },
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    )
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                    contentDescription = null,
                    tint = AppTextStyles.primaryContent,
                    modifier = Modifier.size(20.dp),
                )

                Text(
                    text = stringResource("projects.sources.count", knowledgeSources.size),
                    style = AppTextStyles.body,
                    fontWeight = FontWeight.SemiBold,
                )

                // ── Indexed badge ──────────────────────────────────
                if (indexProgress.isComplete) {
                    themedTooltip(text = stringResource("project.indexing.ready.tooltip")) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            successIcon(size = 14.dp)
                            Text(
                                text = stringResource("project.indexing.ready.label"),
                                style = AppTextStyles.hint,
                            )
                        }
                    }
                }

                val rotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    label = "rotation",
                )

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) {
                        stringResource("projects.sources.collapse")
                    } else {
                        stringResource("projects.sources.expand")
                    },
                    tint = AppTextStyles.primaryContent,
                    modifier = Modifier.rotate(rotation),
                )

                themedTooltip(text = stringResource("projects.sources.info.tooltip")) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AppColors.secondaryIconColor(),
                        modifier = Modifier
                            .size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        } else {
            // Empty state header
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                    contentDescription = null,
                    tint = AppTextStyles.primaryContent,
                    modifier = Modifier.size(20.dp),
                )

                Text(
                    text = stringResource("projects.sources.empty.title"),
                    style = AppTextStyles.body,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Right side - Guide link + optional Add button (always visible)
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            linkButton(
                onClick = {
                    try {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/rag/"))
                        }
                    } catch (_: Exception) {}
                },
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource("projects.sources.guide"),
                    style = AppTextStyles.caption,
                    modifier = Modifier.padding(start = Spacing.extraSmall),
                )
            }

            if (onShowAddDialog != null) {
                themedTooltip(text = stringResource("projects.sources.add.tooltip")) {
                    IconButton(
                        onClick = onShowAddDialog,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource("projects.sources.add.tooltip"),
                            tint = AppTextStyles.primaryContent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsible "Knowledge Sources" panel showing a tree-structured view of the
 * reference materials (folders/files/URLs) with per-file indexed status icons.
 * Shares the header chrome with [knowledgeSourcesPanel] but renders a
 * [ragSourcesTree] instead of a flat grouped list.
 *
 * Neutral, shared component both Project and Resource Collection detail views
 * depend on — neither package depends on the other.
 *
 * @param key Stable identity (e.g. project or collection id) used to reset the
 *   expanded/collapsed state via `remember` when switching between containers.
 */
@Composable
fun knowledgeSourcesTreePanel(
    key: Any,
    knowledgeSources: List<KnowledgeSourceConfig>,
    indexProgress: IndexProgress,
    indexedPaths: Set<String>,
    modifier: Modifier = Modifier,
    onShowAddDialog: (() -> Unit)? = null,
    onRemove: (KnowledgeSourceConfig) -> Unit = {},
    onWatchToggle: ((LocalFoldersKnowledgeSourceConfig, Boolean) -> Unit)? = null,
) {
    var isExpanded by remember(key) { mutableStateOf(knowledgeSources.isEmpty()) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
        ) {
            knowledgeSourcesPanelHeader(
                knowledgeSources = knowledgeSources,
                indexProgress = indexProgress,
                isExpanded = isExpanded,
                onExpandedChange = { isExpanded = it },
                onShowAddDialog = onShowAddDialog,
            )

            knowledgeSourcesIndexProgressIndicator(
                indexProgress = indexProgress,
                hasKnowledgeSources = knowledgeSources.isNotEmpty(),
            )
            skippedFilesWarning(skippedFileNames = indexProgress.skippedFileNames)

            if (knowledgeSources.isNotEmpty()) {
                AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium)) {
                        HorizontalDivider(modifier = Modifier.fillMaxWidth(), color = AppColors.codeBlockBorderColor())
                        ragSourcesTree(
                            sources = knowledgeSources,
                            indexedPaths = indexedPaths,
                            modifier = Modifier.fillMaxWidth().height(400.dp),
                            onRemove = onRemove,
                            onWatchToggle = onWatchToggle,
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium)) {
                    HorizontalDivider(modifier = Modifier.fillMaxWidth(), color = AppColors.codeBlockBorderColor())
                    Text(
                        text = stringResource("projects.sources.empty.description"),
                        style = AppTextStyles.caption,
                        color = AppColors.secondaryIconColor(),
                    )
                }
            }
        }
    }
}

/**
 * Indexing progress indicator shared between Project and Resource Collection
 * knowledge-source panels. Renders nothing when [hasKnowledgeSources] is false
 * or when indexing is [IndexStatus.READY]/[IndexStatus.WATCHING]/not started.
 */
@Composable
fun knowledgeSourcesIndexProgressIndicator(
    indexProgress: IndexProgress,
    hasKnowledgeSources: Boolean,
) {
    if (!hasKnowledgeSources) return

    when (indexProgress.status) {
        IndexStatus.NOT_STARTED, IndexStatus.QUEUED -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppComponents.loadingSpinner(size = 14.dp)
                Text(
                    text = indexProgress.blockedByName?.let {
                        stringResource("project.indexing.queued", it)
                    } ?: stringResource("project.indexing.waiting"),
                    style = AppTextStyles.caption,
                )
            }
        }

        IndexStatus.INDEXING -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    indexProgress.resourceIdentifier?.let { resourceIdentifier ->
                        Text(
                            text = stringResource("project.indexing.label", resourceIdentifier),
                            style = AppTextStyles.caption,
                        )
                    }
                    if (indexProgress.totalFiles > 0) {
                        Text(
                            text = "${indexProgress.processedFiles} / ${indexProgress.totalFiles} (${indexProgress.progressPercentFormatted}%)",
                            style = AppTextStyles.caption,
                        )
                    }
                }

                if (indexProgress.progressPercent > 0f) {
                    LinearProgressIndicator(
                        progress = { indexProgress.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        trackColor = AppColors.surfaceColor(AppColors.Elevation.RECESSED),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        trackColor = AppColors.surfaceColor(AppColors.Elevation.RECESSED),
                    )
                }
                indexProgress.currentFile?.let { file ->
                    val backendElapsedMs = indexProgress.currentFileElapsedMs

                    // Baseline resets whenever a new file starts being processed.
                    val baseWallClock = remember(indexProgress.currentFile) { mutableStateOf(System.currentTimeMillis()) }
                    val baseElapsed = remember(indexProgress.currentFile) { mutableStateOf(0L) }
                    var liveElapsedMs by remember(indexProgress.currentFile) { mutableStateOf(0L) }

                    // Skip that first fire and start the timer from zero for each new file.
                    val seenFirstBackendUpdate = remember(indexProgress.currentFile) { mutableStateOf(false) }

                    // When the backend reports a higher value (a batch just completed),
                    // advance the baseline so the display never goes backwards.
                    LaunchedEffect(backendElapsedMs) {
                        if (!seenFirstBackendUpdate.value) {
                            seenFirstBackendUpdate.value = true
                            return@LaunchedEffect
                        }
                        if (backendElapsedMs >= baseElapsed.value) {
                            baseElapsed.value = backendElapsedMs
                            baseWallClock.value = System.currentTimeMillis()
                            liveElapsedMs = backendElapsedMs
                        }
                    }

                    // Tick every 500 ms to interpolate smoothly between backend events.
                    LaunchedEffect(indexProgress.currentFile) {
                        while (true) {
                            delay(500.milliseconds)
                            liveElapsedMs = baseElapsed.value + (System.currentTimeMillis() - baseWallClock.value)
                        }
                    }

                    val elapsedText = when {
                        liveElapsedMs >= 1000 -> " (${LocalizationManager.formatNumber(liveElapsedMs / 1000)}s)"
                        else -> ""
                    }
                    Text(
                        text = "$file$elapsedText",
                        style = AppTextStyles.hint,
                        color = AppColors.secondaryIconColor(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        IndexStatus.FAILED -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = indexProgress.error
                        ?: stringResource("project.indexing.failed"),
                    style = AppTextStyles.errorText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        else -> Unit // READY, WATCHING — no indicator needed
    }
}

/**
 * Collapsible warning shown in the knowledge sources panel when one or more files
 * could not be indexed (e.g. image-only PDFs with no extractable text). Shared
 * between the Project and Resource Collection knowledge-source panels.
 */
@Composable
fun skippedFilesWarning(skippedFileNames: List<String>) {
    if (skippedFileNames.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "skipped_rotation",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.small),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = { expanded = !expanded },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                )
                .pointerHoverIcon(PointerIcon.Hand),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = AppTextStyles.secondaryContent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "${skippedFileNames.size} file(s) skipped — no extractable text (e.g. image-only PDFs)",
                style = AppTextStyles.hint,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = AppTextStyles.secondaryContent,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.extraSmall, start = Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.micro),
            ) {
                skippedFileNames.forEach { name ->
                    Text(
                        text = "• $name",
                        style = AppTextStyles.hint,
                        color = AppColors.secondaryIconColor(),
                    )
                }
            }
        }
    }
}
