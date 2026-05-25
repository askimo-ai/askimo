/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.skills.domain.SkillRunRecord
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedTooltip
import java.awt.Cursor
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val RUN_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun skillRunHistoryPanelRow(
    record: SkillRunRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    showSkillName: Boolean = true,
) {
    val isError = record.error != null
    val timeLabel = RUN_TIME_FMT.format(record.createdAt)
    val skillDisplayName = remember(record.skillPath) {
        record.skillPath.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    }
    val tooltipText = remember(record) {
        buildString {
            append(skillDisplayName)
            append(" · ")
            append(timeLabel)
            if (record.userInput.isNotBlank()) {
                append("\n")
                append(record.userInput)
            }
            if (isError) {
                append("\n⚠ ")
                append(record.error)
            }
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    themedTooltip(text = tooltipText, placement = TooltipPlacement.LEFT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .background(
                    if (isHovered) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                )
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Icon(
                if (isError) Icons.Default.Close else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                if (showSkillName) {
                    Text(
                        skillDisplayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.userInput.isNotBlank()) {
                    Text(
                        record.userInput.take(50) + if (record.userInput.length > 50) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(28.dp)
                    .alpha(if (isHovered) 1f else 0f)
                    .pointerHoverIcon(PointerIcon.Hand),
                enabled = isHovered,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete record",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
internal fun skillsHistoryPanel(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    runHistory: List<SkillRunRecord> = emptyList(),
    filterSkillName: String? = null,
    onSelectRecord: (SkillRunRecord) -> Unit = {},
    onDeleteRecord: (SkillRunRecord) -> Unit = {},
) {
    var panelWidth by remember {
        mutableStateOf(ApplicationPreferences.getSkillsSidePanelWidth().dp)
    }

    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) panelWidth else 56.dp,
        animationSpec = tween(durationMillis = 300),
    )

    Card(
        modifier = Modifier.width(animatedWidth).fillMaxHeight(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = AppComponents.sidebarSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── Drag-to-resize handle ─────────────────────────────────────────
            if (isExpanded) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val newWidth = panelWidth - dragAmount.x.toDp()
                                    panelWidth = newWidth.coerceIn(220.dp, 560.dp)
                                },
                                onDragEnd = {
                                    ApplicationPreferences.setSkillsSidePanelWidth(panelWidth.value.toInt())
                                },
                            )
                        },
                )
            }

            // ── Expanded content ──────────────────────────────────────────────
            if (isExpanded) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                filterSkillName ?: stringResource("skills.view.history.title"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (runHistory.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), shape = MaterialTheme.shapes.extraSmall)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text("${runHistory.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        IconButton(onClick = { onExpandedChange(false) }, modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(Icons.Default.Remove, contentDescription = stringResource("skills.view.history.collapse"), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider()

                    if (runHistory.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                Icon(Icons.Default.History, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                                Text(stringResource("skills.view.history.empty"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        }
                    } else {
                        val panelScrollState = rememberScrollState()
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(panelScrollState)
                                    .padding(vertical = 4.dp),
                            ) {
                                runHistory.forEach { record ->
                                    skillRunHistoryPanelRow(
                                        record = record,
                                        showSkillName = filterSkillName == null,
                                        onClick = { onSelectRecord(record) },
                                        onDelete = { onDeleteRecord(record) },
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                                }
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(panelScrollState),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                                style = ScrollbarStyle(
                                    minimalHeight = 16.dp,
                                    thickness = 6.dp,
                                    shape = MaterialTheme.shapes.small,
                                    hoverDurationMillis = 300,
                                    unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
                                ),
                            )
                        }
                    }
                }
            }

            // ── Icon bar (always visible) ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                themedTooltip(text = stringResource("skills.view.history.title")) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isExpanded) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                },
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onExpandedChange(!isExpanded) },
                            )
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource("skills.view.history.title"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
