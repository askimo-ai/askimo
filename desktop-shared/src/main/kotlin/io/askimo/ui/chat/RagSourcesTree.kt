/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFilesKnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import io.askimo.core.logging.currentFileLogger
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

private val log = currentFileLogger()

/** Max results shown in search mode to keep rendering fast. */
private const val SEARCH_RESULTS_CAP = 200

/**
 * Tree view component for displaying RAG knowledge sources.
 * Shows files and folders with expandable/collapsible functionality.
 * In search mode, a pre-built flat index is filtered (debounced 300 ms, capped at 200 results).
 *
 * When [onAddToChat] is provided, each file/folder row gains a "+" quick-add button,
 * a context-menu "Add to chat" option, and a sticky bottom bar for multi-file selection.
 *
 * @param sources          Knowledge source configs to display.
 * @param modifier         Optional modifier.
 * @param selectedNode     Currently selected node (hoisted to allow viewer integration).
 * @param onNodeSelected   Called when a node is selected; passes `null` to deselect.
 * @param onRemove         Called when the user removes a knowledge source.
 * @param onAddToChat      Called with a list of file paths to attach to the current chat message.
 *                         Pass `null` to disable the feature.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ragSourcesTree(
    sources: List<KnowledgeSourceConfig>,
    modifier: Modifier = Modifier,
    selectedNode: TreeNode? = null,
    onNodeSelected: (TreeNode?) -> Unit = {},
    onRemove: (KnowledgeSourceConfig) -> Unit = {},
    onAddToChat: ((List<String>) -> Unit)? = null,
) {
    val chatSelection = remember { mutableStateSetOf<String>() }

    // ── Folder expanded state — survives search mode switches ──────────────
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }

    // ── Flat file index for search ─────────────────────────────────────────
    // Built once per sources change on Dispatchers.IO — never blocks the UI thread.
    var flatIndex by remember { mutableStateOf<List<String>?>(null) } // null = building
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<String>>(emptyList()) }

    // Build the flat index in the background whenever sources change
    LaunchedEffect(sources) {
        flatIndex = null // reset to show loading if needed
        flatIndex = withContext(Dispatchers.IO) {
            buildFlatIndex(sources)
        }
    }

    // Debounced search: 300 ms delay, then filter the flat index
    LaunchedEffect(searchQuery, flatIndex) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300.milliseconds)
        val index = flatIndex ?: return@LaunchedEffect
        searchResults = withContext(Dispatchers.Default) {
            index.filter { path ->
                File(path).name.contains(query, ignoreCase = true)
            }.take(SEARCH_RESULTS_CAP)
        }
    }

    // Convert sources to tree nodes (normal tree mode)
    val treeNodes = remember(sources) {
        sources.map { source ->
            when (source) {
                is LocalFoldersKnowledgeSourceConfig -> FolderTreeNode(path = source.resourceIdentifier, source = source)
                is LocalFilesKnowledgeSourceConfig -> FileTreeNode(path = source.resourceIdentifier, source = source)
                is UrlKnowledgeSourceConfig -> UrlTreeNode(url = source.resourceIdentifier, source = source)
            }
        }
    }

    val listState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val isSearchActive = searchQuery.isNotEmpty()

    Column(modifier = modifier) {
        // ── Search bar ─────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = {
                Text(
                    text = stringResource("rag.tree.search.placeholder"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            },
            trailingIcon = {
                if (isSearchActive) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource("rag.tree.search.clear"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )

        // ── Content area ───────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            if (isSearchActive) {
                // Search results view
                when {
                    flatIndex == null -> {
                        // Index is still building
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    text = stringResource("rag.tree.search.indexing"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    searchResults.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource("rag.tree.search.no.results"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            state = searchListState,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 22.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            // Result count header
                            item {
                                val capped = searchResults.size >= SEARCH_RESULTS_CAP
                                Text(
                                    text = if (capped) {
                                        stringResource("rag.tree.search.results.capped", SEARCH_RESULTS_CAP)
                                    } else {
                                        stringResource("rag.tree.search.results.count", searchResults.size)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            items(searchResults, key = { it }) { path ->
                                searchResultItem(
                                    path = path,
                                    query = searchQuery.trim(),
                                    isSelected = selectedNode?.fullPath == path,
                                    isInChatSelection = path in chatSelection,
                                    onSelect = { node -> onNodeSelected(node) },
                                    onAddToSelection = { chatSelection.add(path) },
                                    onRemoveFromSelection = { chatSelection.remove(path) },
                                    onAddToChat = onAddToChat,
                                )
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(searchListState),
                            style = ScrollbarStyle(
                                minimalHeight = 16.dp,
                                thickness = 6.dp,
                                shape = MaterialTheme.shapes.small,
                                hoverDurationMillis = 300,
                                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            ),
                        )
                    }
                }
            } else {
                // Normal tree view
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(treeNodes) { node ->
                        treeNodeItem(
                            node = node,
                            level = 0,
                            selectedNode = selectedNode,
                            onNodeSelected = onNodeSelected,
                            onRemove = onRemove,
                            chatSelection = chatSelection,
                            onAddToChat = onAddToChat,
                            expandedPaths = expandedFolders,
                        )
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(listState),
                    style = ScrollbarStyle(
                        minimalHeight = 16.dp,
                        thickness = 6.dp,
                        shape = MaterialTheme.shapes.small,
                        hoverDurationMillis = 300,
                        unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    ),
                )
            }
        }

        // Sticky bottom action bar — only shown when files are selected for chat
        if (onAddToChat != null && chatSelection.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                    Text(text = stringResource("rag.tree.chat.selected", chatSelection.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { chatSelection.clear() }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text(text = stringResource("rag.tree.chat.clear"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Button(onClick = {
                        onAddToChat(chatSelection.toList())
                        chatSelection.clear()
                    }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(imageVector = Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(text = stringResource("rag.tree.chat.add"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

/**
 * A single flat search result row — shows filename bold + full path dimmed below.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun searchResultItem(
    path: String,
    query: String,
    isSelected: Boolean,
    isInChatSelection: Boolean,
    onSelect: (TreeNode) -> Unit,
    onAddToSelection: () -> Unit,
    onRemoveFromSelection: () -> Unit,
    onAddToChat: ((List<String>) -> Unit)?,
) {
    val file = File(path)
    val isFile = file.isFile
    val node: TreeNode = if (isFile) {
        FileTreeNode(path = path, source = LocalFilesKnowledgeSourceConfig(resourceIdentifier = path))
    } else {
        FolderTreeNode(path = path, source = LocalFoldersKnowledgeSourceConfig(resourceIdentifier = path))
    }

    val backgroundColor = when {
        isInChatSelection -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    themedTooltip(text = path) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor, RoundedCornerShape(4.dp))
                .onClick(
                    matcher = PointerMatcher.mouse(PointerButton.Primary),
                    onClick = { onSelect(node) },
                    onDoubleClick = { if (isFile) openInFileBrowser(path) else openInFileBrowser(path) },
                )
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .pointerHoverIcon(PointerIcon.Hand),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isFile) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.Folder,
                contentDescription = null,
                tint = AppComponents.secondaryIconColor(),
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                // Highlight matching portion in filename
                val fileName = file.name
                val matchStart = fileName.indexOf(query, ignoreCase = true)
                if (matchStart >= 0) {
                    androidx.compose.foundation.text.BasicText(
                        text = buildAnnotatedString {
                            append(fileName.substring(0, matchStart))
                            withStyle(SpanStyle(background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), fontWeight = FontWeight.SemiBold)) {
                                append(fileName.substring(matchStart, matchStart + query.length))
                            }
                            append(fileName.substring(matchStart + query.length))
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(text = fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = file.parent ?: path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Add to chat button (files only)
            if (onAddToChat != null && isFile) {
                if (isInChatSelection) {
                    IconButton(onClick = onRemoveFromSelection, modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = stringResource("rag.tree.chat.deselect"), tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                    }
                } else {
                    IconButton(onClick = onAddToSelection, modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(imageVector = Icons.Default.AddCircleOutline, contentDescription = stringResource("rag.tree.chat.select"), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Builds a flat list of all file (and folder) paths from the given knowledge sources.
 * Folders are walked recursively. Must be called on a background thread.
 */
private fun buildFlatIndex(sources: List<KnowledgeSourceConfig>): List<String> {
    val result = mutableListOf<String>()
    for (source in sources) {
        when (source) {
            is LocalFoldersKnowledgeSourceConfig -> walkDirectory(File(source.resourceIdentifier), result)
            is LocalFilesKnowledgeSourceConfig -> result.add(source.resourceIdentifier)
            is UrlKnowledgeSourceConfig -> result.add(source.resourceIdentifier)
        }
    }
    return result
}

/** Recursive walk — adds files and sub-folders to [out]. */
private fun walkDirectory(dir: File, out: MutableList<String>) {
    if (!dir.exists() || !dir.isDirectory) return
    val entries = dir.listFiles() ?: return
    for (entry in entries) {
        out.add(entry.absolutePath)
        if (entry.isDirectory) walkDirectory(entry, out)
    }
}

// ── Tree node classes ──────────────────────────────────────────────────────

sealed class TreeNode {
    abstract val displayName: String
    abstract val fullPath: String
}

data class FolderTreeNode(
    val path: String,
    val source: LocalFoldersKnowledgeSourceConfig,
    val children: List<TreeNode> = emptyList(),
) : TreeNode() {
    override val displayName: String = File(path).name.ifEmpty { path }
    override val fullPath: String = path
}

data class FileTreeNode(
    val path: String,
    val source: LocalFilesKnowledgeSourceConfig,
) : TreeNode() {
    override val displayName: String = File(path).name.ifEmpty { path }
    override val fullPath: String = path
}

data class UrlTreeNode(
    val url: String,
    val source: UrlKnowledgeSourceConfig,
) : TreeNode() {
    override val displayName: String = url
    override val fullPath: String = url
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun treeNodeItem(
    node: TreeNode,
    level: Int,
    selectedNode: TreeNode?,
    onNodeSelected: (TreeNode) -> Unit,
    onRemove: (KnowledgeSourceConfig) -> Unit,
    chatSelection: SnapshotStateSet<String> = remember { mutableStateSetOf() },
    onAddToChat: ((List<String>) -> Unit)? = null,
    expandedPaths: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() },
    modifier: Modifier = Modifier,
) {
    when (node) {
        is FolderTreeNode -> folderNodeItem(node, level, selectedNode, onNodeSelected, onRemove, chatSelection, onAddToChat, expandedPaths, modifier)
        is FileTreeNode -> fileNodeItem(node, level, selectedNode, onNodeSelected, onRemove, chatSelection, onAddToChat, modifier)
        is UrlTreeNode -> urlNodeItem(node, level, selectedNode, onNodeSelected, onRemove, modifier)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun folderNodeItem(
    node: FolderTreeNode,
    level: Int,
    selectedNode: TreeNode?,
    onNodeSelected: (TreeNode) -> Unit,
    onRemove: (KnowledgeSourceConfig) -> Unit,
    chatSelection: SnapshotStateSet<String> = remember { mutableStateSetOf() },
    onAddToChat: ((List<String>) -> Unit)? = null,
    expandedPaths: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() },
    modifier: Modifier = Modifier,
) {
    val isExpanded = expandedPaths[node.path] ?: false
    var showContextMenu by remember { mutableStateOf(false) }

    val children = remember(node.path, isExpanded) {
        if (isExpanded) loadFolderChildren(node.path) else emptyList()
    }

    val isSelected = selectedNode == node
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent

    Column(modifier = modifier) {
        themedTooltip(text = node.fullPath) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .onClick(matcher = PointerMatcher.mouse(PointerButton.Primary), onClick = {
                            expandedPaths[node.path] = !isExpanded
                            onNodeSelected(node)
                        })
                        .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = { showContextMenu = true })
                        .padding(start = (level * 16).dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) stringResource("rag.tree.collapse") else stringResource("rag.tree.expand"),
                        tint = AppComponents.secondaryIconColor(),
                        modifier = Modifier.size(16.dp),
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        tint = AppComponents.secondaryIconColor(),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = node.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }, offset = DpOffset(x = 0.dp, y = 0.dp)) {
                    DropdownMenuItem(text = { Text(stringResource("rag.tree.folder.open")) }, onClick = {
                        openInFileBrowser(node.path)
                        showContextMenu = false
                    }, leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) })
                    DropdownMenuItem(text = { Text(stringResource("rag.tree.folder.copy.path")) }, onClick = {
                        copyToClipboard(node.path)
                        showContextMenu = false
                    }, leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) })
                    DropdownMenuItem(text = { Text(stringResource("rag.tree.remove")) }, onClick = {
                        onRemove(node.source)
                        showContextMenu = false
                    }, leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null) })
                }
            }
        }
        if (isExpanded && children.isNotEmpty()) {
            Column {
                children.forEach { child ->
                    treeNodeItem(node = child, level = level + 1, selectedNode = selectedNode, onNodeSelected = onNodeSelected, onRemove = onRemove, chatSelection = chatSelection, onAddToChat = onAddToChat, expandedPaths = expandedPaths)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun fileNodeItem(
    node: FileTreeNode,
    level: Int,
    selectedNode: TreeNode?,
    onNodeSelected: (TreeNode) -> Unit,
    onRemove: (KnowledgeSourceConfig) -> Unit,
    chatSelection: SnapshotStateSet<String> = remember { mutableStateSetOf() },
    onAddToChat: ((List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val isSelected = selectedNode == node
    val isInChatSelection = node.path in chatSelection
    val backgroundColor = when {
        isInChatSelection -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    themedTooltip(text = node.fullPath) {
        Box {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(backgroundColor, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .onClick(matcher = PointerMatcher.mouse(PointerButton.Primary), onClick = { onNodeSelected(node) }, onDoubleClick = { openInFileBrowser(node.path) })
                    .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = { showContextMenu = true })
                    .padding(start = (level * 16 + 16).dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = AppComponents.secondaryIconColor(), modifier = Modifier.size(18.dp))
                Text(text = node.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onAddToChat != null) {
                    if (isInChatSelection) {
                        IconButton(onClick = { chatSelection.remove(node.path) }, modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = stringResource("rag.tree.chat.deselect"), tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        IconButton(onClick = { chatSelection.add(node.path) }, modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(imageVector = Icons.Default.AddCircleOutline, contentDescription = stringResource("rag.tree.chat.select"), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }, offset = DpOffset(x = 0.dp, y = 0.dp)) {
                if (onAddToChat != null) {
                    if (isInChatSelection) {
                        DropdownMenuItem(text = { Text(stringResource("rag.tree.chat.deselect")) }, onClick = {
                            chatSelection.remove(node.path)
                            showContextMenu = false
                        }, leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource("rag.tree.chat.select")) }, onClick = {
                            chatSelection.add(node.path)
                            showContextMenu = false
                        }, leadingIcon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null) })
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text(stringResource("rag.tree.file.preview")) }, onClick = {
                    onNodeSelected(node)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) })
                DropdownMenuItem(text = { Text(stringResource("rag.tree.file.open")) }, onClick = {
                    openInFileBrowser(node.path)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) })
                DropdownMenuItem(text = { Text(stringResource("rag.tree.file.open.folder")) }, onClick = {
                    openContainingFolder(node.path)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) })
                DropdownMenuItem(text = { Text(stringResource("rag.tree.file.copy.path")) }, onClick = {
                    copyToClipboard(node.path)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) })
                DropdownMenuItem(text = { Text(stringResource("rag.tree.remove")) }, onClick = {
                    onRemove(node.source)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun urlNodeItem(
    node: UrlTreeNode,
    level: Int,
    selectedNode: TreeNode?,
    onNodeSelected: (TreeNode) -> Unit,
    onRemove: (KnowledgeSourceConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val isSelected = selectedNode == node
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent

    themedTooltip(text = node.fullPath) {
        Box {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(backgroundColor, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .onClick(matcher = PointerMatcher.mouse(PointerButton.Primary), onClick = { onNodeSelected(node) }, onDoubleClick = { openInBrowser(node.url) })
                    .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = { showContextMenu = true })
                    .padding(start = (level * 16).dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Default.Language, contentDescription = null, tint = AppComponents.secondaryIconColor(), modifier = Modifier.size(18.dp))
                Text(text = node.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }, offset = DpOffset(x = 0.dp, y = 0.dp)) {
                DropdownMenuItem(text = { Text(stringResource("rag.tree.url.open")) }, onClick = {
                    openInBrowser(node.url)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) })
                DropdownMenuItem(text = { Text(stringResource("rag.tree.url.copy")) }, onClick = {
                    copyToClipboard(node.url)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) })
                DropdownMenuItem(text = { Text(stringResource("rag.tree.remove")) }, onClick = {
                    onRemove(node.source)
                    showContextMenu = false
                }, leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null) })
            }
        }
    }
}

private fun loadFolderChildren(folderPath: String): List<TreeNode> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()
    val files = folder.listFiles() ?: return emptyList()
    val sorted = files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
    return sorted.map { file ->
        if (file.isDirectory) {
            FolderTreeNode(path = file.absolutePath, source = LocalFoldersKnowledgeSourceConfig(resourceIdentifier = file.absolutePath))
        } else {
            FileTreeNode(path = file.absolutePath, source = LocalFilesKnowledgeSourceConfig(resourceIdentifier = file.absolutePath))
        }
    }
}

private fun openInFileBrowser(path: String) {
    try {
        val file = File(path)
        if (file.exists() && Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    } catch (e: Exception) {
        log.warn("Failed to open file in browser: {}", path, e)
    }
}

private fun openContainingFolder(filePath: String) {
    try {
        val parentFolder = File(filePath).parentFile
        if (parentFolder?.exists() == true && Desktop.isDesktopSupported()) Desktop.getDesktop().open(parentFolder)
    } catch (e: Exception) {
        log.warn("Failed to open containing folder: {}", filePath, e)
    }
}

private fun openInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(URI(url))
    } catch (e: Exception) {
        log.warn("Failed to open URL in browser: {}", url, e)
    }
}

private fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (e: Exception) {
        log.warn("Failed to copy to clipboard", e)
    }
}
