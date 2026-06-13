/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.PointerMatcher
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.ui.codeViewerBlock
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

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
 *
 * @param workDir          Root directory to display.
 * @param refreshKey       Increment to force a full reload (e.g. after a new run).
 * @param onWorkDirChanged If provided, shows a folder-picker button in the header.
 */
@Composable
internal fun workspaceFilesPanel(
    workDir: File,
    refreshKey: Int = 0,
    onWorkDirChanged: ((File) -> Unit)? = null,
) {
    var rootChildren by remember(workDir, refreshKey) { mutableStateOf<List<WorkspaceNode>>(emptyList()) }
    var isLoading by remember(workDir, refreshKey) { mutableStateOf(true) }
    val expandedPaths = remember(workDir) { mutableStateMapOf<String, Boolean>() }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var internalRefreshKey by remember { mutableStateOf(0) }

    // ── Mutation state ────────────────────────────────────────────────────────
    var renamingPath by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteConfirmNode by remember { mutableStateOf<WorkspaceNode?>(null) }
    // Pair(parentDir, isFolder)
    var newItemTarget by remember { mutableStateOf<Pair<File, Boolean>?>(null) }
    var newItemName by remember { mutableStateOf("") }
    var headerMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(workDir, refreshKey, internalRefreshKey) {
        isLoading = true
        rootChildren = withContext(Dispatchers.IO) { loadWorkspaceChildren(workDir) }
        isLoading = false
    }

    // ── Mutation helpers ──────────────────────────────────────────────────────

    fun refresh() {
        internalRefreshKey++
    }

    fun startRename(node: WorkspaceNode) {
        renamingPath = node.file.absolutePath
        renameText = node.displayName
    }

    fun confirmRename(node: WorkspaceNode) {
        val trimmed = renameText.trim()
        if (trimmed.isNotBlank() && trimmed != node.displayName) {
            val dest = File(node.file.parentFile, trimmed)
            node.file.renameTo(dest)
            if (selectedFile?.absolutePath == node.file.absolutePath) selectedFile = dest
        }
        renamingPath = null
        refresh()
    }

    fun cancelRename() {
        renamingPath = null
    }

    fun deleteNode(node: WorkspaceNode) {
        if (selectedFile?.absolutePath == node.file.absolutePath ||
            selectedFile?.absolutePath?.startsWith(node.file.absolutePath + File.separator) == true
        ) {
            selectedFile = null
        }
        node.file.deleteRecursively()
        refresh()
    }

    fun duplicateFile(file: File) {
        val baseName = file.nameWithoutExtension
        val ext = if (file.extension.isNotEmpty()) ".${file.extension}" else ""
        var dest = File(file.parentFile, "$baseName copy$ext")
        var i = 2
        while (dest.exists()) {
            dest = File(file.parentFile, "$baseName copy $i$ext")
            i++
        }
        file.copyTo(dest)
        refresh()
    }

    fun createNewItem(parentDir: File, name: String, isFolder: Boolean) {
        val f = File(parentDir, name.trim())
        if (isFolder) {
            f.mkdirs()
        } else {
            f.parentFile?.mkdirs()
            f.createNewFile()
        }
        refresh()
    }

    val listState = rememberLazyListState()

    // ── Delete confirmation dialog ────────────────────────────────────────────
    deleteConfirmNode?.let { node ->
        AlertDialog(
            onDismissRequest = { deleteConfirmNode = null },
            title = { Text(stringResource("skills.view.workspace.delete.confirm.title", node.displayName)) },
            text = { Text(stringResource("skills.view.workspace.delete.confirm.message")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteNode(node)
                    deleteConfirmNode = null
                }) {
                    Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmNode = null }) {
                    Text(stringResource("action.cancel"))
                }
            },
        )
    }

    // ── New item dialog ───────────────────────────────────────────────────────
    newItemTarget?.let { (parentDir, isFolder) ->
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = {
                newItemTarget = null
                newItemName = ""
            },
            title = {
                Text(
                    if (isFolder) {
                        stringResource("skills.view.workspace.new.folder")
                    } else {
                        stringResource("skills.view.workspace.new.file")
                    },
                )
            },
            text = {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it },
                    placeholder = { Text(stringResource("skills.view.workspace.name.placeholder")) },
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onKeyEvent { event ->
                        if (event.key == Key.Enter && newItemName.trim().isNotBlank()) {
                            createNewItem(parentDir, newItemName, isFolder)
                            newItemTarget = null
                            newItemName = ""
                            true
                        } else if (event.key == Key.Escape) {
                            newItemTarget = null
                            newItemName = ""
                            true
                        } else {
                            false
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newItemName.trim().isNotBlank(),
                    onClick = {
                        createNewItem(parentDir, newItemName, isFolder)
                        newItemTarget = null
                        newItemName = ""
                    },
                ) { Text(stringResource("action.create")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    newItemTarget = null
                    newItemName = ""
                }) {
                    Text(stringResource("action.cancel"))
                }
            },
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top panel: File tree ───────────────────────────────────────────
        Column(modifier = Modifier.weight(if (selectedFile != null) 0.4f else 1f).fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // ── + New button ───────────────────────────────────────────
                Box {
                    themedTooltip(text = stringResource("skills.view.workspace.new")) {
                        IconButton(
                            onClick = { headerMenuExpanded = true },
                            modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    dropdownMenu(expanded = headerMenuExpanded, onDismissRequest = { headerMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.new.folder")) },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                newItemTarget = Pair(workDir, true)
                                newItemName = ""
                                headerMenuExpanded = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.new.file")) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                newItemTarget = Pair(workDir, false)
                                newItemName = ""
                                headerMenuExpanded = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                } // closes Box

                // ── Change / open workdir button ───────────────────────────
                if (onWorkDirChanged != null) {
                    themedTooltip(text = stringResource("skills.view.workdir.change")) {
                        IconButton(
                            onClick = {
                                val chooser = JFileChooser().apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    currentDirectory = workDir
                                    dialogTitle = "Select Working Directory"
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val selected = chooser.selectedFile
                                    ApplicationPreferences.setSkillsWorkspaceDir(selected.absolutePath)
                                    onWorkDirChanged(selected)
                                }
                            },
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
                } else {
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
                                    isRenaming = renamingPath == item.node.file.absolutePath,
                                    renameText = renameText,
                                    onRenameTextChange = { renameText = it },
                                    onToggleExpand = { path -> expandedPaths[path] = !(expandedPaths[path] ?: false) },
                                    onSelectFile = { if (it is WorkspaceFileNode) selectedFile = it.file },
                                    onStartRename = ::startRename,
                                    onConfirmRename = ::confirmRename,
                                    onCancelRename = ::cancelRename,
                                    onRequestDelete = { deleteConfirmNode = it },
                                    onDuplicate = { duplicateFile(it.file) },
                                    onNewFileHere = { dir ->
                                        newItemTarget = Pair(dir, false)
                                        newItemName = ""
                                    },
                                    onNewSubfolderHere = { dir ->
                                        newItemTarget = Pair(dir, true)
                                        newItemName = ""
                                    },
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                            style = AppComponents.scrollbarStyle(),
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
    isRenaming: Boolean = false,
    renameText: String = "",
    onRenameTextChange: (String) -> Unit = {},
    onToggleExpand: (String) -> Unit,
    onSelectFile: (WorkspaceNode) -> Unit = {},
    onStartRename: (WorkspaceNode) -> Unit = {},
    onConfirmRename: (WorkspaceNode) -> Unit = {},
    onCancelRename: () -> Unit = {},
    onRequestDelete: (WorkspaceNode) -> Unit = {},
    onDuplicate: (WorkspaceFileNode) -> Unit = {},
    onNewFileHere: (File) -> Unit = {},
    onNewSubfolderHere: (File) -> Unit = {},
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val renameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isRenaming) {
        if (isRenaming) renameFocusRequester.requestFocus()
    }

    themedTooltip(text = if (isRenaming) "" else node.file.absolutePath) {
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
                        if (!isRenaming) {
                            when (node) {
                                is WorkspaceFolderNode -> Modifier.onClick(
                                    matcher = PointerMatcher.mouse(PointerButton.Primary),
                                    onClick = { onToggleExpand(node.file.absolutePath) },
                                )

                                is WorkspaceFileNode -> Modifier.onClick(
                                    matcher = PointerMatcher.mouse(PointerButton.Primary),
                                    onClick = { onSelectFile(node) },
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (!isRenaming) {
                            Modifier.onClick(
                                matcher = PointerMatcher.mouse(PointerButton.Secondary),
                                onClick = { showContextMenu = true },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = (depth * 14 + 8).dp, top = 3.dp, bottom = 3.dp, end = 8.dp)
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
                    }

                    is WorkspaceFileNode -> {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = workspaceFileIconTint(node.file.name),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                if (isRenaming) {
                    // ── Inline rename field ────────────────────────────────
                    BasicTextField(
                        value = renameText,
                        onValueChange = onRenameTextChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (node is WorkspaceFolderNode) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(3.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .focusRequester(renameFocusRequester)
                            .onKeyEvent { event ->
                                when (event.key) {
                                    Key.Enter -> {
                                        onConfirmRename(node)
                                        true
                                    }

                                    Key.Escape -> {
                                        onCancelRename()
                                        true
                                    }

                                    else -> false
                                }
                            },
                    )
                    // Confirm / cancel micro-buttons
                    IconButton(
                        onClick = { onConfirmRename(node) },
                        modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.DriveFileRenameOutline,
                            contentDescription = stringResource("action.rename"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    IconButton(
                        onClick = onCancelRename,
                        modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource("action.cancel"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                } else {
                    Text(
                        text = node.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (node is WorkspaceFolderNode) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (node is WorkspaceFileNode) {
                        Text(
                            text = node.file.length().toWorkspaceHumanSize(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        )
                    }
                }
            }

            // ── Context menu ───────────────────────────────────────────────
            dropdownMenu(
                expanded = showContextMenu && !isRenaming,
                onDismissRequest = { showContextMenu = false },
                offset = DpOffset(x = 0.dp, y = 0.dp),
            ) {
                when (node) {
                    is WorkspaceFolderNode -> {
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.new.file.here")) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                onNewFileHere(node.file)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.new.subfolder")) },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                onNewSubfolderHere(node.file)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource("action.rename")) },
                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                onStartRename(node)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workdir.open")) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file) }
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.copy.path")) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                workspaceCopyToClipboard(node.file.absolutePath)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onRequestDelete(node)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }

                    is WorkspaceFileNode -> {
                        DropdownMenuItem(
                            text = { Text(stringResource("action.rename")) },
                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                onStartRename(node)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.duplicate")) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                onDuplicate(node)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.open.file")) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file) }
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.open.folder")) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file.parentFile) }
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("skills.view.workspace.copy.path")) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                workspaceCopyToClipboard(node.file.absolutePath)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onRequestDelete(node)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
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
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp, bottom = 10.dp),
                        adapter = rememberScrollbarAdapter(vScrollState),
                        style = AppComponents.scrollbarStyle(),
                    )
                    HorizontalScrollbar(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 6.dp),
                        adapter = rememberScrollbarAdapter(hScrollState),
                        style = AppComponents.scrollbarStyle(),
                    )
                }
            }

            is WorkspaceViewerState.TooLarge -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("file.viewer.too.large", state.sizeKb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            WorkspaceViewerState.Binary -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("file.viewer.binary"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is WorkspaceViewerState.Error -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
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
