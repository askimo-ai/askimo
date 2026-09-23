/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.resourcecollection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.IndexingRequestedEvent
import io.askimo.core.event.internal.ReIndexEvent
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.rag.container.IndexingContainerType
import io.askimo.core.rag.state.IndexStatus
import io.askimo.desktop.knowledgesource.addReferenceMaterialDialog
import io.askimo.desktop.knowledgesource.buildKnowledgeSourceConfigs
import io.askimo.desktop.knowledgesource.knowledgeSourcesPanel
import io.askimo.desktop.knowledgesource.ragSourcesTreeWithPreview
import io.askimo.desktop.project.reIndexConfirmDialog
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf

private val log = currentFileLogger()

@Composable
fun resourceCollectionView(
    collectionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(collectionId) {
        GlobalContext.get().get<ResourceCollectionDetailViewModel> { parametersOf(scope, collectionId) }
    }

    var showCollectionMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showReIndexConfirmDialog by remember { mutableStateOf(false) }
    var showAddReferenceMaterialDialog by remember(collectionId) { mutableStateOf(false) }
    var selectedSourceForTree by remember(collectionId) { mutableStateOf<KnowledgeSourceConfig?>(null) }

    val currentCollection = viewModel.currentCollection

    LaunchedEffect(collectionId, viewModel.embeddingModelConfigured, currentCollection) {
        if (viewModel.embeddingModelConfigured && currentCollection != null) {
            EventBus.post(
                IndexingRequestedEvent(
                    containerId = collectionId,
                    containerType = IndexingContainerType.RESOURCE_COLLECTION,
                    knowledgeSources = null,
                    watchForChanges = false,
                ),
            )
            log.debug("Indexing requested for resource collection $collectionId")
        }
    }

    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // NOTE: this section intentionally wraps its actual content height (no
            // weight, no fillMaxHeight overlay) so it never eats into the space the
            // weighted tree section below needs. Mouse-wheel/trackpad scrolling still
            // works via verticalScroll even without a visible scrollbar affordance.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(start = Spacing.extraLarge, end = Spacing.extraLarge, top = Spacing.extraLarge, bottom = Spacing.small),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = Spacing.small),
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource("action.back"),
                                tint = AppTextStyles.secondaryContent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = stringResource("resourcecollections.title"),
                                style = AppTextStyles.caption,
                            )
                        }
                    }

                    if (currentCollection != null) {
                        collectionHeroCard(
                            currentCollection = currentCollection,
                            showCollectionMenu = showCollectionMenu,
                            onMenuStateChange = { showCollectionMenu = it },
                            onEditClick = { showEditDialog = true },
                            onDeleteClick = { showDeleteDialog = true },
                            onReindexClick = {
                                if (!viewModel.embeddingModelConfigured) {
                                    // No-op: banner above already prompts the user to
                                    // configure an embedding model first.
                                } else if (viewModel.indexProgress.status == IndexStatus.INDEXING) {
                                    showReIndexConfirmDialog = true
                                } else {
                                    EventBus.post(
                                        ReIndexEvent(
                                            containerId = currentCollection.id,
                                            containerType = IndexingContainerType.RESOURCE_COLLECTION,
                                            reason = "Manual re-index requested by user from collection menu",
                                        ),
                                    )
                                }
                            },
                        )

                        // Knowledge Sources Panel — shared collapsible component (see
                        // io.askimo.desktop.knowledgesource.knowledgeSourcesPanel), also used by
                        // the Project view.
                        knowledgeSourcesPanel(
                            key = currentCollection.id,
                            knowledgeSources = currentCollection.knowledgeSources,
                            indexProgress = viewModel.indexProgress,
                            onShowAddDialog = { showAddReferenceMaterialDialog = true },
                            modifier = Modifier.padding(bottom = Spacing.medium),
                        ) { source ->
                            knowledgeSourceItemForCollection(
                                source = source,
                                isSelected = selectedSourceForTree?.resourceIdentifier == source.resourceIdentifier,
                                onSelect = {
                                    selectedSourceForTree = if (selectedSourceForTree?.resourceIdentifier == source.resourceIdentifier) {
                                        null
                                    } else {
                                        source
                                    }
                                },
                                onDelete = {
                                    if (selectedSourceForTree?.resourceIdentifier == source.resourceIdentifier) {
                                        selectedSourceForTree = null
                                    }
                                    viewModel.deleteKnowledgeSource(source)
                                },
                                onWatchToggle = { watch ->
                                    if (source is LocalFoldersKnowledgeSourceConfig) {
                                        viewModel.toggleWatchForChanges(source, watch)
                                    }
                                },
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            if (viewModel.isLoading) {
                                AppComponents.loadingSpinner()
                            } else if (viewModel.errorMessage != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                    Text(
                                        stringResource("resourcecollections.error", viewModel.errorMessage ?: ""),
                                        style = AppTextStyles.body,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(onClick = {
                                        viewModel.clearError()
                                        viewModel.loadCollection()
                                    }) {
                                        Text(stringResource("action.retry"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Separate content area: shows the RAG source tree (folder/file structure +
            // per-file indexed status) for the clicked knowledge source. Fills the
            // remaining vertical space of the view (rather than a fixed height) and
            // relies on ragSourcesTree's own internal scrollbar. Reuses the shared
            // ragSourcesTree component so this stays consistent with the Project view
            // without coupling the two packages.
            selectedSourceForTree?.let { source ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Card(
                        modifier = Modifier
                            .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(start = Spacing.extraLarge, end = Spacing.extraLarge, bottom = Spacing.large),
                        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(Spacing.large)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = source.resourceIdentifier,
                                    style = AppTextStyles.fieldLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { selectedSourceForTree = null },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource("action.close"))
                                }
                            }
                            ragSourcesTreeWithPreview(
                                sources = listOf(source),
                                indexedPaths = viewModel.indexedPaths,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                onRemove = {
                                    selectedSourceForTree = null
                                    viewModel.deleteKnowledgeSource(it)
                                },
                                onWatchToggle = { updatedSource, watch ->
                                    viewModel.toggleWatchForChanges(updatedSource, watch)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog && currentCollection != null) {
        deleteResourceCollectionDialog(
            collectionName = currentCollection.name,
            onConfirm = {
                showDeleteDialog = false
                onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showEditDialog && currentCollection != null) {
        editResourceCollectionDialog(
            collection = currentCollection,
            onDismiss = { showEditDialog = false },
            onSave = { _, name, description, knowledgeSources ->
                viewModel.updateCollection(name, description, knowledgeSources)
                showEditDialog = false
            },
        )
    }

    // Add reference material dialog — mirrors ProjectView's equivalent, but persistence
    // and indexing are delegated to the ViewModel instead of duplicating the logic here.
    if (showAddReferenceMaterialDialog && currentCollection != null) {
        addReferenceMaterialDialog(
            onDismiss = { showAddReferenceMaterialDialog = false },
            onAdd = { newSources ->
                viewModel.addKnowledgeSources(buildKnowledgeSourceConfigs(newSources))
            },
        )
    }

    // Re-index confirmation dialog (shown when user requests re-index while the collection is
    // actively indexing)
    if (showReIndexConfirmDialog && currentCollection != null) {
        reIndexConfirmDialog(
            projectName = currentCollection.name,
            onConfirm = {
                showReIndexConfirmDialog = false
                EventBus.post(
                    ReIndexEvent(
                        containerId = currentCollection.id,
                        containerType = IndexingContainerType.RESOURCE_COLLECTION,
                        reason = "Manual re-index confirmed by user from collection menu",
                    ),
                )
            },
            onDismiss = { showReIndexConfirmDialog = false },
        )
    }
}

@Composable
private fun collectionHeroCard(
    currentCollection: ResourceCollection,
    showCollectionMenu: Boolean,
    onMenuStateChange: (Boolean) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onReindexClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.large),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.spacedBy(Spacing.large),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = currentCollection.name.firstOrNull()?.uppercaseChar()?.toString() ?: "C",
                    style = AppTextStyles.pageTitle,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            SelectionContainer(modifier = Modifier.weight(1f)) {
                Column {
                    Text(
                        text = currentCollection.name,
                        style = AppTextStyles.pageTitle,
                    )
                    currentCollection.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            text = desc,
                            style = AppTextStyles.bodySecondary,
                            modifier = Modifier.padding(top = Spacing.extraSmall),
                        )
                    }
                }
            }

            Box {
                themedTooltip(text = stringResource("resourcecollection.menu.tooltip")) {
                    IconButton(
                        onClick = { onMenuStateChange(true) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource("resourcecollection.menu.tooltip"),
                            tint = AppTextStyles.primaryContent,
                        )
                    }
                }
                AppComponents.dropdownMenu(
                    expanded = showCollectionMenu,
                    onDismissRequest = { onMenuStateChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource("action.edit")) },
                        onClick = {
                            onMenuStateChange(false)
                            onEditClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource("resourcecollection.reindex")) },
                        onClick = {
                            onMenuStateChange(false)
                            onReindexClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            onMenuStateChange(false)
                            onDeleteClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        }
    }
}

@Composable
private fun knowledgeSourceItemForCollection(
    source: KnowledgeSourceConfig,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onDelete: () -> Unit = {},
    onWatchToggle: (Boolean) -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) AppColors.surfaceColor(AppColors.Elevation.SELECTED) else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onSelect)
            .padding(vertical = Spacing.micro, horizontal = Spacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "•", style = AppTextStyles.caption)
                Text(
                    text = source.resourceIdentifier,
                    style = AppTextStyles.caption,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only folders support watch-for-changes.
            if (source is LocalFoldersKnowledgeSourceConfig) {
                themedTooltip(text = stringResource("projects.sources.watch.tooltip")) {
                    Checkbox(
                        checked = source.watchForChanges,
                        onCheckedChange = onWatchToggle,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }

            Box {
                themedTooltip(text = stringResource("projects.sources.more.tooltip")) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource("projects.sources.more.tooltip"),
                            tint = AppTextStyles.primaryContent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                AppComponents.dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        }
    }
}
