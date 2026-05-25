/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.chat

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.logging.currentFileLogger
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

private val log = currentFileLogger()

/** Maximum file size (512 KB) the viewer will load into memory. */
private const val MAX_PREVIEW_BYTES = 512 * 1024L

/** Extensions treated as binary / non-previewable. */
private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "zip", "tar", "gz", "bz2", "7z", "rar",
    "exe", "dll", "so", "dylib", "class", "jar",
    "mp3", "mp4", "wav", "ogg", "flac", "avi", "mov",
    "woff", "woff2", "ttf", "otf", "eot",
    "bin", "dat", "db", "sqlite",
)

/** Loading state for the file viewer. */
private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Content(val text: String, val lineCount: Int) : ViewerState
    data class TooLarge(val sizeKb: Long) : ViewerState
    data object Binary : ViewerState
    data class Error(val message: String) : ViewerState
}

/**
 * In-panel file viewer pane.
 *
 * Shows the raw text content of a [io.askimo.desktop.chat.FileTreeNode] in a scrollable, selectable
 * monospace view. Gracefully handles binary files, oversized files, and read
 * errors with an "Open externally" fallback action.
 *
 * @param node     The [io.askimo.desktop.chat.FileTreeNode] whose content to preview.
 * @param onClose  Called when the user dismisses the viewer.
 * @param modifier Optional modifier applied to the outer column.
 */
@Composable
fun fileViewerPane(
    node: FileTreeNode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewerState by remember(node.path) { mutableStateOf<ViewerState>(ViewerState.Loading) }

    // Load file content off the main thread whenever the selected node changes
    LaunchedEffect(node.path) {
        viewerState = ViewerState.Loading
        viewerState = withContext(Dispatchers.IO) { loadFileContent(node.path) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
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
                    text = node.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (viewerState is ViewerState.Content) {
                    Text(
                        text = stringResource(
                            "file.viewer.lines",
                            (viewerState as ViewerState.Content).lineCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                // Open in OS default editor — always available
                themedTooltip(text = stringResource("file.viewer.open.external")) {
                    IconButton(
                        onClick = { openFileExternally(node.path) },
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource("file.viewer.open.external"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                if (viewerState is ViewerState.Content) {
                    themedTooltip(text = stringResource("file.viewer.copy")) {
                        IconButton(
                            onClick = { copyTextToClipboard((viewerState as ViewerState.Content).text) },
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
            ViewerState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource("file.viewer.loading"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is ViewerState.Content -> {
                val language = File(node.path).extension.lowercase().takeIf { it.isNotEmpty() }
                val vScrollState = rememberScrollState()
                val hScrollState = rememberScrollState()
                // Outer Box: stacks scrollable content + fixed scrollbar overlays
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vScrollState)
                            .padding(end = 8.dp, bottom = 10.dp), // room for scrollbars
                    ) {
                        codeViewerBlock(
                            code = state.text,
                            language = language,
                            hScrollState = hScrollState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Vertical scrollbar — always visible on the right
                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(bottom = 10.dp),
                        adapter = rememberScrollbarAdapter(vScrollState),
                        style = ScrollbarStyle(
                            minimalHeight = 16.dp,
                            thickness = 6.dp,
                            shape = MaterialTheme.shapes.small,
                            hoverDurationMillis = 300,
                            unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        ),
                    )
                    // Horizontal scrollbar — always visible at the bottom, never scrolls away
                    HorizontalScrollbar(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(end = 10.dp), // room for vertical scrollbar
                        adapter = rememberScrollbarAdapter(hScrollState),
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

            is ViewerState.TooLarge -> viewerPlaceholder(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(32.dp),
                    )
                },
                message = stringResource("file.viewer.too.large", state.sizeKb),
                actionLabel = stringResource("file.viewer.open.external"),
                onAction = { openFileExternally(node.path) },
            )

            ViewerState.Binary -> viewerPlaceholder(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                },
                message = stringResource("file.viewer.binary"),
                actionLabel = stringResource("file.viewer.open.external"),
                onAction = { openFileExternally(node.path) },
            )

            is ViewerState.Error -> viewerPlaceholder(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                },
                message = stringResource("file.viewer.error", state.message),
                actionLabel = stringResource("file.viewer.open.external"),
                onAction = { openFileExternally(node.path) },
            )
        }
    }
}

/** Generic placeholder used for binary / too-large / error states. */
@Composable
private fun viewerPlaceholder(
    icon: @Composable () -> Unit,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon()
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onAction,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Opens a file in the OS file browser (local to this file). */
private fun openFileExternally(path: String) {
    try {
        val file = File(path)
        if (file.exists() && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    } catch (e: Exception) {
        log.warn("Failed to open file externally: {}", path, e)
    }
}

/** Copies text to the system clipboard (local to this file). */
private fun copyTextToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (e: Exception) {
        log.warn("Failed to copy text to clipboard", e)
    }
}

/** Reads file content and returns the appropriate [ViewerState]. Must run on IO dispatcher. */
private fun loadFileContent(path: String): ViewerState {
    return try {
        val file = File(path)
        if (!file.exists() || !file.isFile) return ViewerState.Error("File not found")

        val ext = file.extension.lowercase()
        if (ext in BINARY_EXTENSIONS) return ViewerState.Binary

        val sizeBytes = file.length()
        if (sizeBytes > MAX_PREVIEW_BYTES) return ViewerState.TooLarge(sizeBytes / 1024)

        val text = file.readText(Charsets.UTF_8)
        ViewerState.Content(text = text, lineCount = text.lines().size)
    } catch (e: Exception) {
        ViewerState.Error(e.message ?: "Unknown error")
    }
}
