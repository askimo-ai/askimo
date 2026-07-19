/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.core.mcp.McpServerDefinition
import io.askimo.core.mcp.McpServerTemplateRegistry
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing

/**
 * Two-panel dialog for picking an MCP server from the built-in catalog or opting for manual setup.
 *
 * @param onSelectTemplate  Called with a pre-filled [McpServerDefinition] when the user picks a catalog entry.
 * @param onManualSetup     Called when the user wants to configure a server from scratch.
 * @param onDismiss         Called when the dialog is closed without a selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun mcpServerCatalogDialog(
    onSelectTemplate: (McpServerDefinition) -> Unit,
    onManualSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val visibleTemplates = remember(searchQuery, selectedCategory) {
        McpServerTemplateRegistry.getByCategory(selectedCategory)
            .let { list ->
                if (searchQuery.isBlank()) {
                    list
                } else {
                    val q = searchQuery.trim().lowercase()
                    list.filter {
                        it.name.lowercase().contains(q) ||
                            it.description.lowercase().contains(q) ||
                            it.tags.any { t -> t.contains(q) }
                    }
                }
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 700.dp, max = 900.dp)
                .heightIn(min = 700.dp, max = 900.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Header ────────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.extraLarge, end = Spacing.extraLarge, top = Spacing.extraLarge, bottom = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        text = stringResource("mcp.catalog.dialog.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource("mcp.catalog.dialog.description"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.medium))

                // ── Search + category filters ──────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource("mcp.catalog.search.placeholder")) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = AppComponents.outlinedTextFieldColors(),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    ) {
                        McpServerTemplateRegistry.CATEGORIES.forEach { cat ->
                            FilterChip(
                                selected = cat == selectedCategory,
                                onClick = { selectedCategory = cat },
                                label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.small))

                // ── Template grid ─────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        if (visibleTemplates.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource("mcp.catalog.search.empty"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            // Two-column layout
                            visibleTemplates.chunked(2).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    row.forEach { template ->
                                        mcpTemplateCatalogCard(
                                            template = template,
                                            modifier = Modifier.weight(1f),
                                            onSelect = { onSelectTemplate(template) },
                                        )
                                    }
                                    // Pad the last row if odd number of cards
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }

                        Spacer(Modifier.height(Spacing.small))
                    }
                }

                // ── Footer ────────────────────────────────────────────────────
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.extraLarge, vertical = Spacing.large),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = onDismiss,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(stringResource("mcp.catalog.action.cancel"))
                    }

                    secondaryButton(
                        onClick = onManualSetup,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.small))
                        Text(stringResource("mcp.catalog.action.manual"))
                    }
                }
            }
        }
    }
}

@Composable
private fun mcpTemplateCatalogCard(
    template: McpServerDefinition,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val isPopular = "popular" in template.tags

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (isPopular) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource("mcp.catalog.category.popular"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Text(
                text = template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            val credentialLabel = if (template.parameters.isNotEmpty()) {
                stringResource("mcp.catalog.card.credentials.required", template.parameters.count { it.required }.toString())
            } else {
                stringResource("mcp.catalog.card.no.credentials")
            }
            Text(
                text = credentialLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(2.dp))

            primaryButton(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(stringResource("mcp.catalog.card.configure"), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
