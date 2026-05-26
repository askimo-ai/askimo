/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.clickableCard
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip
import java.awt.Desktop
import java.net.URI

@Composable
internal fun skillsListContent(
    skills: List<SkillDefinition>,
    onSelectSkill: (SkillDefinition) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSkillsSettings: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(skills, searchQuery) {
        if (searchQuery.isBlank()) {
            skills
        } else {
            val q = searchQuery.trim().lowercase()
            skills.filter {
                it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.category.lowercase().contains(q)
            }
        }
    }

    val grouped: Map<String, List<SkillDefinition>> = remember(filtered) {
        val sortLast = "\u10FFFF" // Unicode max code point — sorts uncategorised items last
        filtered
            .groupBy { it.categoryPath.firstOrNull() ?: "" }
            .entries
            .sortedWith(compareBy { if (it.key.isEmpty()) sortLast else it.key })
            .associate { it.key to it.value }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // ── Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource("skills.view.title"),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        val runtimes = ExternalAgentLoader.displayNames()
                        val runtimesLabel = runtimes.mapIndexed { i, r ->
                            if (i == runtimes.lastIndex) "or $r" else r
                        }.joinToString(", ")
                        Text(
                            text = stringResource("skills.view.description", runtimesLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        themedTooltip(text = stringResource("skills.view.docs.tooltip")) {
                            IconButton(
                                onClick = { runCatching { Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/skills/")) } },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = stringResource("skills.view.docs.tooltip"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        themedTooltip(text = stringResource("skills.view.settings.tooltip")) {
                            IconButton(
                                onClick = onNavigateToSkillsSettings,
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = stringResource("skills.view.settings.tooltip"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        themedTooltip(text = stringResource("action.refresh")) {
                            IconButton(onClick = onRefresh, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource("action.refresh"),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.large))

                // ── Search bar ──
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource("skills.view.search.placeholder"), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(18.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource("skills.view.search.clear"), modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                Spacer(modifier = Modifier.height(Spacing.large))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(Spacing.large))

                if (filtered.isEmpty()) {
                    skillsGridEmptyState(hasQuery = searchQuery.isNotBlank())
                } else {
                    val expandedCategories = remember(grouped.keys) {
                        mutableStateOf(grouped.keys.toSet())
                    }

                    grouped.forEach { (topCategory, groupSkills) ->
                        val isCategoryExpanded = topCategory in expandedCategories.value
                        val categoryLabel = topCategory.ifEmpty { stringResource("skills.view.category.general") }

                        // ── Category header ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable {
                                    expandedCategories.value = if (isCategoryExpanded) {
                                        expandedCategories.value - topCategory
                                    } else {
                                        expandedCategories.value + topCategory
                                    }
                                }
                                .padding(vertical = Spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = categoryLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "(${groupSkills.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                            Icon(
                                if (isCategoryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // ── Skill cards (collapsible) ──
                        if (isCategoryExpanded) {
                            Spacer(modifier = Modifier.height(Spacing.small))
                            val columns = 2
                            val gapDp = Spacing.medium
                            groupSkills.chunked(columns).forEach { rowSkills ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(gapDp),
                                ) {
                                    rowSkills.forEach { skill ->
                                        skillCard(
                                            skill = skill,
                                            onClick = { onSelectSkill(skill) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(columns - rowSkills.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(modifier = Modifier.height(gapDp))
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.medium))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(Spacing.medium))
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
            ),
        )
    }
}

@Composable
private fun skillsGridEmptyState(hasQuery: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Icon(
                if (hasQuery) Icons.Default.Search else Icons.Default.Extension,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            )
            Text(
                if (hasQuery) stringResource("skills.view.empty.search") else stringResource("skills.view.empty"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (!hasQuery) {
                Text(
                    stringResource("skills.view.empty.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
private fun skillCard(
    skill: SkillDefinition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    clickableCard(
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text("🤖", style = MaterialTheme.typography.titleLarge)
                themedTooltip(text = skill.name, modifier = Modifier.weight(1f)) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (skill.description.isNotBlank()) {
                themedTooltip(text = skill.description) {
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(Spacing.small))
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val immediateParent = skill.categoryPath.lastOrNull()
                if (immediateParent != null) {
                    themedTooltip(text = skill.categoryPath.joinToString(" / ")) {
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    shape = MaterialTheme.shapes.extraSmall,
                                )
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                                    shape = MaterialTheme.shapes.extraSmall,
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                immediateParent,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource("skills.view.run"),
                    modifier = Modifier.size(18.dp),
                    tint = if (isHovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
        }
    }
}
