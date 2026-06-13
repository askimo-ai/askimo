/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.mcp

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.intent.ToolCategory
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolStrategy
import io.askimo.core.mcp.McpClientFactory
import io.askimo.core.mcp.McpInstance
import io.askimo.core.mcp.SecretDetector
import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.ui.common.components.inlineErrorMessage
import io.askimo.ui.common.components.inlineSuccessMessage
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.rememberDialogState
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.util.FileDialogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.get
import java.util.concurrent.TimeoutException

@Composable
fun mcpToolsDialog(
    instance: McpInstance,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val mcpClientFactory = get<McpClientFactory>(McpClientFactory::class.java)
    val serverDefinition = remember(instance.serverId) { McpServersConfig.get(instance.serverId) }
    var tools by remember { mutableStateOf<List<ToolConfig>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val dialogState = rememberDialogState()
    var exportMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // true=success, false=error
    var searchQuery by remember { mutableStateOf("") }

    val filteredTools = remember(tools, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            tools
        } else {
            tools?.filter { tool ->
                tool.specification.name().lowercase().contains(query) ||
                    tool.specification.description()?.lowercase()?.contains(query) == true
            }
        }
    }

    LaunchedEffect(instance.id) {
        isLoading = true
        dialogState.clearError()
        try {
            val result = withContext(Dispatchers.IO) {
                mcpClientFactory.listTools(instance)
            }
            result.fold(
                onSuccess = { tools = it },
                onFailure = { e ->
                    val isTimeout = e is TimeoutException ||
                        e.cause is TimeoutException ||
                        e.message?.contains("TimeoutException", ignoreCase = true) == true
                    dialogState.setError(
                        if (isTimeout) {
                            "Connection timeout: Unable to connect to MCP server. Please check if the server is running and accessible."
                        } else {
                            e.message ?: "Failed to load tools"
                        },
                    )
                },
            )
        } catch (e: Exception) {
            dialogState.setError(e, "Failed to load tools")
        } finally {
            isLoading = false
        }
    }

    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource("mcp.tools.dialog.title", instance.name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
            ) {
                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    // ── Instance info card ─────────────────────────────────
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppComponents.secondaryCardColors(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Text(
                                text = stringResource("mcp.tools.dialog.instance.info"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource("mcp.instance.field.serverId"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                )
                                SelectionContainer {
                                    Text(
                                        text = instance.serverId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            if (instance.parameterValues.isNotEmpty()) {
                                Text(
                                    text = stringResource("mcp.tools.dialog.parameters"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(top = Spacing.extraSmall),
                                )
                                instance.parameterValues.forEach { (key, value) ->
                                    val isSecret = SecretDetector.isSecret(key, serverDefinition)
                                    var showSecret by remember(key) { mutableStateOf(false) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = Spacing.small),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Key — fixed, never shrinks
                                        SelectionContainer {
                                            Text(
                                                text = key,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.weight(1f, fill = false),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            // Value — takes remaining space, truncates if too long
                                            if (isSecret && showSecret) {
                                                SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                                                    Text(
                                                        text = value,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(start = Spacing.small),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = if (isSecret) "••••••••" else value,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier
                                                        .weight(1f, fill = false)
                                                        .padding(start = Spacing.small),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            // Icon — always reserved space, never squeezed out
                                            if (isSecret) {
                                                IconButton(
                                                    onClick = { showSecret = !showSecret },
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .pointerHoverIcon(PointerIcon.Hand),
                                                ) {
                                                    Icon(
                                                        imageVector = if (showSecret) {
                                                            Icons.Default.VisibilityOff
                                                        } else {
                                                            Icons.Default.Visibility
                                                        },
                                                        contentDescription = stringResource(
                                                            if (showSecret) {
                                                                "mcp.instance.password.hide"
                                                            } else {
                                                                "mcp.instance.password.show"
                                                            },
                                                        ),
                                                        modifier = Modifier.size(14.dp),
                                                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Search field — shown once tools are loaded ─────────
                    if (!tools.isNullOrEmpty()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource("mcp.tools.dialog.search.placeholder")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = AppComponents.outlinedTextFieldColors(),
                        )
                    }

                    // ── Main content ───────────────────────────────────────
                    when {
                        isLoading -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource("mcp.tools.dialog.loading"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        dialogState.errorMessage != null -> {
                            inlineErrorMessage(errorMessage = dialogState.errorMessage)
                        }

                        tools.isNullOrEmpty() -> {
                            Text(
                                text = stringResource("mcp.tools.dialog.empty"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> {
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    stringResource("mcp.tools.dialog.count", tools!!.size)
                                } else {
                                    stringResource("mcp.tools.dialog.search.count", filteredTools!!.size, tools!!.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (filteredTools.isNullOrEmpty()) {
                                Text(
                                    text = stringResource("mcp.tools.dialog.search.empty"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                filteredTools.forEach { tool ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(Spacing.medium),
                                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                                        ) {
                                            SelectionContainer {
                                                Text(
                                                    text = tool.specification.name(),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                            tool.specification.description()?.let { desc ->
                                                SelectionContainer {
                                                    Text(
                                                        text = desc,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                toolCategoryChip(tool.category)
                                                toolStrategyChip(tool.strategy)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Export feedback — shown below tools, never replaces them
                    exportMessage?.let { (isSuccess, msg) ->
                        if (isSuccess) {
                            inlineSuccessMessage(message = msg)
                        } else {
                            inlineErrorMessage(errorMessage = msg)
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                    style = AppComponents.scrollbarStyle(),
                )
            }
        },
        confirmButton = {
            val exportSuccessMsg = stringResource("mcp.tools.dialog.export.success")
            val exportFailedMsg = stringResource("mcp.tools.dialog.export.failed")
            val exportDialogTitle = stringResource("mcp.tools.dialog.export")

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                // Export button — only enabled when tools are loaded
                if (!tools.isNullOrEmpty()) {
                    secondaryButton(
                        onClick = {
                            scope.launch {
                                exportToolsToJson(
                                    tools = tools!!,
                                    instanceName = instance.name,
                                    dialogTitle = exportDialogTitle,
                                ).fold(
                                    onSuccess = { path ->
                                        exportMessage = true to exportSuccessMsg.replace("{0}", path)
                                    },
                                    onFailure = { e ->
                                        exportMessage = false to exportFailedMsg.replace("{0}", e.message ?: "Unknown error")
                                    },
                                )
                            }
                        },
                    ) {
                        Text(stringResource("mcp.tools.dialog.export"))
                    }
                }
                primaryButton(onClick = onDismiss) {
                    Text(stringResource("dialog.close"))
                }
            }
        },
    )
}

/**
 * Opens a [FileDialog] save prompt and writes the given [tools] as a JSON array.
 * Each entry contains: name, description, category, strategy.
 *
 * @return [Result] with the absolute path of the saved file on success.
 */
private suspend fun exportToolsToJson(
    tools: List<ToolConfig>,
    instanceName: String,
    dialogTitle: String,
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val suggestedName = "${instanceName.replace(" ", "_")}_tools"
        val targetFile = FileDialogUtils.pickSavePath(
            suggestedName = suggestedName,
            extension = "json",
            title = dialogTitle,
        ) ?: return@runCatching Result.failure<String>(
            IllegalStateException("Export cancelled"),
        ).getOrThrow()

        val json = buildString {
            appendLine("[")
            tools.forEachIndexed { index, tool ->
                appendLine("  {")
                appendLine("    \"name\": ${tool.specification.name().toJsonString()},")
                appendLine("    \"description\": ${tool.specification.description().toJsonString()},")
                appendLine("    \"category\": ${tool.category.name.toJsonString()},")
                append("    \"strategy\": ${tool.strategy}")
                appendLine()
                append("  }")
                if (index < tools.lastIndex) appendLine(",") else appendLine()
            }
            append("]")
        }

        targetFile.writeText(json, Charsets.UTF_8)
        targetFile.absolutePath
    }
}

private fun String?.toJsonString(): String {
    if (this == null) return "null"
    val escaped = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}

@Composable
private fun toolCategoryChip(category: ToolCategory) {
    val label = stringResource("mcp.tool.category.${category.name}.desc")
    val (bg, fg) = when (category) {
        ToolCategory.DATABASE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ToolCategory.NETWORK -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ToolCategory.FILE_READ -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ToolCategory.FILE_WRITE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ToolCategory.SEARCH -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ToolCategory.VERSION_CONTROL -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun toolStrategyChip(strategy: Int) {
    val (label, bg, fg) = when (strategy) {
        ToolStrategy.INTENT_BASED -> Triple(
            stringResource("mcp.instance.tool.strategy.intent"),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        ToolStrategy.FOLLOW_UP_BASED -> Triple(
            stringResource("mcp.instance.tool.strategy.followup"),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        ToolStrategy.BOTH -> Triple(
            stringResource("mcp.instance.tool.strategy.both"),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        else -> Triple(
            stringResource("mcp.instance.tool.strategy.unknown"),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}
