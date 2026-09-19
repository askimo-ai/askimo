/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.currentFileLogger
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.filePreviewPane
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import javax.swing.JFileChooser
import kotlin.time.Duration.Companion.milliseconds

private val log = currentFileLogger()

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

/** Number of "Recent" workspaces shown before collapsing behind a "Show N more" toggle. */
private const val RECENT_WORKSPACES_COLLAPSED_LIMIT = 6

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
    val scope = rememberCoroutineScope()
    var rootChildren by remember(workDir, refreshKey) { mutableStateOf<List<WorkspaceNode>>(emptyList()) }
    var isLoading by remember(workDir, refreshKey) { mutableStateOf(true) }
    val expandedPaths = remember(workDir) { mutableStateMapOf<String, Boolean>() }
    val workspaceWatcher = remember(workDir) { WorkspaceWatcher(workDir) }
    DisposableEffect(workspaceWatcher) {
        onDispose { workspaceWatcher.close() }
    }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var internalRefreshKey by remember { mutableStateOf(0) }

    // ── Mutation state ────────────────────────────────────────────────────────
    var renamingPath by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteConfirmNodes by remember { mutableStateOf<List<WorkspaceNode>?>(null) }
    // Pair(parentDir, isFolder)
    var newItemTarget by remember { mutableStateOf<Pair<File, Boolean>?>(null) }
    var newItemName by remember { mutableStateOf("") }
    var headerMenuExpanded by remember { mutableStateOf(false) }

    // ── Multi-selection state ─────────────────────────────────────────────────
    val selectedPaths = remember(workDir) { mutableStateMapOf<String, Boolean>() }
    var lastSelectedPath by remember(workDir) { mutableStateOf<String?>(null) }
    val selectedCount = selectedPaths.values.count { it }

    // ── Workspace switcher state ───────────────────────────────────────────────
    val workspaceRepo = remember { DatabaseManager.getInstance().getWorkspaceRepository() }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var workspaceListVersion by remember { mutableStateOf(0) }
    var renameWorkspaceTarget by remember { mutableStateOf<Workspace?>(null) }
    var renameWorkspaceText by remember { mutableStateOf("") }
    var deleteWorkspaceTarget by remember { mutableStateOf<Workspace?>(null) }
    var showAllRecentWorkspaces by remember { mutableStateOf(false) }
    val currentWorkspace = remember(workspaces, workDir) {
        workspaces.firstOrNull { it.path == workDir.absoluteFile.normalize().path }
    }

    LaunchedEffect(workDir, workspaceListVersion) {
        workspaces = withContext(Dispatchers.IO) { workspaceRepo.findAll() }
    }

    LaunchedEffect(workDir, refreshKey, internalRefreshKey) {
        isLoading = true
        rootChildren = withContext(Dispatchers.IO) { loadWorkspaceChildren(workDir) }
        isLoading = false
    }

    // ── Auto-refresh on filesystem changes (e.g. agent writing files) ─────────
    LaunchedEffect(workspaceWatcher) {
        val changeEvents = Channel<Unit>(Channel.CONFLATED)
        val watcherJob = launch(Dispatchers.IO) {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    workspaceWatcher.run { changeEvents.trySend(Unit) }
                }
            }
        }
        try {
            for (unused in changeEvents) {
                // Debounce bursts of events (e.g. an agent writing many files at once).
                delay(400.milliseconds)
                while (changeEvents.tryReceive().isSuccess) {
                    // Drain any additional pending signals accumulated during the debounce.
                }
                internalRefreshKey++
            }
        } finally {
            watcherJob.cancel()
        }
    }

    // ── Mutation helpers ──────────────────────────────────────────────────────

    fun refresh() {
        internalRefreshKey++
    }

    fun toggleExpand(path: String) {
        val expanding = !(expandedPaths[path] ?: false)
        expandedPaths[path] = expanding
        val nioPath = File(path).toPath()
        if (expanding) workspaceWatcher.register(nioPath) else workspaceWatcher.unregister(nioPath)
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

    fun deleteNodes(nodes: List<WorkspaceNode>) {
        nodes.forEach { node ->
            if (selectedFile?.absolutePath == node.file.absolutePath ||
                selectedFile?.absolutePath?.startsWith(node.file.absolutePath + File.separator) == true
            ) {
                selectedFile = null
            }
            node.file.deleteRecursively()
            selectedPaths.remove(node.file.absolutePath)
        }
        refresh()
    }

    fun clearSelection() {
        selectedPaths.clear()
        lastSelectedPath = null
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
    deleteConfirmNodes?.let { nodes ->
        val isMultiple = nodes.size > 1
        AppComponents.alertDialog(
            onDismissRequest = { deleteConfirmNodes = null },
            title = {
                Text(
                    if (isMultiple) {
                        stringResource("agents.view.workspace.delete.confirm.title.multiple", nodes.size.toString())
                    } else {
                        stringResource("agents.view.workspace.delete.confirm.title", nodes.first().displayName)
                    },
                )
            },
            text = { Text(stringResource("agents.view.workspace.delete.confirm.message")) },
            confirmButton = {
                dangerButton(onClick = {
                    deleteNodes(nodes)
                    deleteConfirmNodes = null
                }) {
                    Text(stringResource("action.delete"))
                }
            },
            dismissButton = {
                secondaryButton(onClick = { deleteConfirmNodes = null }) {
                    Text(stringResource("action.cancel"))
                }
            },
        )
    }

    // ── Rename workspace dialog ────────────────────────────────────────────────
    renameWorkspaceTarget?.let { target ->
        val focusRequester = remember { FocusRequester() }
        AppComponents.alertDialog(
            onDismissRequest = { renameWorkspaceTarget = null },
            title = { Text(stringResource("agents.view.workspace.switcher.rename")) },
            text = {
                OutlinedTextField(
                    value = renameWorkspaceText,
                    onValueChange = { renameWorkspaceText = it },
                    singleLine = true,
                    colors = AppColors.outlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onKeyEvent { event ->
                        if (event.key == Key.Enter && renameWorkspaceText.trim().isNotBlank()) {
                            val newName = renameWorkspaceText.trim()
                            scope.launch {
                                withContext(Dispatchers.IO) { workspaceRepo.rename(target.id, newName) }
                                renameWorkspaceTarget = null
                                workspaceListVersion++
                            }
                            true
                        } else {
                            false
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameWorkspaceText.trim().isNotBlank(),
                    onClick = {
                        val newName = renameWorkspaceText.trim()
                        scope.launch {
                            withContext(Dispatchers.IO) { workspaceRepo.rename(target.id, newName) }
                            renameWorkspaceTarget = null
                            workspaceListVersion++
                        }
                    },
                ) { Text(stringResource("action.rename")) }
            },
            dismissButton = {
                TextButton(onClick = { renameWorkspaceTarget = null }) { Text(stringResource("action.cancel")) }
            },
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    // ── Remove workspace confirmation ─────────────────────────────────────────
    deleteWorkspaceTarget?.let { target ->
        AppComponents.alertDialog(
            onDismissRequest = { deleteWorkspaceTarget = null },
            title = { Text(stringResource("agents.view.workspace.switcher.remove.title", target.name)) },
            text = { Text(stringResource("agents.view.workspace.switcher.remove.message")) },
            confirmButton = {
                dangerButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { workspaceRepo.delete(target.id) }
                        deleteWorkspaceTarget = null
                        workspaceListVersion++
                    }
                }) {
                    Text(stringResource("action.remove"))
                }
            },
            dismissButton = {
                secondaryButton(onClick = { deleteWorkspaceTarget = null }) { Text(stringResource("action.cancel")) }
            },
        )
    }

    // ── New item dialog ───────────────────────────────────────────────────────
    newItemTarget?.let { (parentDir, isFolder) ->
        val focusRequester = remember { FocusRequester() }
        AppComponents.alertDialog(
            onDismissRequest = {
                newItemTarget = null
                newItemName = ""
            },
            title = {
                Text(
                    if (isFolder) {
                        stringResource("agents.view.workspace.new.folder")
                    } else {
                        stringResource("agents.view.workspace.new.file")
                    },
                )
            },
            text = {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it },
                    placeholder = { Text(stringResource("agents.view.workspace.name.placeholder")) },
                    singleLine = true,
                    colors = AppColors.outlinedTextFieldColors(),
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
            if (selectedCount > 1) {
                // ── Multi-selection action bar ───────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.small, end = Spacing.extraSmall, top = Spacing.small, bottom = Spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        stringResource("agents.view.workspace.selection.count", selectedCount.toString()),
                        style = AppTextStyles.fieldLabel,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    themedTooltip(text = stringResource("action.delete")) {
                        IconButton(
                            onClick = {
                                deleteConfirmNodes = rootChildren
                                    .flatMap { buildWorkspaceRenderList(listOf(it), 0, expandedPaths) }
                                    .map { it.node }
                                    .filter { selectedPaths[it.file.absolutePath] == true }
                            },
                            modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource("action.delete"),
                                modifier = Modifier.size(14.dp),
                                tint = AppColors.destructiveIconColor(),
                            )
                        }
                    }
                    themedTooltip(text = stringResource("agents.view.workspace.selection.clear")) {
                        IconButton(
                            onClick = { clearSelection() },
                            modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource("agents.view.workspace.selection.clear"),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.small, end = Spacing.extraSmall, top = Spacing.small, bottom = Spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
                ) {
                    if (onWorkDirChanged != null) {
                        // ── Workspace switcher ───────────────────────────────────
                        Box(modifier = Modifier.weight(1f)) {
                            themedTooltip(text = stringResource("agents.view.workspace.switcher.title")) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { workspaceMenuExpanded = true },
                                        )
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(start = Spacing.extraSmall, end = Spacing.micro, top = Spacing.micro, bottom = Spacing.micro),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                ) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        currentWorkspace?.name ?: workDir.name.ifEmpty { workDir.path },
                                        style = AppTextStyles.fieldLabel,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = stringResource("agents.view.workspace.switcher.title"),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            dropdownMenu(
                                expanded = workspaceMenuExpanded,
                                onDismissRequest = { workspaceMenuExpanded = false },
                                modifier = Modifier.widthIn(min = 320.dp),
                            ) {
                                if (workspaces.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource("agents.view.workspace.switcher.empty"),
                                                style = AppTextStyles.caption,
                                                color = AppColors.tertiaryIconColor(),
                                            )
                                        },
                                        onClick = {},
                                        enabled = false,
                                    )
                                } else {
                                    val pinnedWorkspaces = workspaces.filter { it.pinned }
                                    val recentWorkspaces = workspaces.filterNot { it.pinned }
                                    val visibleRecent = if (showAllRecentWorkspaces) {
                                        recentWorkspaces
                                    } else {
                                        recentWorkspaces.take(RECENT_WORKSPACES_COLLAPSED_LIMIT)
                                    }

                                    fun renameHandler(ws: Workspace): () -> Unit = {
                                        renameWorkspaceTarget = ws
                                        renameWorkspaceText = ws.name
                                        workspaceMenuExpanded = false
                                    }
                                    fun removeHandler(ws: Workspace): () -> Unit = {
                                        deleteWorkspaceTarget = ws
                                        workspaceMenuExpanded = false
                                    }
                                    fun pinHandler(ws: Workspace): () -> Unit = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { workspaceRepo.setPinned(ws.id, !ws.pinned) }
                                            workspaceListVersion++
                                        }
                                    }
                                    fun selectHandler(ws: Workspace, isCurrent: Boolean): () -> Unit = {
                                        workspaceMenuExpanded = false
                                        if (!isCurrent) onWorkDirChanged(File(ws.path))
                                    }

                                    if (pinnedWorkspaces.isNotEmpty()) {
                                        workspaceSectionHeader(stringResource("agents.view.workspace.switcher.section.pinned"))
                                        pinnedWorkspaces.forEach { ws ->
                                            val isCurrent = ws.id == currentWorkspace?.id
                                            workspaceMenuItem(
                                                ws = ws,
                                                isCurrent = isCurrent,
                                                onSelect = selectHandler(ws, isCurrent),
                                                onTogglePin = pinHandler(ws),
                                                onRenameRequest = renameHandler(ws),
                                                onRemoveRequest = removeHandler(ws),
                                            )
                                        }
                                        if (recentWorkspaces.isNotEmpty()) HorizontalDivider()
                                    }

                                    if (recentWorkspaces.isNotEmpty()) {
                                        workspaceSectionHeader(stringResource("agents.view.workspace.switcher.section.recent"))
                                        visibleRecent.forEach { ws ->
                                            val isCurrent = ws.id == currentWorkspace?.id
                                            workspaceMenuItem(
                                                ws = ws,
                                                isCurrent = isCurrent,
                                                onSelect = selectHandler(ws, isCurrent),
                                                onTogglePin = pinHandler(ws),
                                                onRenameRequest = renameHandler(ws),
                                                onRemoveRequest = removeHandler(ws),
                                            )
                                        }
                                        if (recentWorkspaces.size > RECENT_WORKSPACES_COLLAPSED_LIMIT) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (showAllRecentWorkspaces) {
                                                            stringResource("action.show.less")
                                                        } else {
                                                            stringResource(
                                                                "agents.view.workspace.switcher.show.more",
                                                                (recentWorkspaces.size - RECENT_WORKSPACES_COLLAPSED_LIMIT).toString(),
                                                            )
                                                        },
                                                        style = AppTextStyles.caption,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                },
                                                onClick = { showAllRecentWorkspaces = !showAllRecentWorkspaces },
                                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource("agents.view.workspace.switcher.add")) },
                                    leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) },
                                    onClick = {
                                        workspaceMenuExpanded = false
                                        val chooser = JFileChooser().apply {
                                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                            currentDirectory = workDir
                                            dialogTitle = "Select Working Directory"
                                        }
                                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                            onWorkDirChanged(chooser.selectedFile)
                                            workspaceListVersion++
                                        }
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                            }
                        }
                    } else {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            workDir.name.ifEmpty { workDir.path },
                            style = AppTextStyles.fieldLabel,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(start = Spacing.extraSmall),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // ── + New button ───────────────────────────────────────────
                    Box {
                        themedTooltip(text = stringResource("agents.view.workspace.new")) {
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
                                text = { Text(stringResource("agents.view.workspace.new.folder")) },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    newItemTarget = Pair(workDir, true)
                                    newItemName = ""
                                    headerMenuExpanded = false
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource("agents.view.workspace.new.file")) },
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

                    // ── Manual refresh ───────────────────────────────────────────
                    themedTooltip(text = stringResource("action.refresh")) {
                        IconButton(
                            onClick = { refresh() },
                            modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource("action.refresh"),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ── Open workdir in Finder/Explorer ─────────────────────────
                    themedTooltip(text = stringResource("agents.view.workdir.open")) {
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
            } // closes else (normal header)

            HorizontalDivider(color = AppColors.codeBlockBorderColor())

            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "...",
                        style = AppTextStyles.caption,
                        color = AppColors.tertiaryIconColor(),
                    )
                }

                rootChildren.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource("agents.view.workspace.empty"),
                        style = AppTextStyles.caption,
                        color = AppColors.tertiaryIconColor(),
                        modifier = Modifier.padding(Spacing.large),
                    )
                }

                else -> {
                    val renderItems = remember(rootChildren, expandedPaths.keys.toSet(), expandedPaths.values.toList()) {
                        buildWorkspaceRenderList(rootChildren, depth = 0, expandedPaths = expandedPaths)
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(vertical = Spacing.extraSmall)) {
                            items(renderItems, key = { it.node.file.absolutePath }) { item ->
                                val path = item.node.file.absolutePath
                                workspaceNodeRow(
                                    node = item.node,
                                    depth = item.depth,
                                    isExpanded = expandedPaths[path] ?: false,
                                    isSelected = selectedFile?.absolutePath == path && item.node is WorkspaceFileNode,
                                    isMultiSelected = selectedPaths[path] == true,
                                    selectedCount = selectedCount,
                                    isRenaming = renamingPath == path,
                                    renameText = renameText,
                                    onRenameTextChange = { renameText = it },
                                    onToggleExpand = ::toggleExpand,
                                    onSelectFile = { node, kind ->
                                        handleWorkspaceClick(
                                            node = node,
                                            kind = kind,
                                            renderItems = renderItems,
                                            selectedPaths = selectedPaths,
                                            lastSelectedPath = lastSelectedPath,
                                            onLastSelectedPathChange = { lastSelectedPath = it },
                                            onSelectSingleFile = { selectedFile = it },
                                        )
                                    },
                                    onStartRename = ::startRename,
                                    onConfirmRename = ::confirmRename,
                                    onCancelRename = ::cancelRename,
                                    onRequestDelete = { node ->
                                        val nodePath = node.file.absolutePath
                                        deleteConfirmNodes = if (selectedPaths[nodePath] == true && selectedCount > 1) {
                                            renderItems.filter { selectedPaths[it.node.file.absolutePath] == true }.map { it.node }
                                        } else {
                                            listOf(node)
                                        }
                                    },
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
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = Spacing.micro),
                            style = AppComponents.scrollbarStyle(),
                        )
                    }
                }
            }
        }

        // ── Bottom panel: File viewer ──────────────────────────────────────
        if (selectedFile != null) {
            Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                filePreviewPane(
                    path = selectedFile!!.absolutePath,
                    displayName = selectedFile!!.name,
                    onClose = { selectedFile = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ── Workspace switcher: dropdown list items ───────────────────────────────────

/** Small, non-interactive uppercase label separating "Pinned" from "Recent" workspaces. */
@Composable
private fun workspaceSectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = AppTextStyles.hint,
        color = AppColors.tertiaryIconColor(),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.micro),
    )
}

/** A single workspace row inside the switcher dropdown, with pin/rename/remove actions. */
@Composable
private fun workspaceMenuItem(
    ws: Workspace,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onRenameRequest: () -> Unit,
    onRemoveRequest: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            themedTooltip(text = ws.path) {
                Column(modifier = Modifier.widthIn(max = 320.dp)) {
                    Text(
                        ws.name,
                        style = AppTextStyles.body,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ws.path,
                        style = AppTextStyles.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingIcon = {
            if (isCurrent) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Spacer(modifier = Modifier.size(16.dp))
            }
        },
        trailingIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.micro)) {
                themedTooltip(text = stringResource("agents.view.workspace.switcher.pin")) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            if (ws.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource("agents.view.workspace.switcher.pin"),
                            modifier = Modifier.size(13.dp),
                            tint = if (ws.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                themedTooltip(text = stringResource("action.rename")) {
                    IconButton(
                        onClick = onRenameRequest,
                        modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.DriveFileRenameOutline,
                            contentDescription = stringResource("action.rename"),
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                themedTooltip(text = stringResource("action.remove")) {
                    IconButton(
                        onClick = onRemoveRequest,
                        modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource("action.remove"),
                            modifier = Modifier.size(13.dp),
                            tint = AppColors.destructiveIconColor(),
                        )
                    }
                }
            }
        },
        onClick = onSelect,
        contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = Spacing.small),
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}

// ── Node row ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun workspaceNodeRow(
    node: WorkspaceNode,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean = false,
    isMultiSelected: Boolean = false,
    selectedCount: Int = 0,
    isRenaming: Boolean = false,
    renameText: String = "",
    onRenameTextChange: (String) -> Unit = {},
    onToggleExpand: (String) -> Unit,
    onSelectFile: (WorkspaceNode, WorkspaceClickKind) -> Unit = { _, _ -> },
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
                        if (isSelected || isMultiSelected) {
                            AppColors.surfaceColor(AppColors.Elevation.SELECTED)
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(4.dp),
                    )
                    .then(
                        if (!isRenaming) {
                            // Plain click: expand/select a single item, clearing multi-selection.
                            Modifier.onClick(
                                matcher = PointerMatcher.mouse(PointerButton.Primary),
                                keyboardModifiers = { !isCtrlPressed && !isMetaPressed && !isShiftPressed },
                                onClick = {
                                    if (node is WorkspaceFolderNode) onToggleExpand(node.file.absolutePath)
                                    onSelectFile(node, WorkspaceClickKind.SINGLE)
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (!isRenaming) {
                            // Cmd/Ctrl+Click: toggle this item in/out of the multi-selection.
                            Modifier.onClick(
                                matcher = PointerMatcher.mouse(PointerButton.Primary),
                                keyboardModifiers = { isCtrlPressed || isMetaPressed },
                                onClick = { onSelectFile(node, WorkspaceClickKind.TOGGLE) },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (!isRenaming) {
                            // Shift+Click: select the range between the last-selected item and this one.
                            Modifier.onClick(
                                matcher = PointerMatcher.mouse(PointerButton.Primary),
                                keyboardModifiers = { isShiftPressed },
                                onClick = { onSelectFile(node, WorkspaceClickKind.RANGE) },
                            )
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
                    .padding(start = (depth * 14 + 8).dp, top = Spacing.micro, bottom = Spacing.micro, end = Spacing.small)
                    .pointerHoverIcon(PointerIcon.Hand),

                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (node) {
                    is WorkspaceFolderNode -> {
                        Icon(
                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = AppColors.secondaryIconColor(),
                            modifier = Modifier.size(14.dp),
                        )
                        Icon(
                            if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                            contentDescription = null,
                            tint = AppColors.secondaryIconColor(),
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
                        textStyle = AppTextStyles.caption.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (node is WorkspaceFolderNode) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                AppColors.surfaceColor(AppColors.Elevation.EMPHASIS),
                                RoundedCornerShape(3.dp),
                            )
                            .padding(horizontal = Spacing.extraSmall, vertical = Spacing.micro)
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
                        style = AppTextStyles.caption,
                        fontWeight = if (node is WorkspaceFolderNode) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (node is WorkspaceFileNode) {
                        Text(
                            text = node.file.length().toWorkspaceHumanSize(),
                            style = AppTextStyles.hint,
                            color = AppColors.tertiaryIconColor(),
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
                if (isMultiSelected && selectedCount > 1) {
                    // ── Bulk action menu for the active multi-selection ─────
                    DropdownMenuItem(
                        text = { Text(stringResource("agents.view.workspace.delete.selected", selectedCount.toString()), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            onRequestDelete(node)
                            showContextMenu = false
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                    return@dropdownMenu
                }
                when (node) {
                    is WorkspaceFolderNode -> {
                        DropdownMenuItem(
                            text = { Text(stringResource("agents.view.workspace.new.file.here")) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                onNewFileHere(node.file)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("agents.view.workspace.new.subfolder")) },
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
                            text = { Text(stringResource("agents.view.workdir.open")) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file) }
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("agents.view.workspace.copy.path")) },
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
                            text = { Text(stringResource("agents.view.workspace.duplicate")) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                onDuplicate(node)
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource("agents.view.workspace.open.file")) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file) }
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("agents.view.workspace.open.folder")) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                runCatching { Desktop.getDesktop().open(node.file.parentFile) }
                                showContextMenu = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("agents.view.workspace.copy.path")) },
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

// ── Multi-selection helpers ───────────────────────────────────────────────────

/** Describes how a click on a workspace row should affect the selection. */
private enum class WorkspaceClickKind { SINGLE, TOGGLE, RANGE }

/**
 * Applies the effect of a click on [node] to the selection state.
 *
 * - [WorkspaceClickKind.SINGLE]: clears the multi-selection; opens the file preview if [node]
 *   is a file (folders only toggle expansion, handled by the caller).
 * - [WorkspaceClickKind.TOGGLE] (Cmd/Ctrl+Click): adds/removes [node] from the multi-selection.
 * - [WorkspaceClickKind.RANGE] (Shift+Click): selects every visible row between the last
 *   selected/toggled row and [node].
 */
private fun handleWorkspaceClick(
    node: WorkspaceNode,
    kind: WorkspaceClickKind,
    renderItems: List<WorkspaceRenderItem>,
    selectedPaths: MutableMap<String, Boolean>,
    lastSelectedPath: String?,
    onLastSelectedPathChange: (String) -> Unit,
    onSelectSingleFile: (File?) -> Unit,
) {
    val path = node.file.absolutePath
    when (kind) {
        WorkspaceClickKind.SINGLE -> {
            selectedPaths.clear()
            onLastSelectedPathChange(path)
            if (node is WorkspaceFileNode) onSelectSingleFile(node.file)
        }

        WorkspaceClickKind.TOGGLE -> {
            if (selectedPaths[path] == true) {
                selectedPaths.remove(path)
            } else {
                selectedPaths[path] = true
            }
            onLastSelectedPathChange(path)
        }

        WorkspaceClickKind.RANGE -> {
            val anchorIndex = lastSelectedPath?.let { anchor -> renderItems.indexOfFirst { it.node.file.absolutePath == anchor } } ?: -1
            val targetIndex = renderItems.indexOfFirst { it.node.file.absolutePath == path }
            if (anchorIndex < 0 || targetIndex < 0) {
                selectedPaths[path] = true
            } else {
                val range = if (anchorIndex <= targetIndex) anchorIndex..targetIndex else targetIndex..anchorIndex
                range.forEach { i -> selectedPaths[renderItems[i].node.file.absolutePath] = true }
            }
            onLastSelectedPathChange(path)
        }
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

/**
 * Lazily watches only the directories the user has expanded in the workspace tree (plus the
 * root), mirroring `expandedPaths` — registering a native watch on expand, cancelling it on
 * collapse. Keeps watch resources proportional to what's visible, instead of the whole tree
 * (which for real projects can mean tens of thousands of dirs under `node_modules`, `.git`, etc.).
 *
 * [register]/[unregister] run on the UI thread while [run] drives the blocking event loop on
 * an IO thread, so both maps are [java.util.concurrent.ConcurrentHashMap] — they're genuinely
 * mutated from both.
 */
private class WorkspaceWatcher(root: File) {
    private val watchService = FileSystems.getDefault().newWatchService()
    private val keysByPath = java.util.concurrent.ConcurrentHashMap<Path, WatchKey>()
    private val pathsByKey = java.util.concurrent.ConcurrentHashMap<WatchKey, Path>()

    init {
        register(root.toPath())
    }

    /** Starts watching [path] for create/delete/modify events, if not already watched. No-op if [path] no longer exists or the watch service is closed. */
    fun register(path: Path) {
        if (keysByPath.containsKey(path)) return
        runCatching {
            val key = path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
            keysByPath[path] = key
            pathsByKey[key] = path
        }.onFailure { e ->
            log.warn("Failed to register workspace file watch for {}: {}", path, e.message)
        }
    }

    /** Stops watching [path] (e.g. the folder was just collapsed in the UI). No-op if it wasn't registered. */
    fun unregister(path: Path) {
        keysByPath.remove(path)?.let { key ->
            key.cancel()
            pathsByKey.remove(key)
        }
    }

    /**
     * Blocks the calling thread, invoking [onChange] whenever a create/modify/delete event
     * arrives for any currently-registered directory. Intended to be called via
     * `runInterruptible` on an IO dispatcher so cancellation interrupts the blocking
     * [java.nio.file.WatchService.take] call and stops watching cleanly.
     */
    fun run(onChange: () -> Unit) {
        try {
            while (true) {
                val key = watchService.take()
                if (pathsByKey.containsKey(key)) {
                    val hasRealEvent = key.pollEvents().any { it.kind() != StandardWatchEventKinds.OVERFLOW }
                    if (hasRealEvent) onChange()
                }
                if (!key.reset()) {
                    pathsByKey.remove(key)?.let { keysByPath.remove(it) }
                }
            }
        } catch (_: InterruptedException) {
            // Expected when the watching coroutine is cancelled.
        } catch (_: ClosedWatchServiceException) {
            // Expected when close() runs concurrently.
        } catch (e: Exception) {
            log.warn("Workspace watcher stopped unexpectedly", e)
        }
    }

    /** Closes the underlying watch service, unblocking any in-progress [run] call. Safe to call more than once. */
    fun close() {
        runCatching { watchService.close() }
    }
}

@Composable
private fun workspaceFileIconTint(name: String) = when {
    name.endsWith(".sh") || name.endsWith(".bash") -> AppColors.warningColor()

    name.endsWith(".md") -> AppColors.secondaryIconColor()

    name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".py") ||
        name.endsWith(".js") || name.endsWith(".ts") -> AppColors.secondaryIconColor()

    name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".yaml") || name.endsWith(".yml") ->
        AppColors.secondaryIconColor()

    else -> AppColors.tertiaryIconColor()
}

private fun Long.toWorkspaceHumanSize(): String = when {
    this < 1_024 -> "${this}B"
    this < 1_048_576 -> "${"%.1f".format(this / 1_024.0)}KB"
    else -> "${"%.1f".format(this / 1_048_576.0)}MB"
}

private fun workspaceCopyToClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
