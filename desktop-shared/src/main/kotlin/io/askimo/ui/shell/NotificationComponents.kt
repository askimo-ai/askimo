/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import io.askimo.core.event.Event
import io.askimo.core.event.EventBus
import io.askimo.core.event.system.ShellErrorEvent
import io.askimo.core.event.system.UpdateAvailableEvent
import io.askimo.core.event.user.FileRemovedFromIndexEvent
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingQueuedEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.util.TimeUtil.formatInstantDisplay
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.AccountPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import java.awt.Desktop
import java.net.URI

/**
 * Wrapper to give each notification event a stable unique key for [LazyColumn].
 *
 * [projectId] is non-null for indexing events and is used as the deduplication key
 * so that in-progress cards replace each other rather than stacking.
 */
data class NotificationEventItem(
    val id: String,
    val event: Event,
    val projectId: String? = null,
)

/**
 * Notification bell icon displayed in the footer bar.
 *
 * Subscribes to [EventBus.userEvents] and [EventBus.internalEvents], accumulates up to
 * 100 events, and shows a badge with the unread count. Clicking the icon toggles a
 * [notificationPopup].
 *
 * Shared between the community desktop app and the Pro (askimo-app) edition.
 *
 * @param onShowUpdateDetails Called when the user clicks "Details" on an [UpdateAvailableEvent].
 */
@Composable
fun notificationIcon(onShowUpdateDetails: () -> Unit) {
    var showEventPopup by remember { mutableStateOf(false) }
    val events = remember { mutableStateListOf<NotificationEventItem>() }
    var unreadCount by remember { mutableStateOf(0) }
    var eventCounter by remember { mutableStateOf(0) }

    // Single enforcement point for the 100-item cap — called after every mutation.
    fun trimEvents() {
        while (events.size > 100) events.removeAt(events.lastIndex)
    }

    // Upserts a terminal indexing card (completed / failed) and bumps the unread badge
    // only when a new card is added — not when replacing an existing in-progress card.
    fun upsertIndexingEvent(item: NotificationEventItem) {
        val existingIdx = events.indexOfFirst { it.projectId == item.projectId }
        if (existingIdx >= 0) {
            events[existingIdx] = item
            // Replace in-place — card was already counted, don't bump badge
        } else {
            events.add(0, item)
            unreadCount++
            trimEvents()
        }
    }

    // User-facing events (UpdateAvailableEvent, ShellErrorEvent, …)
    LaunchedEffect(Unit) {
        EventBus.userEvents.collect { event ->
            val uniqueId = "${eventCounter++}_${event.timestamp.toEpochMilli()}"
            events.add(0, NotificationEventItem(uniqueId, event))
            unreadCount++
            trimEvents()

            if (event is UpdateAvailableEvent &&
                AccountPreferences.device().getDismissedUpdateVersion() != event.latestVersion
            ) {
                showEventPopup = true
            }
            if (event is ShellErrorEvent) {
                showEventPopup = true
            }
        }
    }

    // Indexing events live on internalEvents — deduplicated by projectId.
    LaunchedEffect(Unit) {
        EventBus.internalEvents.collect { event ->
            when (event) {
                is IndexingQueuedEvent,
                is IndexingStartedEvent,
                -> {
                    val projectId = when (event) {
                        is IndexingQueuedEvent -> event.projectId
                        else -> (event as IndexingStartedEvent).projectId
                    }
                    val existingIdx = events.indexOfFirst { it.projectId == projectId }
                    val item = NotificationEventItem(
                        id = "indexing_$projectId",
                        event = event,
                        projectId = projectId,
                    )
                    if (existingIdx >= 0) {
                        // Replace in-place — card was already counted, don't bump badge
                        events[existingIdx] = item
                    } else {
                        events.add(0, item)
                        unreadCount++
                        trimEvents()
                    }
                }

                is IndexingCompletedEvent -> {
                    upsertIndexingEvent(
                        NotificationEventItem(
                            id = "${eventCounter++}_${event.timestamp.toEpochMilli()}",
                            event = event,
                            projectId = event.projectId,
                        ),
                    )
                    // Badge-only — don't force-open popup
                }

                is IndexingFailedEvent -> {
                    upsertIndexingEvent(
                        NotificationEventItem(
                            id = "${eventCounter++}_${event.timestamp.toEpochMilli()}",
                            event = event,
                            projectId = event.projectId,
                        ),
                    )
                    showEventPopup = true // User must see failures
                }

                is FileRemovedFromIndexEvent -> {
                    // Each removal is its own notification — not deduped by projectId,
                    // since multiple files can be removed independently.
                    events.add(
                        0,
                        NotificationEventItem(
                            id = "${eventCounter++}_${event.timestamp.toEpochMilli()}",
                            event = event,
                            projectId = "removed_${event.projectId}_${event.fileName}_${event.timestamp.toEpochMilli()}",
                        ),
                    )
                    unreadCount++
                    trimEvents()
                }
            }
        }
    }

    Box {
        IconButton(
            onClick = {
                if (showEventPopup) {
                    // Closing — persist dismissed version
                    val latestUpdate = events.firstNotNullOfOrNull { it.event as? UpdateAvailableEvent }
                    if (latestUpdate != null) {
                        AccountPreferences.device().setDismissedUpdateVersion(latestUpdate.latestVersion)
                    }
                }
                showEventPopup = !showEventPopup
            },
            modifier = Modifier
                .size(32.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = "Events",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }

        if (unreadCount > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                    style = AppTextStyles.hint,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onError,
                    maxLines = 1,
                )
            }
        }

        if (showEventPopup) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, -40),
                onDismissRequest = {
                    showEventPopup = false
                    // Persist the latest update version so the popup won't auto-reopen for it
                    val latestUpdate = events.mapNotNull { it.event as? UpdateAvailableEvent }.firstOrNull()
                    if (latestUpdate != null) {
                        AccountPreferences.device().setDismissedUpdateVersion(latestUpdate.latestVersion)
                    }
                },
            ) {
                Card(
                    modifier = Modifier.padding(Spacing.small),
                    colors = CardDefaults.cardColors(
                        containerColor = AppComponents.popupContainerColor(),
                    ),
                    border = AppComponents.popupBorderStroke(),
                    elevation = CardDefaults.cardElevation(defaultElevation = AppComponents.popupElevation),
                ) {
                    notificationPopup(
                        events = events,
                        onShowUpdateDetails = onShowUpdateDetails,
                        onDismissPopup = { showEventPopup = false },
                        onRemoveEvent = { item ->
                            events.remove(item)
                            if (unreadCount > 0) unreadCount--
                        },
                        onClearAll = {
                            events.clear()
                            unreadCount = 0
                        },
                    )
                }
            }
        }
    }
}

/**
 * Popup content listing all accumulated [NotificationEventItem]s.
 *
 * Height adjusts dynamically up to [maxHeight] based on the number of items.
 */
@Composable
fun notificationPopup(
    events: List<NotificationEventItem>,
    onShowUpdateDetails: () -> Unit,
    onDismissPopup: () -> Unit,
    onRemoveEvent: (NotificationEventItem) -> Unit,
    onClearAll: () -> Unit,
) {
    val estimatedItemHeight = 160.dp
    val maxHeight = 800.dp
    val minHeight = 120.dp

    val dynamicHeight = remember(events.size) {
        val contentHeight = 60.dp + estimatedItemHeight * events.size.toFloat()
        when {
            contentHeight < minHeight -> minHeight
            contentHeight > maxHeight -> maxHeight
            else -> contentHeight
        }
    }

    Column(
        modifier = Modifier
            .width(560.dp)
            .padding(Spacing.small),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("event.notification.title", events.size),
                style = AppTextStyles.sectionTitle,
            )

            if (events.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource("event.notification.clear.all"),
                        style = AppTextStyles.fieldLabel,
                    )
                }
            }
        }

        HorizontalDivider()

        if (events.isEmpty()) {
            Text(
                text = stringResource("event.notification.empty"),
                style = AppTextStyles.body,
                modifier = Modifier.padding(Spacing.large),
            )
        } else {
            val listState = rememberLazyListState()

            // Pinned system notifications (updates, etc.) always on top
            val pinnedEvents = events.filter { it.event is UpdateAvailableEvent }
            // Background/error events below
            val otherEvents = events.filter { it.event !is UpdateAvailableEvent }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dynamicHeight)
                    .padding(top = Spacing.small),
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    // Pinned group
                    if (pinnedEvents.isNotEmpty()) {
                        items(
                            items = pinnedEvents,
                            key = { it.id },
                        ) { item ->
                            notificationEventCard(
                                event = item.event,
                                onShowUpdateDetails = onShowUpdateDetails,
                                onDismissPopup = onDismissPopup,
                                onRemoveEvent = { onRemoveEvent(item) },
                            )
                        }
                    }

                    // Divider between groups when both have content
                    if (pinnedEvents.isNotEmpty() && otherEvents.isNotEmpty()) {
                        item(key = "group-divider") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.extraSmall),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f))
                                Text(
                                    text = stringResource("event.notification.group.errors"),
                                    style = AppTextStyles.hint,
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Other events group
                    if (otherEvents.isNotEmpty()) {
                        items(
                            items = otherEvents,
                            key = { it.id },
                        ) { item ->
                            notificationEventCard(
                                event = item.event,
                                onShowUpdateDetails = onShowUpdateDetails,
                                onDismissPopup = onDismissPopup,
                                onRemoveEvent = { onRemoveEvent(item) },
                            )
                        }
                    }
                }

                if (estimatedItemHeight * events.size.toFloat() > maxHeight) {
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
}

@Composable
private fun expandErrorButton(expanded: Boolean, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (expanded) {
                stringResource("event.shell.error.cause.hide")
            } else {
                stringResource("event.shell.error.cause.show")
            },
            style = AppTextStyles.hint,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * A single notification card inside [notificationPopup].
 *
 * Handles [UpdateAvailableEvent], [ShellErrorEvent], and all four indexing events
 * ([IndexingQueuedEvent], [IndexingStartedEvent], [IndexingCompletedEvent], [IndexingFailedEvent]).
 */
@Composable
fun notificationEventCard(
    event: Event,
    onShowUpdateDetails: () -> Unit,
    onDismissPopup: () -> Unit,
    onRemoveEvent: () -> Unit,
) {
    val isUpdateEvent = event is UpdateAvailableEvent
    val isShellError = event is ShellErrorEvent
    val isIndexingQueued = event is IndexingQueuedEvent
    val isIndexingStarted = event is IndexingStartedEvent
    val isIndexingCompleted = event is IndexingCompletedEvent
    val isIndexingFailed = event is IndexingFailedEvent
    val isFileRemoved = event is FileRemovedFromIndexEvent
    val isIndexingEvent = isIndexingQueued || isIndexingStarted || isIndexingCompleted || isIndexingFailed || isFileRemoved

    val eventName = when (event) {
        is UpdateAvailableEvent -> stringResource("event.update.available")
        is ShellErrorEvent -> event.title ?: stringResource("event.shell.error")
        is IndexingQueuedEvent -> stringResource("event.indexing.queued")
        is IndexingStartedEvent -> stringResource("event.indexing.started")
        is IndexingCompletedEvent -> stringResource("event.indexing.completed")
        is IndexingFailedEvent -> stringResource("event.indexing.failed")
        is FileRemovedFromIndexEvent -> stringResource("event.file.removed")
        else -> event::class.simpleName ?: "Unknown"
    }

    val cardColors = when {
        isUpdateEvent -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        isIndexingCompleted -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )

        else -> AppComponents.surfaceVariantCardColors()
    }

    val contentColor = when {
        isUpdateEvent -> MaterialTheme.colorScheme.onSecondaryContainer
        isIndexingCompleted -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Animated rotation for the Sync spinner shown during IndexingStartedEvent
    val infiniteTransition = rememberInfiniteTransition(label = "sync-spin")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync-rotation",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            // ── Header row ──────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    when {
                        isShellError || isIndexingFailed -> Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )

                        isUpdateEvent -> Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp),
                        )

                        isIndexingQueued -> Icon(
                            imageVector = Icons.Outlined.HourglassEmpty,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp),
                        )

                        isIndexingStarted -> Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(rotationAngle),
                        )

                        isIndexingCompleted -> Icon(
                            imageVector = Icons.Outlined.CheckCircleOutline,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp),
                        )

                        isFileRemoved -> Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = eventName,
                        style = AppTextStyles.itemTitle,
                        color = contentColor,
                    )
                }

                TextButton(
                    onClick = onRemoveEvent,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource("event.notification.clear"),
                        style = AppTextStyles.hint,
                        color = contentColor,
                    )
                }
            }

            // ── Version badge (UpdateAvailableEvent) ────────────────────────────────
            if (event is UpdateAvailableEvent) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = contentColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "v${event.currentVersion} → v${event.latestVersion}",
                            style = AppTextStyles.fieldLabel,
                            color = contentColor,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }

            // ── Project name (indexing events) ──────────────────────────────────────
            if (isIndexingEvent) {
                val projectName = when (event) {
                    is IndexingQueuedEvent -> event.projectName
                    is IndexingStartedEvent -> event.projectName
                    is IndexingCompletedEvent -> event.projectName
                    is IndexingFailedEvent -> event.projectName
                    is FileRemovedFromIndexEvent -> event.projectName
                    else -> null
                }
                if (projectName != null) {
                    Text(
                        text = projectName,
                        style = AppTextStyles.fieldLabel,
                        color = contentColor,
                    )
                }
            }

            // ── Timestamp ───────────────────────────────────────────────────────────
            Text(
                text = formatInstantDisplay(event.timestamp),
                style = AppTextStyles.caption,
                color = contentColor.copy(alpha = 0.7f),
            )

            // ── Details / file count ────────────────────────────────────────────────
            if (!isUpdateEvent) {
                SelectionContainer {
                    Text(
                        text = if (isIndexingCompleted) {
                            stringResource("event.indexing.files_indexed", (event as IndexingCompletedEvent).filesIndexed)
                        } else {
                            event.getDetails()
                        },
                        style = AppTextStyles.caption,
                        color = contentColor,
                    )
                }
            }

            // ── Expandable stack trace (ShellErrorEvent) ────────────────────────────
            if (isShellError) {
                var showCause by remember { mutableStateOf(false) }
                expandErrorButton(expanded = showCause, onToggle = { showCause = !showCause })
                if (showCause) {
                    SelectionContainer {
                        Text(
                            text = event.cause.stackTraceToString(),
                            style = AppTextStyles.hint,
                            fontFamily = FontFamily.Monospace,
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            // ── Expandable error message (IndexingFailedEvent) ──────────────────────
            if (isIndexingFailed) {
                var showError by remember { mutableStateOf(false) }
                expandErrorButton(expanded = showError, onToggle = { showError = !showError })
                if (showError) {
                    SelectionContainer {
                        Text(
                            text = event.errorMessage,
                            style = AppTextStyles.hint,
                            fontFamily = FontFamily.Monospace,
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            // ── Update action buttons ───────────────────────────────────────────────
            if (event is UpdateAvailableEvent) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    linkButton(
                        onClick = {
                            onShowUpdateDetails()
                            onDismissPopup()
                        },
                    ) {
                        Text(
                            text = stringResource("event.details.action"),
                            style = AppTextStyles.fieldLabel,
                        )
                    }
                    primaryButton(
                        onClick = {
                            runCatching {
                                Desktop.getDesktop().browse(URI(event.downloadUrl))
                            }
                            onRemoveEvent()
                            onDismissPopup()
                        },
                    ) {
                        Text(
                            text = stringResource("event.update.download"),
                            style = AppTextStyles.fieldLabel,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
