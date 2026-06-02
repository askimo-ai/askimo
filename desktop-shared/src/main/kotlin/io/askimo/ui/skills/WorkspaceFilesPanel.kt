/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.ui.codeViewerBlock
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

// ── Domain model ──────────────────────────────────────────────────────────────

private sealed class WorkspaceNode {
    abstract val file: File
    abstract val displayName: String
}

private data class WorkspaceFolderNode(override val file: File) : WorkspaceNode() {
    override val displayName: String = file.name.ifEmpty { file.path }
}

private data class WorkspaceFileNode(override val file: File) : WorkspaceNode() {
    override val displayName: String = file.name
}

private data class WorkspaceRenderItem(val node: WorkspaceNode, val depth: Int)

// ── Public entry point ────────────────────────────────────────────────────────

/**
 * File tree panel for the workspace directory used during a skill run.
 * Mirrors the folder-expand/collapse UX from RagSourcesTree but is self-contained
 * with no dependency on KnowledgeSourceConfig.
 *
 * @param workDir    Root directory to display.
 * @param refreshKey Increment to force a full reload (e.g. after a new run).
 */
@Composable
internal fun workspaceFilesPanel(
    workDir: File,
    refreshKey: Int = 0,
) {
    var rootChildren by remember(workDir, refreshKey) { mutableStateOf<List<WorkspaceNode>>(emptyList()) }
    var isLoading by remember(workDir, refreshKey) { mutableStateOf(true) }
    val expandedPaths = remember(workDir) { mutableStateMapOf<String, Boolean>() }
    var selectedFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(workDir, refreshKey) {
        isLoading = true
        rootChildren = withContext(Dispatchers.IO) { loadWorkspaceChildren(workDir) }
        isLoading = false
    }

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top panel: File tree ───────────────────────────────────────────
        Column(modifier = Modifier.weight(if (selectedFile != null) 0.4f else 1f).fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    workDir.name.ifEmpty { workDir.path },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                themedTooltip(text = stringResource("skills.view.workdir.open")) {
                    IconButton(
                        onClick = { runCatching { Desktop.getDesktop().open(workDir.also { it.mkdirs() }) } },
                        modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }

                rootChildren.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource("skills.view.workspace.empty"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(16.dp),
                    )
                }

                else -> {
                    val renderItems = remember(rootChildren, expandedPaths.keys.toSet(), expandedPaths.values.toList()) {
                        buildWorkspaceRenderList(rootChildren, depth = 0, expandedPaths = expandedPaths)
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                            items(renderItems, key = { it.node.file.absolutePath }) { item ->
                                workspaceNodeRow(
                                    node = item.node,
                                    depth = item.depth,
                                    isExpanded = expandedPaths[item.node.file.absolutePath] ?: false,
                                    isSelected = selectedFile?.absolutePath == item.node.file.absolutePath && item.node is WorkspaceFileNode,
                                    onToggleExpand = { path ->
                                        expandedPaths[path] = !(expandedPaths[path] ?: false)
                                    },
                                    onSelectFile = { file ->
                                        if (file is WorkspaceFileNode) {
                                            selectedFile = file.file
                                        }
                                    },
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                            style = ScrollbarStyle(
                                minimalHeight = 16.dp,
                                thickness = 4.dp,
                                shape = MaterialTheme.shapes.small,
                                hoverDurationMillis = 300,
                                unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
        }

        // ── Bottom panel: File viewer ──────────────────────────────────────
        if (selectedFile != null) {
            Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                workspaceFileViewer(
                    file = selectedFile!!,
                    onClose = { selectedFile = null },
                )
            }
        }
    }
}

// ── Node row ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun workspaceNodeRow(
    node: WorkspaceNode,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean = false,
    onToggleExpand: (String) -> Unit,
    onSelectFile: (WorkspaceNode) -> Unit = {},
) {
    var showContextMenu by remember { mutableStateOf(false) }

    themedTooltip(text = node.file.absolutePath) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(4.dp),
                    )
                    .then(
                        when (node) {
                            is WorkspaceFolderNode -> Modifier.onClick(
                                matcher = PointerMatcher.mouse(PointerButton.Primary),
                                onClick = { onToggleExpand(node.file.absolutePath) },
                            )

                            is WorkspaceFileNode -> Modifier.onClick(
                                matcher = PointerMatcher.mouse(PointerButton.Primary),
                                onClick = { onSelectFile(node) },
                            )
                        },
                    )
                    .onClick(
                        matcher = PointerMatcher.mouse(PointerButton.Secondary),
                        onClick = { showContextMenu = true },
                    )
                    .padding(start = (depth * 14 + 8).dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (node) {
                    is WorkspaceFolderNode -> {
                        Icon(
                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = AppComponents.secondaryIconColor(),
                            modifier = Modifier.size(14.dp),
                        )
                        Icon(
                            if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                            contentDescription = null,
                            tint = AppComponents.secondaryIconColor(),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = node.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    is WorkspaceFileNode -> {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = workspaceFileIconTint(node.file.name),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = node.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = node.file.length().toWorkspaceHumanSize(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                offset = DpOffset(x = 0.dp, y = 0.dp),
            ) {
                when (node) {
                    is WorkspaceFolderNode -> {
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workdir.open")) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file) }
                                showContextMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.copy.path")) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                workspaceCopyToClipboard(node.file.absolutePath)
                                showContextMenu = false
                            },
                        )
                    }

                    is WorkspaceFileNode -> {
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.open.file")) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file) }
                                showContextMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.open.folder")) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file.parentFile) }
                                showContextMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.copy.path")) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                workspaceCopyToClipboard(node.file.absolutePath)
                                showContextMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── File viewer ───────────────────────────────────────────────────────────────

/** Loading state for the workspace file viewer. */
private sealed interface WorkspaceViewerState {
    data object Loading : WorkspaceViewerState
    data class Content(val text: String, val lineCount: Int) : WorkspaceViewerState
    data class TooLarge(val sizeKb: Long) : WorkspaceViewerState
    data object Binary : WorkspaceViewerState
    data class Error(val message: String) : WorkspaceViewerState
}

/** Maximum file size (512 KB) the viewer will load into memory. */
private const val WORKSPACE_MAX_PREVIEW_BYTES = 512 * 1024L

/** Extensions treated as binary / non-previewable. */
private val WORKSPACE_BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "zip", "tar", "gz", "bz2", "7z", "rar",
    "exe", "dll", "so", "dylib", "class", "jar",
    "mp3", "mp4", "wav", "ogg", "flac", "avi", "mov",
    "woff", "woff2", "ttf", "otf", "eot",
    "bin", "dat", "db", "sqlite",
)

@Composable
private fun workspaceFileViewer(
    file: File,
    onClose: () -> Unit,
) {
    var viewerState by remember(file.absolutePath) { mutableStateOf<WorkspaceViewerState>(WorkspaceViewerState.Loading) }

    LaunchedEffect(file.absolutePath) {
        viewerState = WorkspaceViewerState.Loading
        viewerState = withContext(Dispatchers.IO) { loadWorkspaceFileContent(file) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
    ) {
        HorizontalDivider()

        // ── Header bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File name + line count
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = AppComponents.secondaryIconColor(),
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (viewerState is WorkspaceViewerState.Content) {
                    Text(
                        text = stringResource(
                            "file.viewer.lines",
                            (viewerState as WorkspaceViewerState.Content).lineCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (viewerState is WorkspaceViewerState.Content) {
                    themedTooltip(text = stringResource("file.viewer.copy")) {
                        IconButton(
                            onClick = { copyTextToClipboard((viewerState as WorkspaceViewerState.Content).text) },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource("file.viewer.copy"),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
                themedTooltip(text = stringResource("file.viewer.close")) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource("file.viewer.close"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        when (val state = viewerState) {
            WorkspaceViewerState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource("file.viewer.loading"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is WorkspaceViewerState.Content -> {
                val language = file.extension.lowercase().takeIf { it.isNotEmpty() }
                val vScrollState = rememberScrollState()
                val hScrollState = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vScrollState)
                            .padding(end = 8.dp, bottom = 10.dp),
                    ) {
                        codeViewerBlock(
                            code = state.text,
                            language = language,
                            hScrollState = hScrollState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(end = 2.dp, bottom = 10.dp),
                        adapter = rememberScrollbarAdapter(vScrollState),
                        style = ScrollbarStyle(
                            minimalHeight = 16.dp,
                            thickness = 4.dp,
                            shape = MaterialTheme.shapes.small,
                            hoverDurationMillis = 300,
                            unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        ),
                    )
                    HorizontalScrollbar(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(end = 6.dp),
                        adapter = rememberScrollbarAdapter(hScrollState),
                        style = ScrollbarStyle(
                            minimalHeight = 16.dp,
                            thickness = 4.dp,
                            shape = MaterialTheme.shapes.small,
                            hoverDurationMillis = 300,
                            unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        ),
                    )
                }
            }

            is WorkspaceViewerState.TooLarge -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource("file.viewer.too.large", state.sizeKb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            WorkspaceViewerState.Binary -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource("file.viewer.binary"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is WorkspaceViewerState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun loadWorkspaceFileContent(file: File): WorkspaceViewerState {
    return try {
        if (!file.exists() || !file.isFile) return WorkspaceViewerState.Error("File not found")

        val ext = file.extension.lowercase()
        if (ext in WORKSPACE_BINARY_EXTENSIONS) return WorkspaceViewerState.Binary

        val sizeBytes = file.length()
        if (sizeBytes > WORKSPACE_MAX_PREVIEW_BYTES) return WorkspaceViewerState.TooLarge(sizeBytes / 1024)

        val text = file.readText(Charsets.UTF_8)
        WorkspaceViewerState.Content(text = text, lineCount = text.lines().size)
    } catch (e: Exception) {
        WorkspaceViewerState.Error("Unable to read file: ${e.message}")
    }
}

private fun copyTextToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

// ── Render list builder ───────────────────────────────────────────────────────

private fun buildWorkspaceRenderList(
    nodes: List<WorkspaceNode>,
    depth: Int,
    expandedPaths: Map<String, Boolean>,
): List<WorkspaceRenderItem> {
    val result = mutableListOf<WorkspaceRenderItem>()
    for (node in nodes) {
        result += WorkspaceRenderItem(node, depth)
        if (node is WorkspaceFolderNode && expandedPaths[node.file.absolutePath] == true) {
            result += buildWorkspaceRenderList(loadWorkspaceChildren(node.file), depth + 1, expandedPaths)
        }
    }
    return result
}

// ── File system helpers ───────────────────────────────────────────────────────

private fun loadWorkspaceChildren(dir: File): List<WorkspaceNode> {
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    val files = dir.listFiles() ?: return emptyList()
    return files
        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        .map { if (it.isDirectory) WorkspaceFolderNode(it) else WorkspaceFileNode(it) }
}

@Composable
private fun workspaceFileIconTint(name: String) = when {
    name.endsWith(".sh") || name.endsWith(".bash") -> MaterialTheme.colorScheme.error.copy(alpha = 0.75f)

    name.endsWith(".md") -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)

    name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".py") ||
        name.endsWith(".js") || name.endsWith(".ts") -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".yaml") || name.endsWith(".yml") ->
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
}

private fun Long.toWorkspaceHumanSize(): String = when {
    this < 1_024 -> "${this}B"
    this < 1_048_576 -> "${"%.1f".format(this / 1_024.0)}KB"
    else -> "${"%.1f".format(this / 1_048_576.0)}MB"
}

private fun workspaceCopyToClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
