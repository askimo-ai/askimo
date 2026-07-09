/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.plan

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.plan.domain.PlanExecution
import io.askimo.core.plan.domain.PlanExecutionStatus
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip
import java.awt.Cursor

/**
 * Collapsible right-side panel for plan run history,
 */
@Composable
fun planHistorySidePanel(
    executions: List<PlanExecution>,
    onDeleteExecution: (String) -> Unit,
    onRestoreInputs: (PlanExecution) -> Unit,
    onPinExecution: ((PlanExecution) -> Unit)? = null,
    pinnedExecutionId: String? = null,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var panelWidth by remember {
        mutableStateOf(ApplicationPreferences.getPlanHistorySidePanelWidth().dp)
    }

    val targetWidth = if (isExpanded) panelWidth else 56.dp
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 300),
    )

    Card(
        modifier = modifier.width(animatedWidth).fillMaxHeight(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = AppComponents.sidebarSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
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
                                    panelWidth = newWidth.coerceIn(240.dp, 600.dp)
                                },
                                onDragEnd = {
                                    ApplicationPreferences.setPlanHistorySidePanelWidth(panelWidth.value.toInt())
                                },
                            )
                        },
                )
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(Spacing.large),
                ) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource("plans.history.title"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (executions.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                            shape = MaterialTheme.shapes.extraSmall,
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "${executions.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { onExpandedChange(false) },
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = stringResource("panel.collapse"),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val scrollState = rememberScrollState()

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = Spacing.small) // room for scrollbar
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            if (executions.isEmpty()) {
                                Text(
                                    text = stringResource("plans.history.empty"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = Spacing.small),
                                )
                            } else {
                                executions.forEach { execution ->
                                    planHistoryItem(
                                        execution = execution,
                                        isPinned = execution.id == pinnedExecutionId,
                                        onDelete = { onDeleteExecution(execution.id) },
                                        onRestoreInputs = { onRestoreInputs(execution) },
                                        onPin = if (onPinExecution != null && execution.output != null) {
                                            { onPinExecution(execution) }
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            style = AppComponents.scrollbarStyle(),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = Spacing.large, horizontal = Spacing.small),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                themedTooltip(text = stringResource("plans.history.title")) {
                    IconButton(
                        onClick = { onExpandedChange(!isExpanded) },
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = if (isExpanded) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = MaterialTheme.shapes.small,
                            )
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = stringResource("plans.history.title"),
                            tint = if (isExpanded) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun planHistoryItem(
    execution: PlanExecution,
    isPinned: Boolean = false,
    onDelete: () -> Unit,
    onRestoreInputs: () -> Unit,
    onPin: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val tooltipText = buildString {
        append(stringResource("plans.history.restore"))
        val parts = mutableListOf<String>()
        execution.totalTokens?.let { parts += "Tokens: ${LocalizationManager.formatNumber(it)}" }
        execution.totalInputTokens?.let { parts += "In: ${LocalizationManager.formatNumber(it)}" }
        execution.totalOutputTokens?.let { parts += "Out: ${LocalizationManager.formatNumber(it)}" }
        execution.totalDurationMs?.let { ms ->
            parts += if (ms >= 1_000) "Time: ${"%.1f".format(ms / 1000.0)}s" else "Time: ${ms}ms"
        }
        if (parts.isNotEmpty()) {
            append("\n")
            append(parts.joinToString(" · "))
        }
    }

    themedTooltip(text = tooltipText) {
        Surface(
            modifier = modifier
                .hoverable(interactionSource)
                .clickable(onClick = onRestoreInputs)
                .pointerHoverIcon(PointerIcon.Hand),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    val statusColor = when (execution.status) {
                        PlanExecutionStatus.COMPLETED -> Color(0xFF4CAF50)
                        PlanExecutionStatus.FAILED -> MaterialTheme.colorScheme.error
                        PlanExecutionStatus.RUNNING -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, MaterialTheme.shapes.extraSmall),
                    )
                    Column {
                        Text(
                            text = execution.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = TimeUtil.formatDisplay(execution.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        if (execution.runCount > 1) {
                            Text(
                                text = "×${execution.runCount} runs",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }

                        if (execution.inputs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(Spacing.extraSmall))
                            execution.inputs.entries.take(3).forEach { (key, value) ->
                                val preview = value.trim().take(40).let { if (value.length > 40) "$it…" else it }
                                if (preview.isNotBlank()) {
                                    Text(
                                        text = "$key: $preview",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isHovered) {
                        if (onPin != null) {
                            themedTooltip(text = stringResource(if (isPinned) "plans.result.unpin" else "plans.result.pin")) {
                                IconButton(
                                    onClick = onPin,
                                    modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = stringResource(if (isPinned) "plans.result.unpin" else "plans.result.pin"),
                                        tint = if (isPinned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource("action.delete"),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
