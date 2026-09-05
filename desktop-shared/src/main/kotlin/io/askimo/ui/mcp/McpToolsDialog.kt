/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.askimo.core.intent.ToolApprovalPolicy
import io.askimo.core.intent.ToolCategory
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolStrategy
import io.askimo.core.mcp.McpInstance
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.mcp.SecretDetector
import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.ui.common.components.inlineErrorMessage
import io.askimo.ui.common.components.inlineSuccessMessage
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.rememberDialogState
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
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
    val mcpInstanceService = remember { get<McpInstanceService>(McpInstanceService::class.java) }
    val serverDefinition = remember(instance.serverId) { McpServersConfig.get(instance.serverId) }
    var tools by remember { mutableStateOf<List<ToolConfig>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val dialogState = rememberDialogState()
    var exportMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
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
            val result = withContext(Dispatchers.IO) { mcpInstanceService.listTools(instance.id) }
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

    val exportSuccessMsg = stringResource("mcp.tools.dialog.export.success")
    val exportFailedMsg = stringResource("mcp.tools.dialog.export.failed")
    val exportDialogTitle = stringResource("mcp.tools.dialog.export")

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 800.dp,
        title = {
            Text(
                text = stringResource("mcp.tools.dialog.title", instance.name),
                style = AppTextStyles.pageTitle,
            )
        },
        stickyHeader = {
            if (!tools.isNullOrEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource("mcp.tools.dialog.search.placeholder")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = AppColors.outlinedTextFieldColors(),
                )
            }
        },
        actions = {
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
                Spacer(modifier = Modifier.width(Spacing.small))
            }
            primaryButton(onClick = onDismiss) {
                Text(stringResource("dialog.close"))
            }
        },
    ) {
        // ── Instance info card ─────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource("mcp.tools.dialog.instance.info"),
                    style = AppTextStyles.itemTitle,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                HorizontalDivider(
                    color = AppColors.tertiaryIconColor(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource("mcp.instance.field.serverId"),
                        style = AppTextStyles.caption,
                        color = AppColors.secondaryIconColor(),
                    )
                    SelectionContainer {
                        Text(
                            text = instance.serverId,
                            style = AppTextStyles.caption,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (instance.parameterValues.isNotEmpty()) {
                    Text(
                        text = stringResource("mcp.tools.dialog.parameters"),
                        style = AppTextStyles.caption,
                        color = AppColors.secondaryIconColor(),
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
                            SelectionContainer {
                                Text(
                                    text = key,
                                    style = AppTextStyles.caption,
                                    color = AppColors.tertiaryIconColor(),
                                )
                            }
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                if (isSecret && showSecret) {
                                    SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                                        Text(
                                            text = value,
                                            style = AppTextStyles.caption,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(start = Spacing.small),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = if (isSecret) "••••••••" else value,
                                        style = AppTextStyles.caption,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .padding(start = Spacing.small),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isSecret) {
                                    IconButton(
                                        onClick = { showSecret = !showSecret },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = stringResource(
                                                if (showSecret) "mcp.instance.password.hide" else "mcp.instance.password.show",
                                            ),
                                            modifier = Modifier.size(14.dp),
                                            tint = AppColors.tertiaryIconColor(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Main content ───────────────────────────────────────────────────
        when {
            isLoading -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppComponents.loadingSpinner(size = 24.dp)
                    Text(
                        text = stringResource("mcp.tools.dialog.loading"),
                        style = AppTextStyles.bodySecondary,
                    )
                }
            }

            dialogState.errorMessage != null -> {
                inlineErrorMessage(errorMessage = dialogState.errorMessage)
            }

            tools.isNullOrEmpty() -> {
                Text(
                    text = stringResource("mcp.tools.dialog.empty"),
                    style = AppTextStyles.bodySecondary,
                )
            }

            else -> {
                Text(
                    text = if (searchQuery.isBlank()) {
                        stringResource("mcp.tools.dialog.count", tools!!.size)
                    } else {
                        stringResource("mcp.tools.dialog.search.count", filteredTools!!.size, tools!!.size)
                    },
                    style = AppTextStyles.caption,
                )

                if (filteredTools.isNullOrEmpty()) {
                    Text(
                        text = stringResource("mcp.tools.dialog.search.empty"),
                        style = AppTextStyles.bodySecondary,
                    )
                } else {
                    filteredTools.forEach { tool ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (tool.enabled) 1f else 0.5f),
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = tool.specification.name(),
                                            style = AppTextStyles.sectionTitle,
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                    ) {
                                        Text(
                                            text = stringResource("mcp.tool.enabled.label"),
                                            style = AppTextStyles.caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Switch(
                                            checked = tool.enabled,
                                            onCheckedChange = { enabled ->
                                                // Reflect change locally so the UI updates instantly
                                                tools = tools?.map { t ->
                                                    if (t.specification.name() == tool.specification.name()) {
                                                        t.copy(enabled = enabled, selected = enabled || t.selected)
                                                    } else {
                                                        t
                                                    }
                                                }
                                                scope.launch(Dispatchers.IO) {
                                                    mcpInstanceService.setToolEnabled(instance.id, tool.specification.name(), enabled)
                                                }
                                            },
                                        )
                                    }
                                }
                                tool.specification.description()?.let { desc ->
                                    SelectionContainer {
                                        Text(
                                            text = desc,
                                            style = AppTextStyles.caption,
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
                                toolApprovalPolicyControl(
                                    current = tool.approvalPolicy,
                                    onPolicyChanged = { newPolicy ->
                                        scope.launch(Dispatchers.IO) {
                                            mcpInstanceService.setToolApproval(instance.id, tool.specification.name(), newPolicy)
                                            // Reflect change locally so the UI updates instantly
                                            tools = tools?.map { t ->
                                                if (t.specification.name() == tool.specification.name()) t.copy(approvalPolicy = newPolicy) else t
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Export feedback
        exportMessage?.let { (isSuccess, msg) ->
            if (isSuccess) {
                inlineSuccessMessage(message = msg)
            } else {
                inlineErrorMessage(errorMessage = msg)
            }
        }
    }
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
private fun toolApprovalPolicyControl(
    current: ToolApprovalPolicy,
    onPolicyChanged: (ToolApprovalPolicy) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource("mcp.tool.approval.label"),
            style = AppTextStyles.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToolApprovalPolicy.entries.forEach { policy ->
            approvalPolicyButton(
                label = stringResource("mcp.tool.approval.${policy.name.lowercase()}"),
                tooltip = stringResource("mcp.tool.approval.${policy.name.lowercase()}.hint"),
                isSelected = policy == current,
                onClick = { if (policy != current) onPolicyChanged(policy) },
            )
        }
    }
}

@Composable
private fun approvalPolicyButton(
    label: String,
    tooltip: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val density = LocalDensity.current

    Box(modifier = Modifier.hoverable(interactionSource)) {
        if (isSelected) {
            primaryButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelSmall) }
        } else {
            secondaryButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelSmall) }
        }
        if (isHovered) {
            AppComponents.anchoredPopup(
                positionProvider = remember(density) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset = IntOffset(
                            x = anchorBounds.left,
                            y = anchorBounds.bottom + with(density) { 4.dp.roundToPx() },
                        )
                    }
                },
                onDismissRequest = {},
                shape = MaterialTheme.shapes.small,
                properties = PopupProperties(focusable = false),
            ) {
                Text(
                    text = tooltip,
                    style = AppTextStyles.hint,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .padding(Spacing.small),
                )
            }
        }
    }
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
            style = AppTextStyles.hint,
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
            style = AppTextStyles.hint,
            color = fg,
        )
    }
}
