/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.mcp.McpInstance
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.mcp.McpServerDefinition
import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.mcp.addMcpInstanceDialog
import io.askimo.ui.mcp.mcpServerCatalogDialog
import io.askimo.ui.mcp.mcpToolsDialog
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import java.awt.Desktop
import java.net.URI

@Composable
fun mcpServerTemplatesSection() {
    val mcpService = remember { get<McpInstanceService>(McpInstanceService::class.java) }
    var mcpInstances by remember { mutableStateOf(mcpService.getInstances()) }
    var showCatalogDialog by remember { mutableStateOf(false) }
    var showAddMcpDialog by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<McpServerDefinition?>(null) }
    var editingMcpInstance by remember { mutableStateOf<McpInstance?>(null) }
    var deletingMcpInstance by remember { mutableStateOf<McpInstance?>(null) }
    val scope = rememberCoroutineScope()

    val scrollState = rememberScrollState()

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
                    .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 36.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                // Page title — consistent with all other settings sections
                Text(
                    text = stringResource("mcp.instances.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource("mcp.instances.description"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                        linkButton(
                            onClick = {
                                try {
                                    if (Desktop.isDesktopSupported()) {
                                        Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/mcp-integration/"))
                                    }
                                } catch (_: Exception) {}
                            },
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource("mcp.servers.guide"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        themedTooltip(text = stringResource("mcp.instance.add.tooltip")) {
                            primaryButton(
                                onClick = { showCatalogDialog = true },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(Spacing.small))
                                Text(stringResource("mcp.instance.add"))
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppComponents.bannerCardColors(),
                    ) {
                        if (mcpInstances.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.extraLarge),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource("mcp.instances.empty"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.padding(Spacing.medium),
                                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                mcpInstances.forEach { instance ->
                                    mcpInstanceCard(
                                        instance = instance,
                                        onToggleEnabled = {
                                            scope.launch {
                                                mcpService.updateInstance(
                                                    instanceId = instance.id,
                                                    enabled = !instance.enabled,
                                                )
                                                mcpInstances = mcpService.getInstances()
                                            }
                                        },
                                        onEdit = { editingMcpInstance = instance },
                                        onDelete = { deletingMcpInstance = instance },
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource("mcp.instances.count", mcpInstances.size.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
        )
    }

    // Step 1 — Catalog picker (shown when user clicks "Add")
    if (showCatalogDialog) {
        mcpServerCatalogDialog(
            onSelectTemplate = { template ->
                selectedTemplate = template
                showCatalogDialog = false
                showAddMcpDialog = true
            },
            onManualSetup = {
                selectedTemplate = null
                showCatalogDialog = false
                showAddMcpDialog = true
            },
            onDismiss = { showCatalogDialog = false },
        )
    }

    // Step 2 — Add MCP Instance Dialog (pre-filled from template, or blank for manual)
    if (showAddMcpDialog) {
        addMcpInstanceDialog(
            templateDefinition = selectedTemplate,
            onDismiss = {
                showAddMcpDialog = false
                selectedTemplate = null
            },
            onSave = { serverId, name, parameters ->
                scope.launch {
                    mcpService.createInstance(
                        serverId = serverId,
                        name = name,
                        parameterValues = parameters,
                    )
                    mcpInstances = mcpService.getInstances()
                }
                showAddMcpDialog = false
                selectedTemplate = null
            },
        )
    }

    // Edit MCP Instance Dialog
    editingMcpInstance?.let { instance ->
        addMcpInstanceDialog(
            existingInstance = instance,
            onDismiss = { editingMcpInstance = null },
            onSave = { serverId, name, parameters ->
                scope.launch {
                    mcpService.updateInstance(
                        instanceId = instance.id,
                        name = name,
                        parameterValues = parameters,
                    )
                    mcpInstances = mcpService.getInstances()
                }
                editingMcpInstance = null
            },
        )
    }

    // Delete Global Instance Confirmation
    deletingMcpInstance?.let { instance ->
        AppComponents.alertDialog(
            onDismissRequest = { deletingMcpInstance = null },
            title = { Text(stringResource("mcp.instance.delete.confirm.title")) },
            text = { Text(stringResource("mcp.instance.delete.confirm.message", instance.name)) },
            confirmButton = {
                dangerButton(
                    onClick = {
                        scope.launch {
                            mcpService.deleteInstance(instance.id)
                            mcpInstances = mcpService.getInstances()
                        }
                        deletingMcpInstance = null
                    },
                ) {
                    Text(stringResource("action.delete"))
                }
            },
            dismissButton = {
                secondaryButton(onClick = { deletingMcpInstance = null }) {
                    Text(stringResource("dialog.cancel"))
                }
            },
        )
    }
}

@Composable
private fun mcpInstanceCard(
    instance: McpInstance,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val serverDef = remember(instance.serverId) { McpServersConfig.get(instance.serverId) }
    var showToolsDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
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
                        text = instance.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                serverDef?.let { def ->
                    Text(
                        text = def.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }

                Text(
                    text = stringResource("mcp.instance.server.label", instance.serverId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                themedTooltip(
                    text = stringResource(
                        if (instance.enabled) "mcp.instance.disable.tooltip" else "mcp.instance.enable.tooltip",
                    ),
                ) {
                    Switch(
                        checked = instance.enabled,
                        onCheckedChange = { onToggleEnabled() },
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .size(width = 44.dp, height = 24.dp),
                    )
                }

                themedTooltip(text = stringResource("mcp.instance.view.tools.tooltip")) {
                    IconButton(
                        onClick = { showToolsDialog = true },
                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = stringResource("mcp.instance.view.tools.tooltip"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                themedTooltip(text = stringResource("mcp.instance.edit.tooltip")) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource("mcp.instance.edit.tooltip"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                themedTooltip(text = stringResource("mcp.instance.delete.tooltip")) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource("mcp.instance.delete.tooltip"),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (showToolsDialog) {
        mcpToolsDialog(
            instance = instance,
            onDismiss = { showToolsDialog = false },
        )
    }
}
