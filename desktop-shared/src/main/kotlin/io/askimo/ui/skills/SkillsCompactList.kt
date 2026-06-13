/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing

/**
 * Compact single-column skill list for the right rail "Skills" tab.
 *
 * Shows a search bar and a flat, scrollable list of all skills grouped by category.
 * The active skill (if any) is highlighted. Clicking an item calls [onSelectSkill].
 */
@Composable
internal fun skillsCompactList(
    skills: List<SkillDefinition>,
    selectedSkill: SkillDefinition?,
    onSelectSkill: (SkillDefinition) -> Unit,
) {
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
    val groupedSkills = remember(filtered) {
        filtered
            .groupBy { it.category.trim() }
            .toList()
            .sortedWith(
                compareBy(
                    { if (it.first.isBlank()) 1 else 0 },
                    { it.first.lowercase() },
                ),
            )
            .map { (category, entries) ->
                category to entries.sortedBy { it.name.lowercase() }
            }
    }
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource("skills.view.title"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
        )

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource("skills.view.search.placeholder"), style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(16.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource("skills.view.search.clear"), modifier = Modifier.size(14.dp))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            colors = AppComponents.outlinedTextFieldColors(),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Icon(
                        if (searchQuery.isBlank()) Icons.Default.Extension else Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    )
                    Text(
                        if (searchQuery.isBlank()) stringResource("skills.view.empty") else stringResource("skills.view.empty.search"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(vertical = Spacing.extraSmall),
                ) {
                    groupedSkills.forEach { (category, entries) ->
                        val categoryLabel = if (category.isBlank()) stringResource("skills.view.category.uncategorized") else category
                        val isExpanded = if (searchQuery.isNotBlank()) true else expandedCategories[category] ?: true

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val current = expandedCategories[category] ?: true
                                    expandedCategories[category] = !current
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Icon(
                                if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = categoryLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "(${entries.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (isExpanded) {
                            entries.forEach { skill ->
                                val isActive = skill.relativePath == selectedSkill?.relativePath
                                val interactionSource = remember { MutableInteractionSource() }
                                val isHovered by interactionSource.collectIsHoveredAsState()

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .hoverable(interactionSource)
                                        .clickable { onSelectSkill(skill) }
                                        .background(
                                            when {
                                                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                                isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                                else -> Color.Transparent
                                            },
                                        )
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            skill.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (skill.description.isNotBlank()) {
                                            Text(
                                                skill.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (isActive) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.07f))
                            }
                        } else {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.07f))
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                    style = AppComponents.scrollbarStyle(),
                )
            }
        }
    }
}
