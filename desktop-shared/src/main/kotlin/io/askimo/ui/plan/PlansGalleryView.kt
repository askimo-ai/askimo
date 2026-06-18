/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.plan

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.plan.domain.PlanDef
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.clickableCard
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip
import java.awt.Desktop
import java.net.URI

@Composable
fun plansGalleryView(
    viewModel: PlansViewModel,
    onSelectPlan: (PlanDef) -> Unit,
    onNewPlan: () -> Unit = {},
    onEditPlan: (String) -> Unit = {},
    onDuplicatePlan: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.loadPlans() }

    val scrollState = rememberScrollState()

    LaunchedEffect(viewModel.galleryShowAll) { scrollState.scrollTo(0) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 36.dp, top = 24.dp, bottom = 24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("plans.title"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        themedTooltip(text = stringResource("plans.docs.tooltip")) {
                            IconButton(
                                onClick = {
                                    runCatching {
                                        Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/plans/"))
                                    }
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = stringResource("plans.docs.tooltip"),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                        IconButton(
                            onClick = onNewPlan,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource("plans.editor.title.new"),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.loadPlans() },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource("action.refresh"),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }

                Text(
                    text = stringResource("plans.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )

                val builtInCount = viewModel.plans.count { it.builtIn }
                val myPlansCount = viewModel.plans.count { !it.builtIn }
                val selectedTabIndex = if (viewModel.galleryShowAll) 0 else 1

                Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        planTab(
                            label = stringResource("plans.tab.builtin"),
                            count = builtInCount,
                            selected = selectedTabIndex == 0,
                            onClick = { viewModel.selectGalleryTab(true) },
                            modifier = Modifier.weight(1f),
                        )
                        planTab(
                            label = stringResource("plans.tab.my.plans"),
                            count = myPlansCount,
                            selected = selectedTabIndex == 1,
                            onClick = { viewModel.selectGalleryTab(false) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }

                Spacer(modifier = Modifier.height(Spacing.large))

                if (viewModel.isLoadingPlans) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    return@Column
                }

                viewModel.plansError?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.large),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(Spacing.large),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                // Filter plans by tab
                val visiblePlans = if (viewModel.galleryShowAll) {
                    viewModel.plans.filter { it.builtIn }
                } else {
                    viewModel.plans.filter { !it.builtIn }
                }

                if (visiblePlans.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (viewModel.galleryShowAll) "📋" else "🤖",
                                style = MaterialTheme.typography.displayMedium,
                            )
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Text(
                                text = if (viewModel.galleryShowAll) {
                                    stringResource("plans.empty")
                                } else {
                                    stringResource("plans.tab.my.plans.empty")
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!viewModel.galleryShowAll) {
                                Spacer(modifier = Modifier.height(Spacing.small))
                                Text(
                                    text = stringResource("plans.tab.my.plans.empty.hint"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                    return@Column
                }

                visiblePlans.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                            .padding(bottom = Spacing.large),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                    ) {
                        row.forEach { plan ->
                            planCard(
                                plan = plan,
                                onSelect = { onSelectPlan(plan) },
                                onDelete = if (!plan.builtIn) {
                                    { viewModel.deletePlan(plan.id) }
                                } else {
                                    null
                                },
                                onEdit = if (!plan.builtIn) {
                                    { onEditPlan(plan.id) }
                                } else {
                                    null
                                },
                                onDuplicate = { onDuplicatePlan(plan.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = Spacing.extraSmall),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

@Composable
private fun planTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onBackground
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = Spacing.medium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
            )
            if (count > 0) planCountBadge(count, selected)
        }

        if (selected) {
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .fillMaxWidth(0.5f)
                    .background(
                        color = MaterialTheme.colorScheme.onBackground,
                        shape = MaterialTheme.shapes.extraSmall,
                    ),
            )
        } else {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun planCountBadge(count: Int, isSelected: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun planCard(
    plan: PlanDef,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showMenu by remember { mutableStateOf(false) }

    clickableCard(
        onClick = onSelect,
        // fillMaxHeight stretches the card to match the tallest card in the row (IntrinsicSize.Max)
        // hoverable connects isHovered so the play icon tint responds to cursor position
        modifier = modifier.fillMaxHeight().hoverable(interactionSource),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(Spacing.large),
        ) {
            // ── Icon + title (wrapping) + menu — all on one line ───────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = plan.icon.ifBlank { "📋" },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                Text(
                    text = plan.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                if (onDelete != null || onEdit != null || onDuplicate != null) {
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        AppComponents.dropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            if (onDuplicate != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource("plans.duplicate")) },
                                    leadingIcon = {
                                        Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurface)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onDuplicate()
                                    },
                                    colors = AppComponents.menuItemColors(),
                                )
                            }
                            if (onEdit != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource("plans.editor.menu.edit")) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurface)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onEdit()
                                    },
                                    colors = AppComponents.menuItemColors(),
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource("action.delete")) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onDelete()
                                    },
                                    colors = AppComponents.menuItemColors(),
                                )
                            }
                        }
                    }
                }
            }

            // ── Description ───────────────────────────────────────────────
            if (plan.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.small))
                themedTooltip(text = plan.description) {
                    Text(
                        text = plan.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── Push steps/play row to the bottom of every card ───────────
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(Spacing.medium))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${plan.steps.size} ${if (plan.steps.size == 1) "step" else "steps"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource("plans.run"),
                    tint = if (isHovered) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
