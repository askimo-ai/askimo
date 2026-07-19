/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.mcp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.langchain4j.agent.tool.ToolSpecification
import io.askimo.core.mcp.HttpConfig
import io.askimo.core.mcp.McpClientFactory
import io.askimo.core.mcp.McpInstance
import io.askimo.core.mcp.McpServerDefinition
import io.askimo.core.mcp.McpServerTemplateRegistry
import io.askimo.core.mcp.ParameterType
import io.askimo.core.mcp.ServerDefinitionSecretManager
import io.askimo.core.mcp.StdioConfig
import io.askimo.core.mcp.TransportType
import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.ui.common.components.inlineErrorMessage
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.rememberDialogState
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.get
import java.time.LocalDateTime
import java.util.UUID

/**
 * Dialog for creating or editing an MCP server instance.
 *
 * When [templateDefinition] is provided (selected from the catalog), the dialog renders
 * labeled parameter fields for each [io.askimo.core.mcp.Parameter] declared by the template
 * instead of showing the raw command/env textarea fields
 *
 * When [templateDefinition] is null (manual setup or editing an existing instance), the full
 * raw STDIO / HTTP configuration fields are shown.
 */
@Composable
fun addMcpInstanceDialog(
    existingInstance: McpInstance? = null,
    templateDefinition: McpServerDefinition? = null,
    onDismiss: () -> Unit,
    onSave: (serverId: String, name: String, parameters: Map<String, String>) -> Unit,
) {
    val dialogState = rememberDialogState()
    val scope = rememberCoroutineScope()

    // Editing an existing instance takes precedence over a template
    val existingServerDef = remember(existingInstance) {
        existingInstance?.let { McpServersConfig.get(it.serverId) }
    }

    // The effective "pre-fill" source: existing def when editing, template when creating from catalog
    val prefill = existingServerDef ?: templateDefinition

    // Basic fields
    var instanceName by remember { mutableStateOf(existingInstance?.name ?: templateDefinition?.name ?: "") }
    var description by remember { mutableStateOf(prefill?.description ?: "") }

    // Template parameter values — keyed by Parameter.key
    val templateParamValues = remember(templateDefinition) {
        mutableStateOf(
            templateDefinition?.parameters?.associate { p ->
                p.key to (p.defaultValue ?: "")
            } ?: emptyMap(),
        )
    }

    // Whether we are in "template mode" (catalog-driven) vs "manual mode"
    val isTemplateMode = templateDefinition != null && existingInstance == null

    // Whether the user has expanded the Advanced settings section in template mode.
    // When true, buildServerDefinition uses the raw fields instead of resolving the template.
    var showAdvanced by remember { mutableStateOf(false) }

    // Transport type: 0 = STDIO, 1 = HTTP
    var selectedTab by remember {
        mutableStateOf(
            if (prefill?.transportType == TransportType.HTTP) 1 else 0,
        )
    }

    // STDIO fields — pre-filled from existingServerDef (edit) or from the template (advanced mode)
    var command by remember {
        mutableStateOf(
            existingServerDef?.stdioConfig?.commandTemplate?.firstOrNull()
                ?: templateDefinition?.stdioConfig?.commandTemplate?.firstOrNull()
                ?: "",
        )
    }
    var args by remember {
        mutableStateOf(
            existingServerDef?.stdioConfig?.commandTemplate?.drop(1)?.joinToString(" ")
                ?: templateDefinition?.stdioConfig?.commandTemplate?.drop(1)?.joinToString(" ")
                ?: "",
        )
    }
    var workingDir by remember {
        mutableStateOf(
            existingServerDef?.stdioConfig?.workingDirectory
                ?: templateDefinition?.stdioConfig?.workingDirectory
                ?: "",
        )
    }
    var envVars by remember {
        mutableStateOf(
            existingServerDef?.stdioConfig?.envTemplate?.entries?.joinToString("\n") { "${it.key}=${it.value}" }
                ?: templateDefinition?.stdioConfig?.envTemplate?.entries?.joinToString("\n") { "${it.key}=${it.value}" }
                ?: "",
        )
    }

    // HTTP fields
    var url by remember {
        mutableStateOf(
            existingServerDef?.httpConfig?.urlTemplate
                ?: templateDefinition?.httpConfig?.urlTemplate
                ?: "",
        )
    }
    var headers by remember {
        mutableStateOf(
            existingServerDef?.httpConfig?.headersTemplate?.entries?.joinToString("\n") { "${it.key}=${it.value}" }
                ?: templateDefinition?.httpConfig?.headersTemplate?.entries?.joinToString("\n") { "${it.key}=${it.value}" }
                ?: "",
        )
    }
    var timeoutMs by remember {
        mutableStateOf(
            existingServerDef?.httpConfig?.timeoutMs?.toString()
                ?: templateDefinition?.httpConfig?.timeoutMs?.toString()
                ?: "60000",
        )
    }

    // Connection test state
    var isTesting by remember { mutableStateOf(false) }
    var testSuccess by remember { mutableStateOf<Boolean?>(null) }
    var availableTools by remember { mutableStateOf<List<ToolSpecification>>(emptyList()) }

    val transportType = if (selectedTab == 0) TransportType.STDIO else TransportType.HTTP

    fun parseEnvironmentVariables(envVarsString: String): Map<String, String> {
        if (envVarsString.isBlank()) return emptyMap()
        return envVarsString
            .split("\n")
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()
    }

    // Helper function to parse command line string into list of arguments
    // Handles spaces and quoted strings properly
    fun parseCommandLine(commandLine: String): List<String> {
        if (commandLine.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar: Char? = null
        var i = 0

        while (i < commandLine.length) {
            val char = commandLine[i]

            when {
                // Handle quotes
                (char == '"' || char == '\'') && !inQuotes -> {
                    inQuotes = true
                    quoteChar = char
                }

                char == quoteChar && inQuotes -> {
                    inQuotes = false
                    quoteChar = null
                }

                // Handle spaces outside quotes
                char.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }

                // Regular character
                else -> {
                    current.append(char)
                }
            }
            i++
        }

        // Add the last argument
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }

    // Helper function to build server definition from current form state
    fun buildServerDefinition(serverId: String): McpServerDefinition = if (isTemplateMode && !showAdvanced) {
        // Template mode (simple): resolve {{key}} placeholders with user-supplied parameter values
        McpServerTemplateRegistry.resolve(
            template = templateDefinition,
            instanceId = serverId,
            instanceName = instanceName,
            paramValues = templateParamValues.value,
        ).copy(description = description)
    } else if (transportType == TransportType.STDIO) {
        // Parse the command field as a complete command line
        val commandList = buildList {
            addAll(parseCommandLine(command))
            // If args field is also provided, parse and add those too
            if (args.isNotBlank()) {
                addAll(parseCommandLine(args))
            }
        }
        val envMap = parseEnvironmentVariables(envVars)

        McpServerDefinition(
            id = serverId,
            name = instanceName,
            description = description,
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = commandList,
                envTemplate = envMap,
                workingDirectory = workingDir.ifBlank { null },
            ),
            httpConfig = null,
            tags = listOf("global"),
        )
    } else {
        val headersMap = parseEnvironmentVariables(headers)
        val timeout = timeoutMs.toLongOrNull() ?: 60000L

        McpServerDefinition(
            id = serverId,
            name = instanceName,
            description = description,
            transportType = TransportType.HTTP,
            stdioConfig = null,
            httpConfig = HttpConfig(
                urlTemplate = url,
                headersTemplate = headersMap,
                timeoutMs = timeout,
            ),
            tags = listOf("global"),
        )
    }

    // Test connection method - can be called independently or as part of save
    suspend fun testConnection(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val serverId = existingInstance?.serverId ?: "global-${UUID.randomUUID()}"
                val serverDef = buildServerDefinition(serverId)
                val (protectedDef, _) = ServerDefinitionSecretManager.protectSecrets(serverDef)
                McpServersConfig.add(protectedDef)

                try {
                    val mcpClientFactory = get<McpClientFactory>(McpClientFactory::class.java)
                    val now = LocalDateTime.now()
                    val instance = McpInstance(
                        id = serverId,
                        serverId = serverId,
                        name = instanceName,
                        parameterValues = emptyMap(),
                        enabled = true,
                        createdAt = now,
                        updatedAt = now,
                    )

                    val clientResult = mcpClientFactory.createMcpClient(
                        instance,
                        "test-connection-$serverId",
                    )

                    clientResult.fold(
                        onSuccess = { mcpClient ->
                            val tools = mcpClient.listTools()
                            availableTools = tools
                        },
                        onFailure = { e ->
                            return@withContext Result.failure(e)
                        },
                    )
                    Result.success(serverId)
                } finally {
                    if (existingInstance == null) {
                        McpServersConfig.remove(serverId)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    Dialog(
        onDismissRequest = { /* modal: do not dismiss on outside click */ },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(800.dp)
                .heightIn(min = 600.dp, max = 800.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 32.dp, top = 24.dp, bottom = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        text = stringResource(
                            if (existingInstance != null) {
                                "mcp.instance.edit.dialog.title"
                            } else {
                                "mcp.instance.add.dialog.title"
                            },
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            if (existingInstance != null) {
                                "mcp.instance.edit.dialog.description"
                            } else {
                                "mcp.instance.add.dialog.description"
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.medium))

                // Scrollable content
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val scrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 32.dp, top = 0.dp, bottom = 16.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(Spacing.large),
                    ) {
                        // Instance Name
                        OutlinedTextField(
                            value = instanceName,
                            onValueChange = { instanceName = it },
                            label = { Text(stringResource("mcp.instance.name")) },
                            placeholder = { Text(stringResource("mcp.instance.name.placeholder")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = AppComponents.outlinedTextFieldColors(),
                        )

                        // Description (optional)
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource("mcp.instance.field.description")) },
                            placeholder = { Text(stringResource("mcp.instance.field.description.placeholder")) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3,
                            colors = AppComponents.outlinedTextFieldColors(),
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.small))

                        if (isTemplateMode) {
                            // ── Toggle row (always visible in template mode) ───────────────
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvanced = !showAdvanced }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(vertical = Spacing.extraSmall),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (showAdvanced) {
                                            "mcp.instance.template.advanced.hide"
                                        } else {
                                            "mcp.instance.template.advanced.show"
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            if (!showAdvanced) {
                                // ── Simple view: labeled parameter fields ─────────────────
                                templateParameterFields(
                                    template = templateDefinition,
                                    paramValues = templateParamValues.value,
                                    onParamChange = { key, value ->
                                        templateParamValues.value += (key to value)
                                    },
                                )
                            } else {
                                // ── Advanced view: full manual dialog fields ──────────────
                                advancedTransportFields(
                                    selectedTab = selectedTab,
                                    onTabChange = { selectedTab = it },
                                    command = command, onCommandChange = { command = it },
                                    args = args, onArgsChange = { args = it },
                                    workingDir = workingDir, onWorkingDirChange = { workingDir = it },
                                    envVars = envVars, onEnvVarsChange = { envVars = it },
                                    url = url, onUrlChange = { url = it },
                                    headers = headers, onHeadersChange = { headers = it },
                                    timeoutMs = timeoutMs, onTimeoutMsChange = { timeoutMs = it },
                                )
                            }
                        } else {
                            advancedTransportFields(
                                selectedTab = selectedTab,
                                onTabChange = { selectedTab = it },
                                command = command, onCommandChange = { command = it },
                                args = args, onArgsChange = { args = it },
                                workingDir = workingDir, onWorkingDirChange = { workingDir = it },
                                envVars = envVars, onEnvVarsChange = { envVars = it },
                                url = url, onUrlChange = { url = it },
                                headers = headers, onHeadersChange = { headers = it },
                                timeoutMs = timeoutMs, onTimeoutMsChange = { timeoutMs = it },
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.medium))

                        // Connection Status Section
                        Text(
                            text = stringResource("mcp.instance.connection.status"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Text(
                            text = stringResource("mcp.instance.connection.info"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Show test result
                        testSuccess?.let { success ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                modifier = Modifier.padding(vertical = Spacing.small),
                            ) {
                                Text(
                                    text = if (success) "✅" else "❌",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(
                                        if (success) {
                                            "mcp.instance.test.success"
                                        } else {
                                            "mcp.instance.test.failed"
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (success) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        // Display available tools
                        if (availableTools.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.medium))

                            Text(
                                text = stringResource("mcp.instance.tools.title"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            Text(
                                text = stringResource("mcp.instance.tools.count", availableTools.size.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.small),
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.medium)
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    availableTools.forEach { toolSpec ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = toolSpec.name(),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                toolSpec.description()?.let { desc ->
                                                    Text(
                                                        text = desc,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                        if (toolSpec != availableTools.last()) {
                                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.extraSmall))
                                        }
                                    }
                                }
                            }
                        }

                        // Show error message if any
                        dialogState.errorMessage?.let { error ->
                            inlineErrorMessage(errorMessage = error)
                        }
                    }
                }

                // Action buttons (pinned at bottom)
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource("dialog.cancel"))
                    }
                    Spacer(Modifier.width(Spacing.small))
                    secondaryButton(
                        onClick = {
                            dialogState.clearError()
                            isTesting = true
                            scope.launch {
                                try {
                                    testConnection()
                                        .onSuccess {
                                            testSuccess = true
                                        }
                                        .onFailure { e ->
                                            testSuccess = false
                                            dialogState.setError(e, "Failed to connect to MCP server. Check your configuration.")
                                        }
                                } catch (e: Exception) {
                                    testSuccess = false
                                    dialogState.setError(e, "Connection test failed")
                                } finally {
                                    isTesting = false
                                }
                            }
                        },
                    ) {
                        Text(stringResource("mcp.instance.test.connection"))
                    }
                    Spacer(Modifier.width(Spacing.small))
                    primaryButton(
                        onClick = {
                            dialogState.clearError()
                            isTesting = true
                            scope.launch {
                                try {
                                    testConnection()
                                        .onSuccess { serverId ->
                                            testSuccess = true
                                            // Connection successful, save permanently with protected secrets
                                            val serverDef = buildServerDefinition(serverId)
                                            val (protectedDef, _) = ServerDefinitionSecretManager.protectSecrets(serverDef)
                                            McpServersConfig.add(protectedDef)
                                            onSave(serverId, instanceName, emptyMap())
                                        }
                                        .onFailure { e ->
                                            testSuccess = false
                                            dialogState.setError(e, "Failed to connect to MCP server. Check your configuration.")
                                        }
                                } catch (e: Exception) {
                                    testSuccess = false
                                    dialogState.setError(e, "Failed to save MCP instance")
                                } finally {
                                    isTesting = false
                                }
                            }
                        },
                    ) {
                        Text(stringResource("action.save"))
                    }
                }
            }
        }
    }
}

/**
 * SECRET parameters show a password visibility toggle.
 */
@Composable
private fun templateParameterFields(
    template: McpServerDefinition,
    paramValues: Map<String, String>,
    onParamChange: (key: String, value: String) -> Unit,
) {
    if (template.parameters.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Text(
                text = stringResource("mcp.instance.template.no.credentials"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(Spacing.medium),
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(
            text = stringResource("mcp.instance.template.config.title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource("mcp.instance.template.config.description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        template.parameters.forEach { param ->
            val value = paramValues[param.key] ?: ""
            val isSecret = param.type == ParameterType.SECRET
            var showSecret by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = value,
                onValueChange = { onParamChange(param.key, it) },
                label = {
                    Text(
                        param.label + if (!param.required) stringResource("mcp.instance.template.param.optional") else "",
                    )
                },
                placeholder = param.placeholder?.let { { Text(it) } },
                supportingText = param.description?.let { desc -> { Text(desc) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = isSecret || param.type != ParameterType.PATH,
                visualTransformation = if (isSecret && !showSecret) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                trailingIcon = if (isSecret) {
                    {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showSecret) "Hide" else "Show",
                            )
                        }
                    }
                } else {
                    null
                },
                colors = AppComponents.outlinedTextFieldColors(),
            )
        }
    }
}

/**
 * The transport configuration fields (STDIO / HTTP tabs + all raw inputs).
 * Shared between manual mode and the Advanced section in template mode.
 */
@Composable
private fun advancedTransportFields(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    command: String,
    onCommandChange: (String) -> Unit,
    args: String,
    onArgsChange: (String) -> Unit,
    workingDir: String,
    onWorkingDirChange: (String) -> Unit,
    envVars: String,
    onEnvVarsChange: (String) -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    headers: String,
    onHeadersChange: (String) -> Unit,
    timeoutMs: String,
    onTimeoutMsChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(
            text = stringResource("mcp.instance.field.transport"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SecondaryTabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabChange(0) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                text = {
                    Text(
                        text = stringResource("mcp.instance.transport.stdio"),
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabChange(1) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                text = {
                    Text(
                        text = stringResource("mcp.instance.transport.http"),
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        AnimatedVisibility(visible = selectedTab == 0) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = onCommandChange,
                    label = { Text(stringResource("mcp.instance.field.command")) },
                    placeholder = { Text(stringResource("mcp.instance.field.command.placeholder")) },
                    supportingText = { Text(stringResource("mcp.instance.field.command.hint")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = onArgsChange,
                    label = { Text(stringResource("mcp.instance.field.args")) },
                    placeholder = { Text(stringResource("mcp.instance.field.args.placeholder")) },
                    supportingText = { Text(stringResource("mcp.instance.field.args.hint")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = workingDir,
                    onValueChange = onWorkingDirChange,
                    label = { Text(stringResource("mcp.instance.field.workingdir")) },
                    placeholder = { Text(System.getProperty("user.home")) },
                    supportingText = { Text(stringResource("mcp.instance.field.workingdir.hint")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = envVars, onValueChange = onEnvVarsChange,
                    label = { Text(stringResource("mcp.instance.env.label")) },
                    placeholder = { Text(stringResource("mcp.instance.env.placeholder")) },
                    supportingText = { Text(stringResource("mcp.instance.env.hint")) },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
            }
        }

        AnimatedVisibility(visible = selectedTab == 1) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource("mcp.instance.http.url.label")) },
                    placeholder = { Text(stringResource("mcp.instance.http.url.placeholder")) },
                    supportingText = { Text(stringResource("mcp.instance.http.url.hint")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = headers, onValueChange = onHeadersChange,
                    label = { Text(stringResource("mcp.instance.http.headers.label")) },
                    placeholder = { Text(stringResource("mcp.instance.http.headers.placeholder")) },
                    supportingText = { Text(stringResource("mcp.instance.http.headers.hint")) },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = timeoutMs,
                    onValueChange = onTimeoutMsChange,
                    label = { Text(stringResource("mcp.instance.http.timeout.label")) },
                    placeholder = { Text("60000") },
                    supportingText = { Text(stringResource("mcp.instance.http.timeout.hint")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
            }
        }
    }
}
