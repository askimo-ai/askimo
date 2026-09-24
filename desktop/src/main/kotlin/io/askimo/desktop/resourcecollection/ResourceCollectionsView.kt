/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.resourcecollection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.chat.repository.CollectionSortColumn
import io.askimo.core.chat.repository.CollectionSortDirection
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.embeddingModelNotConfiguredBanner
import io.askimo.ui.common.components.indexStatusIcon
import io.askimo.ui.common.components.indexStatusLabel
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.tablePageSizeSelector
import io.askimo.ui.common.components.tablePagination
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip

/**
 * Lists resource collections with search, sortable columns (Created/Modified),
 * and management actions (edit/delete/reindex). Shows an empty state with a
 * "create" button when no collections exist.
 */
@Composable
fun resourceCollectionsView(
    viewModel: ResourceCollectionsViewModel,
    onSelectCollection: (String) -> Unit = {},
    onNavigateToAiProviderSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current
    var pageSize by remember { mutableStateOf(10) }
    var showNewCollectionDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
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
                    .padding(start = Spacing.extraLarge, end = Spacing.scrollbarGutter, top = Spacing.extraLarge, bottom = Spacing.extraLarge),
            ) {
                // ── Title + New Collection button ──────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.LibraryBooks,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource("resourcecollections.title"),
                            style = AppTextStyles.pageTitle,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Button(
                        onClick = { showNewCollectionDialog = true },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(stringResource("resourcecollection.create"))
                    }
                }

                Text(
                    text = stringResource("resourcecollections.description"),
                    style = AppTextStyles.bodySecondary,
                    modifier = Modifier.padding(top = Spacing.extraSmall, bottom = Spacing.extraSmall),
                )

                linkButton(onClick = { uriHandler.openUri("https://$DOMAIN/docs/desktop/rag/") }) {
                    Text(
                        text = stringResource("rag.learn.more"),
                        style = AppTextStyles.caption,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (!viewModel.embeddingModelConfigured && onNavigateToAiProviderSettings != null) {
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    embeddingModelNotConfiguredBanner(
                        providerSupportsEmbedding = viewModel.embeddingSupportedByProvider,
                        onConfigureClick = onNavigateToAiProviderSettings,
                        notConfiguredMessageKey = "resourcecollections.rag.embedding.not.configured",
                        unsupportedProviderMessageKey = "resourcecollections.rag.embedding.unsupported.provider",
                        configureActionKey = "resourcecollections.rag.embedding.configure",
                        switchProviderActionKey = "resourcecollections.rag.embedding.switch.provider",
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.large))

                // ── Search ────────────────────────────────────────────────────────
                OutlinedTextField(
                    value = viewModel.searchQuery,
                    onValueChange = { viewModel.updateSearch(it) },
                    placeholder = {
                        Text(
                            text = stringResource("resourcecollections.search.placeholder"),
                            style = AppTextStyles.body,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (viewModel.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearSearch() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = AppTextStyles.body,
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedTextFieldColors(),
                )

                Spacer(modifier = Modifier.height(Spacing.large))

                // ── Loading / error / empty ───────────────────────────────────────
                val pagedCollectionsSnapshot = viewModel.pagedCollections
                when {
                    viewModel.isLoading && pagedCollectionsSnapshot == null -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            AppComponents.loadingSpinner()
                        }
                    }

                    viewModel.errorMessage != null -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                Text(
                                    stringResource("resourcecollections.error", viewModel.errorMessage ?: ""),
                                    style = AppTextStyles.body,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = {
                                    viewModel.clearError()
                                    viewModel.refresh()
                                }) {
                                    Text(stringResource("action.retry"))
                                }
                            }
                        }
                    }

                    pagedCollectionsSnapshot == null || pagedCollectionsSnapshot.isEmpty -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                if (viewModel.searchQuery.isNotBlank()) {
                                    Text(
                                        stringResource("resourcecollections.search.empty", viewModel.searchQuery),
                                        style = AppTextStyles.bodySecondary,
                                    )
                                } else {
                                    Text(stringResource("resourcecollections.empty"), style = AppTextStyles.bodySecondary)
                                    Text(stringResource("resourcecollections.empty.hint"), style = AppTextStyles.bodySecondary)
                                }
                            }
                        }
                    }

                    else -> {
                        collectionTable(
                            collections = pagedCollectionsSnapshot.items,
                            onSelectCollection = onSelectCollection,
                            onDeleteCollection = { viewModel.deleteCollection(it) },
                            onUpdateCollection = { id, name, description, knowledgeSources ->
                                viewModel.updateCollection(id, name, description, knowledgeSources)
                            },
                            onReindexCollection = {
                                if (viewModel.embeddingModelConfigured) {
                                    viewModel.reindexCollection(it)
                                }
                            },
                            embeddingModelConfigured = viewModel.embeddingModelConfigured,
                            sortColumn = viewModel.sortColumn,
                            sortDirection = viewModel.sortDirection,
                            onSortChange = { col -> viewModel.setSort(col) },
                        )
                    }
                }

                // ── Pagination ────────────────────────────────────────────────────
                val pagedCollections = viewModel.pagedCollections
                if (pagedCollections != null && pagedCollections.totalPages > 1) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    tablePagination(
                        currentPage = pagedCollections.currentPage,
                        totalPages = pagedCollections.totalPages,
                        hasPrevious = pagedCollections.hasPreviousPage,
                        hasNext = pagedCollections.hasNextPage,
                        pageSize = pageSize,
                        onPrevious = { viewModel.previousPage() },
                        onNext = { viewModel.nextPage() },
                        onPageSizeChange = { size ->
                            pageSize = size
                            viewModel.setPageSize(size)
                        },
                    )
                } else if (viewModel.pagedCollections != null) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    tablePageSizeSelector(
                        pageSize = pageSize,
                        onPageSizeChange = { size ->
                            pageSize = size
                            viewModel.setPageSize(size)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = AppComponents.scrollbarStyle(),
        )
    }

    // New collection dialog
    if (showNewCollectionDialog) {
        newResourceCollectionDialog(
            onDismiss = { showNewCollectionDialog = false },
            onCreateCollection = { _, _ ->
                viewModel.refresh()
                showNewCollectionDialog = false
            },
        )
    }
}

// ── Table ─────────────────────────────────────────────────────────────────────

@Composable
private fun collectionTable(
    collections: List<ResourceCollection>,
    onSelectCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onUpdateCollection: suspend (String, String, String?, List<KnowledgeSourceConfig>) -> Boolean,
    onReindexCollection: (String) -> Unit,
    embeddingModelConfigured: Boolean,
    sortColumn: CollectionSortColumn,
    sortDirection: CollectionSortDirection,
    onSortChange: (CollectionSortColumn) -> Unit,
) {
    // Note: `collections` arrives already sorted from the repository (sort is applied at
    // the query level, before pagination) — see [ResourceCollectionsViewModel.setSort].
    // No client-side re-sort here since that would only reorder the current page's items
    // and desync from the DB-chosen page boundaries.

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.surfaceColor(AppColors.Elevation.RAISED))
                    .padding(horizontal = Spacing.large, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Name column (non-sortable)
                Text(
                    text = stringResource("resourcecollections.col.name"),
                    style = AppTextStyles.fieldLabel,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Created sortable header
                collectionSortableHeader(
                    label = stringResource("resourcecollections.col.created"),
                    column = CollectionSortColumn.CREATED,
                    currentColumn = sortColumn,
                    direction = sortDirection,
                    onClick = { onSortChange(CollectionSortColumn.CREATED) },
                    modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = Spacing.large),
                )
                // Modified sortable header
                collectionSortableHeader(
                    label = stringResource("resourcecollections.col.modified"),
                    column = CollectionSortColumn.MODIFIED,
                    currentColumn = sortColumn,
                    direction = sortDirection,
                    onClick = { onSortChange(CollectionSortColumn.MODIFIED) },
                    modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = Spacing.large),
                )
                // Status column (non-sortable)
                Text(
                    text = stringResource("resourcecollections.col.status"),
                    style = AppTextStyles.fieldLabel,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 110.dp).padding(horizontal = Spacing.large),
                )
                // Actions column spacer
                Spacer(modifier = Modifier.size(36.dp))
            }

            HorizontalDivider()

            collections.forEachIndexed { index, collection ->
                collectionRow(
                    collection = collection,
                    onSelectCollection = { onSelectCollection(collection.id) },
                    onDeleteCollection = onDeleteCollection,
                    onUpdateCollection = onUpdateCollection,
                    onReindexCollection = onReindexCollection,
                    embeddingModelConfigured = embeddingModelConfigured,
                )
                if (index < collections.lastIndex) {
                    HorizontalDivider(color = AppColors.codeBlockBorderColor())
                }
            }
        }
    }
}

@Composable
private fun collectionSortableHeader(
    label: String,
    column: CollectionSortColumn,
    currentColumn: CollectionSortColumn,
    direction: CollectionSortDirection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = currentColumn == column
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            text = label,
            style = AppTextStyles.fieldLabel,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isActive) {
            Icon(
                imageVector = if (direction == CollectionSortDirection.DESC) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun collectionRow(
    collection: ResourceCollection,
    onSelectCollection: () -> Unit,
    onDeleteCollection: (String) -> Unit,
    onUpdateCollection: suspend (String, String, String?, List<KnowledgeSourceConfig>) -> Boolean,
    onReindexCollection: (String) -> Unit,
    embeddingModelConfigured: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    if (showDeleteDialog) {
        deleteResourceCollectionDialog(
            collectionName = collection.name,
            onConfirm = {
                showDeleteDialog = false
                onDeleteCollection(collection.id)
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showEditDialog) {
        editResourceCollectionDialog(
            collection = collection,
            onDismiss = { showEditDialog = false },
            onSave = { id, name, description, knowledgeSources ->
                val saved = onUpdateCollection(id, name, description, knowledgeSources)
                if (saved) showEditDialog = false
                saved
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clickable { onSelectCollection() }
            .background(
                if (isHovered) {
                    AppColors.surfaceColor(AppColors.Elevation.RAISED)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = Spacing.large, vertical = Spacing.small)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + description
        Column(modifier = Modifier.weight(1f)) {
            themedTooltip(text = collection.name) {
                Text(
                    text = collection.name,
                    style = AppTextStyles.body,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            collection.description?.let { desc ->
                Text(
                    text = desc,
                    style = AppTextStyles.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Created date
        Text(
            text = TimeUtil.formatDisplay(collection.createdAt),
            style = AppTextStyles.caption,
            modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = Spacing.large),
        )

        // Modified date
        Text(
            text = TimeUtil.formatDisplay(collection.updatedAt),
            style = AppTextStyles.caption,
            modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = Spacing.large),
        )

        // Status
        Row(
            modifier = Modifier.widthIn(min = 110.dp).padding(horizontal = Spacing.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            themedTooltip(text = indexStatusLabel(collection.indexStatus)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    indexStatusIcon(status = collection.indexStatus)
                    Text(
                        text = indexStatusLabel(collection.indexStatus),
                        style = AppTextStyles.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // On-demand indexing: NOT_STARTED means the collection was never indexed
            // (auto-index only fires when sources exist at creation time); FAILED means
            // it errored out or was interrupted (e.g. app closed mid-index — see
            // RagIndexer.recoverInterruptedIndexingStatuses). Either way, the user
            // explicitly decides when to (re)start indexing rather than it happening silently.
            if (collection.indexStatus == IndexStatus.NOT_STARTED || collection.indexStatus == IndexStatus.FAILED) {
                themedTooltip(text = stringResource("resourcecollection.reindex")) {
                    IconButton(
                        onClick = { onReindexCollection(collection.id) },
                        enabled = embeddingModelConfigured,
                        modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource("resourcecollection.reindex"),
                            modifier = Modifier.size(14.dp),
                            tint = if (embeddingModelConfigured) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                AppColors.disabledContentColor()
                            },
                        )
                    }
                }
            }
        }

        // Actions
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            AppComponents.dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource("action.edit")) },
                    onClick = {
                        showMenu = false
                        showEditDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("resourcecollection.reindex")) },
                    onClick = {
                        showMenu = false
                        onReindexCollection(collection.id)
                    },
                    enabled = embeddingModelConfigured,
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}
