/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.askimo.core.event.Event
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import java.awt.Cursor

/**
 * Modifier extension to detect hover state
 */
private fun Modifier.detectHover(onHoverChange: (Boolean) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            when (event.type) {
                PointerEventType.Enter -> {
                    onHoverChange(true)
                }

                PointerEventType.Exit -> {
                    onHoverChange(false)
                }
            }
        }
    }
}

/**
 * Modifier extension to add resize drag gesture with cumulative tracking
 */
private fun Modifier.resizeDrag(
    currentSize: Dp,
    density: Density,
    onSizeChange: (Dp) -> Unit,
    extractDragAmount: (Offset) -> Float,
    minSize: Dp,
    maxSize: Dp,
): Modifier = this.pointerInput(Unit) {
    var totalDrag = 0f
    var startSize = currentSize

    detectDragGestures(
        onDragStart = {
            startSize = currentSize
            totalDrag = 0f
        },
        onDragEnd = {
            totalDrag = 0f
        },
        onDrag = { change, dragAmount ->
            change.consume()
            totalDrag += extractDragAmount(dragAmount)
            val dragDp = with(density) { totalDrag.toDp() }
            val newSize = (startSize + dragDp).coerceIn(minSize, maxSize)
            onSizeChange(newSize)
        },
    )
}

/**
 * Reusable resize handle component
 */
@Composable
private fun resizeHandle(
    size: Dp,
    onSizeChange: (Dp) -> Unit,
    density: Density,
    isHorizontal: Boolean,
    cursor: Int,
    extractDragAmount: (Offset) -> Float,
    minSize: Dp,
    maxSize: Dp,
) {
    var isHovering by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .then(
                if (isHorizontal) {
                    Modifier.fillMaxWidth().height(Spacing.small)
                } else {
                    Modifier.fillMaxHeight().width(Spacing.small)
                },
            )
            .background(
                if (isHovering) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                },
            )
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
            .detectHover { isHovering = it }
            .resizeDrag(
                currentSize = size,
                density = density,
                onSizeChange = onSizeChange,
                extractDragAmount = extractDragAmount,
                minSize = minSize,
                maxSize = maxSize,
            ),
    )
}

/**
 * Dockable Event Log Panel - Shows ONLY developer events (isDeveloperEvent = true).
 *
 * This panel displays:
 * - API calls and responses
 * - Debug logs
 * - State changes
 * - Internal system events
 *
 * User events are shown in the notification icon popup instead.
 * Can be docked at bottom, left, or right of main window, or detached to separate window.
 * Similar to Chrome DevTools console.
 */
@Composable
fun eventLogPanel(
    events: SnapshotStateList<Event>,
    onDetach: () -> Unit,
    onClose: () -> Unit,
    onClearEvents: () -> Unit,
    onDockPositionChange: (EventLogDockPosition) -> Unit,
    currentDockPosition: EventLogDockPosition,
    size: Dp,
    onSizeChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    val content = @Composable { contentModifier: Modifier ->
        eventLogPanelContent(
            events = events,
            onDetach = onDetach,
            onClose = onClose,
            onClearEvents = onClearEvents,
            onDockPositionChange = onDockPositionChange,
            currentDockPosition = currentDockPosition,
            modifier = contentModifier,
        )
    }

    when (currentDockPosition) {
        EventLogDockPosition.BOTTOM -> {
            Column(modifier = modifier) {
                resizeHandle(
                    size = size,
                    onSizeChange = onSizeChange,
                    density = density,
                    isHorizontal = true,
                    cursor = Cursor.N_RESIZE_CURSOR,
                    extractDragAmount = { -it.y },
                    minSize = 100.dp,
                    maxSize = 600.dp,
                )
                content(Modifier.fillMaxWidth().height(size))
            }
        }

        EventLogDockPosition.LEFT -> {
            Row(modifier = modifier) {
                content(Modifier.fillMaxHeight().width(size))
                resizeHandle(
                    size = size,
                    onSizeChange = onSizeChange,
                    density = density,
                    isHorizontal = false,
                    cursor = Cursor.E_RESIZE_CURSOR,
                    extractDragAmount = { it.x }, // Drag right to increase
                    minSize = 200.dp,
                    maxSize = 800.dp,
                )
            }
        }

        EventLogDockPosition.RIGHT -> {
            Row(modifier = modifier) {
                resizeHandle(
                    size = size,
                    onSizeChange = onSizeChange,
                    density = density,
                    isHorizontal = false,
                    cursor = Cursor.W_RESIZE_CURSOR,
                    extractDragAmount = { -it.x }, // Drag left to increase
                    minSize = 200.dp,
                    maxSize = 800.dp,
                )
                content(Modifier.fillMaxHeight().width(size))
            }
        }
    }
}

/**
 * The actual event log panel content (separated for reuse with resize handles)
 */
@Composable
private fun eventLogPanelContent(
    events: SnapshotStateList<Event>,
    onDetach: () -> Unit,
    onClose: () -> Unit,
    onClearEvents: () -> Unit,
    onDockPositionChange: (EventLogDockPosition) -> Unit,
    currentDockPosition: EventLogDockPosition,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.small),
    ) {
        // Header with controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("eventlog.title", events.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                if (events.isNotEmpty()) {
                    IconButton(
                        onClick = onClearEvents,
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource("eventlog.clear"),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Dock position menu
                var showDockMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showDockMenu = true },
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Icon(
                            imageVector = when (currentDockPosition) {
                                EventLogDockPosition.BOTTOM -> Icons.Default.HorizontalSplit
                                EventLogDockPosition.LEFT -> Icons.Default.VerticalSplit
                                EventLogDockPosition.RIGHT -> Icons.Default.VerticalSplit
                            },
                            contentDescription = stringResource("eventlog.dock.change"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = when (currentDockPosition) {
                                EventLogDockPosition.BOTTOM -> Modifier.graphicsLayer(rotationZ = 180f)
                                EventLogDockPosition.RIGHT -> Modifier.graphicsLayer(rotationZ = 180f)
                                EventLogDockPosition.LEFT -> Modifier
                            },
                        )
                    }
                    DropdownMenu(
                        expanded = showDockMenu,
                        onDismissRequest = { showDockMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource("eventlog.dock.bottom")) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.HorizontalSplit,
                                    contentDescription = null,
                                    modifier = Modifier.graphicsLayer(rotationZ = 180f),
                                )
                            },
                            onClick = {
                                onDockPositionChange(EventLogDockPosition.BOTTOM)
                                showDockMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("eventlog.dock.left")) },
                            leadingIcon = {
                                Icon(Icons.Default.VerticalSplit, contentDescription = null)
                            },
                            onClick = {
                                onDockPositionChange(EventLogDockPosition.LEFT)
                                showDockMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("eventlog.dock.right")) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.VerticalSplit,
                                    contentDescription = null,
                                    modifier = Modifier.graphicsLayer(rotationZ = 180f),
                                )
                            },
                            onClick = {
                                onDockPositionChange(EventLogDockPosition.RIGHT)
                                showDockMenu = false
                            },
                        )
                    }
                }

                // Detach button
                IconButton(
                    onClick = onDetach,
                    modifier = Modifier.padding(0.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource("eventlog.detach"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.padding(0.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource("eventlog.close"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Event list
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource("eventlog.empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            val listState = rememberLazyListState()

            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    itemsIndexed(events) { index, event ->
                        eventLogCompactItem(event, isEven = index % 2 == 0)
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                    style = AppComponents.scrollbarStyle(),
                )
            }
        }
    }
}

/**
 * Compact event item for the docked panel.
 */
@Composable
private fun eventLogCompactItem(event: Event, isEven: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEven) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = event::class.simpleName ?: stringResource("eventlog.unknown"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = TimeUtil.formatInstantDisplay(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                Text(
                    text = event.getDetails(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    text = event.source.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = Spacing.extraSmall),
                )
            }
        }
    }
}
