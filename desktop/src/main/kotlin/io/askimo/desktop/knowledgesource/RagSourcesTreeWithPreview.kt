/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.knowledgesource

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.filePreviewPane
import java.awt.Cursor

/**
 * Combines [ragSourcesTree] with an in-app file preview pane in a resizable split layout —
 * clicking a file shows its contents below the tree (draggable divider) instead of opening
 * an external app.
 *
 * Shared between Project and Resource Collection detail views — neither depends on the
 * other, both depend only on this component.
 *
 * @param sources       Knowledge source configs to display.
 * @param indexedPaths  Normalized indexed file paths, for per-file status icons.
 * @param modifier      Should carry a bounded height (e.g. `Modifier.weight(1f)` in a
 *                      `Column`), since this composable fills its bounds.
 * @param onRemove      Called when the user removes a knowledge source.
 * @param onWatchToggle Called when toggling "watch for changes" on a top-level folder;
 *                      `null` to disable the toggle UI.
 */
@Composable
fun ragSourcesTreeWithPreview(
    sources: List<KnowledgeSourceConfig>,
    indexedPaths: Set<String>,
    modifier: Modifier = Modifier,
    onRemove: (KnowledgeSourceConfig) -> Unit = {},
    onWatchToggle: ((LocalFoldersKnowledgeSourceConfig, Boolean) -> Unit)? = null,
) {
    var selectedNode by remember(sources) { mutableStateOf<TreeNode?>(null) }
    var viewerHeightRatio by remember { mutableStateOf(ApplicationPreferences.getFileViewerHeightRatio()) }
    val viewedFile = selectedNode as? FileTreeNode

    BoxWithConstraints(modifier = modifier) {
        val totalHeightPx = constraints.maxHeight.toFloat()
        val handleHeightPx = with(LocalDensity.current) { 6.dp.toPx() }
        val treeHeightPx = if (viewedFile != null) {
            ((1f - viewerHeightRatio) * (totalHeightPx - handleHeightPx)).coerceAtLeast(60f)
        } else {
            totalHeightPx
        }
        val viewerHeightPx = (totalHeightPx - treeHeightPx - handleHeightPx).coerceAtLeast(60f)

        Column(modifier = Modifier.fillMaxSize()) {
            ragSourcesTree(
                sources = sources,
                indexedPaths = indexedPaths,
                modifier = Modifier
                    .height(with(LocalDensity.current) { treeHeightPx.toDp() })
                    .fillMaxWidth(),
                selectedNode = selectedNode,
                onNodeSelected = { node -> selectedNode = if (node == selectedNode) null else node },
                onRemove = { removedSource ->
                    if (selectedNode is FileTreeNode && (selectedNode as FileTreeNode).path == removedSource.resourceIdentifier) {
                        selectedNode = null
                    }
                    onRemove(removedSource)
                },
                onWatchToggle = onWatchToggle,
            )

            if (viewedFile != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
                        .pointerInput(totalHeightPx) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    viewerHeightRatio = (viewerHeightRatio - dragAmount.y / totalHeightPx).coerceIn(0.20f, 0.80f)
                                },
                                onDragEnd = { ApplicationPreferences.setFileViewerHeightRatio(viewerHeightRatio) },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.micro)) {
                        repeat(3) {
                            Box(modifier = Modifier.size(3.dp).background(AppColors.tertiaryIconColor(), CircleShape))
                        }
                    }
                }

                filePreviewPane(
                    path = viewedFile.path,
                    displayName = viewedFile.displayName,
                    onClose = { selectedNode = null },
                    modifier = Modifier
                        .height(with(LocalDensity.current) { viewerHeightPx.toDp() })
                        .fillMaxWidth(),
                )
            }
        }
    }
}
