/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui

import androidx.compose.foundation.HorizontalScrollbar
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
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private val log = currentFileLogger()

/** Maximum file size (512 KB) the viewer will load into memory. */
private const val FILE_PREVIEW_MAX_BYTES = 512 * 1024L

/** Extensions treated as binary / non-previewable. */
private val FILE_PREVIEW_BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "zip", "tar", "gz", "bz2", "7z", "rar",
    "exe", "dll", "so", "dylib", "class", "jar",
    "mp3", "mp4", "wav", "ogg", "flac", "avi", "mov",
    "woff", "woff2", "ttf", "otf", "eot",
    "bin", "dat", "db", "sqlite",
)

/** Loading state for the file preview pane. */
private sealed interface FilePreviewState {
    data object Loading : FilePreviewState
    data class Content(val text: String, val lineCount: Int) : FilePreviewState
    data class TooLarge(val sizeKb: Long) : FilePreviewState
    data object Binary : FilePreviewState
    data class Error(val message: String) : FilePreviewState
}

/**
 * Shared, reusable in-panel file preview pane.
 *
 * Shows the raw text content of a file at [path] in a scrollable, selectable monospace
 * view. Gracefully handles binary files, oversized files, and read errors with an
 * "Open externally" fallback action. Used by both the chat RAG source viewer and the
 * agent workspace file browser so preview behavior stays consistent — new call sites
 * should reuse this rather than re-implementing file preview logic.
 *
 * @param path        Absolute path of the file to preview.
 * @param displayName Name shown in the header (usually the file's simple name).
 * @param onClose     Called when the user dismisses the viewer.
 * @param modifier    Optional modifier applied to the outer column.
 */
@Composable
fun filePreviewPane(
    path: String,
    displayName: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewerState by remember(path) { mutableStateOf<FilePreviewState>(FilePreviewState.Loading) }

    // Load file content off the main thread whenever the selected path changes
    LaunchedEffect(path) {
        viewerState = FilePreviewState.Loading
        viewerState = withContext(Dispatchers.IO) { loadFilePreviewContent(path) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.surfaceColor(AppColors.Elevation.RECESSED)),
    ) {
        HorizontalDivider()

        // ── Header bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File name + line count
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = AppColors.secondaryIconColor(),
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = displayName,
                    style = AppTextStyles.fieldLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (viewerState is FilePreviewState.Content) {
                    Text(
                        text = stringResource(
                            "file.viewer.lines",
                            (viewerState as FilePreviewState.Content).lineCount,
                        ),
                        style = AppTextStyles.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                // Open in OS default editor — always available
                themedTooltip(text = stringResource("file.viewer.open.external")) {
                    IconButton(
                        onClick = { openFilePreviewExternally(path) },
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
                if (viewerState is FilePreviewState.Content) {
                    themedTooltip(text = stringResource("file.viewer.copy")) {
                        IconButton(
                            onClick = { copyFilePreviewToClipboard((viewerState as FilePreviewState.Content).text) },
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
            FilePreviewState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.extraLarge),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource("file.viewer.loading"),
                        style = AppTextStyles.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is FilePreviewState.Content -> {
                val language = File(path).extension.lowercase().takeIf { it.isNotEmpty() }
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
                        style = AppComponents.scrollbarStyle(),
                    )
                    // Horizontal scrollbar — always visible at the bottom, never scrolls away
                    HorizontalScrollbar(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(end = 10.dp), // room for vertical scrollbar
                        adapter = rememberScrollbarAdapter(hScrollState),
                        style = AppComponents.scrollbarStyle(),
                    )
                }
            }

            is FilePreviewState.TooLarge -> filePreviewPlaceholder(
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
                onAction = { openFilePreviewExternally(path) },
            )

            FilePreviewState.Binary -> filePreviewPlaceholder(
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
                onAction = { openFilePreviewExternally(path) },
            )

            is FilePreviewState.Error -> filePreviewPlaceholder(
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
                onAction = { openFilePreviewExternally(path) },
            )
        }
    }
}

/** Generic placeholder used for binary / too-large / error states. */
@Composable
private fun filePreviewPlaceholder(
    icon: @Composable () -> Unit,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(Spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            icon()
            Text(
                text = message,
                style = AppTextStyles.caption,
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
                    style = AppTextStyles.caption,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Opens a file in the OS file browser (local to this file). */
private fun openFilePreviewExternally(path: String) {
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
private fun copyFilePreviewToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (e: Exception) {
        log.warn("Failed to copy text to clipboard", e)
    }
}

/** Reads file content and returns the appropriate [FilePreviewState]. Must run on IO dispatcher. */
private fun loadFilePreviewContent(path: String): FilePreviewState {
    return try {
        val file = File(path)
        if (!file.exists() || !file.isFile) return FilePreviewState.Error("File not found")

        val ext = file.extension.lowercase()
        if (ext in FILE_PREVIEW_BINARY_EXTENSIONS) return FilePreviewState.Binary

        val sizeBytes = file.length()
        if (sizeBytes > FILE_PREVIEW_MAX_BYTES) return FilePreviewState.TooLarge(sizeBytes / 1024)

        val text = file.readText(Charsets.UTF_8)
        FilePreviewState.Content(text = text, lineCount = text.lines().size)
    } catch (e: Exception) {
        FilePreviewState.Error(e.message ?: "Unknown error")
    }
}
