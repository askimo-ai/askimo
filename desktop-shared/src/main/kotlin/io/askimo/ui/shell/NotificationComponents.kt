/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import io.askimo.core.event.Event
import io.askimo.core.event.EventBus
import io.askimo.core.event.system.ShellErrorEvent
import io.askimo.core.event.system.UpdateAvailableEvent
import io.askimo.core.util.TimeUtil.formatInstantDisplay
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.AccountPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import java.awt.Desktop
import java.net.URI

/**
 * Wrapper to give each notification event a stable unique key for [LazyColumn].
 */
data class NotificationEventItem(
    val id: String,
    val event: Event,
)

/**
 * Notification bell icon displayed in the footer bar.
 *
 * Subscribes to [EventBus.userEvents], accumulates up to 100 events, and shows
 * a badge with the unread count. Clicking the icon toggles a [notificationPopup].
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

    LaunchedEffect(Unit) {
        EventBus.userEvents.collect { event ->
            val uniqueId = "${eventCounter++}_${event.timestamp.toEpochMilli()}"
            events.add(0, NotificationEventItem(uniqueId, event))
            unreadCount++
            if (events.size > 100) {
                events.removeAt(100)
            }
            // Auto-open the notification popup when a new version is detected,
            // unless the user has already dismissed this exact version.
            if (event is UpdateAvailableEvent &&
                AccountPreferences.device().getDismissedUpdateVersion() != event.latestVersion
            ) {
                showEventPopup = true
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
                    style = MaterialTheme.typography.labelSmall,
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
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
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
                text = "Notifications (${events.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (events.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource("event.notification.clear.all"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        HorizontalDivider()

        if (events.isEmpty()) {
            Text(
                text = stringResource("event.notification.empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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

/**
 * A single notification card inside [notificationPopup].
 *
 * Displays the event name, timestamp, details text, and an optional "Details"
 * action button for [UpdateAvailableEvent]s.
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

    val eventName = when (event) {
        is UpdateAvailableEvent -> stringResource("event.update.available")
        is ShellErrorEvent -> stringResource("event.shell.error")
        else -> event::class.simpleName ?: "Unknown"
    }

    val cardColors = when {
        isUpdateEvent -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        isShellError -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )

        else -> AppComponents.surfaceVariantCardColors()
    }

    val contentColor = when {
        isUpdateEvent -> MaterialTheme.colorScheme.onSecondaryContainer
        isShellError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = eventName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )

                TextButton(
                    onClick = onRemoveEvent,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource("event.notification.clear"),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                }
            }

            // Version badge for update events
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
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }

            Text(
                text = formatInstantDisplay(event.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
            )

            if (!isUpdateEvent) {
                SelectionContainer {
                    Text(
                        text = event.getDetails(),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                }
            }

            // Expandable technical details for ShellErrorEvent — lets users copy & report
            if (isShellError) {
                var showCause by remember { mutableStateOf(false) }

                TextButton(
                    onClick = { showCause = !showCause },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (showCause) {
                            stringResource("event.shell.error.cause.hide")
                        } else {
                            stringResource("event.shell.error.cause.show")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                }

                if (showCause) {
                    SelectionContainer {
                        Text(
                            text = event.cause.stackTraceToString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    }
                }
            }

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
                            style = MaterialTheme.typography.labelMedium,
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
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
