/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.HasBaseUrl
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.filterChatModels
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedTooltip

internal val MODEL_PANEL_WIDTH = 800.dp

// ── Provider badge ─────────────────────────────────────────────────────────────────────────

private fun providerInitials(provider: ModelProvider): String = when (provider) {
    ModelProvider.OPENAI -> "OA"
    ModelProvider.ANTHROPIC -> "AN"
    ModelProvider.GEMINI -> "GM"
    ModelProvider.XAI -> "xA"
    ModelProvider.OLLAMA -> "OL"
    ModelProvider.DOCKER -> "DA"
    ModelProvider.LOCALAI -> "LA"
    ModelProvider.LMSTUDIO -> "LM"
    ModelProvider.OPENAI_COMPATIBLE -> "OC"
    ModelProvider.ASKIMO_PRO -> "AP"
    ModelProvider.UNKNOWN -> "?"
}

@Composable
private fun providerBadge(provider: ModelProvider, size: Int = 26) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = providerInitials(provider),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

// ── Two-column provider + model panel ─────────────────────────────────────────────────────

@Composable
internal fun providerModelPanel(
    expanded: Boolean,
    state: ProviderModelPanelState,
    currentInstanceId: String,
    currentModel: String,
    menuOffset: DpOffset,
    onDismiss: () -> Unit,
    onAddProvider: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    // Reset search when the previewed instance changes or we leave edit mode
    LaunchedEffect(state.pendingInstanceId, state.rightColumnMode) { searchQuery = "" }

    val pendingModels = state.pendingModels
    val suggestedModels = remember(pendingModels) { filterChatModels(pendingModels) }
    val isChatFiltered = suggestedModels.size < pendingModels.size
    var showAllModels by remember(state.pendingInstanceId) { mutableStateOf(false) }
    val displayModels = if (showAllModels || !isChatFiltered) pendingModels else suggestedModels

    val filteredModels = remember(displayModels, searchQuery) {
        if (searchQuery.isBlank()) {
            displayModels
        } else {
            displayModels.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.modelId.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    dropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = menuOffset,
    ) {
        Row(
            modifier = Modifier
                .width(MODEL_PANEL_WIDTH)
                .height(420.dp),
        ) {
            // ── Left column: instance list ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("provider.manage.title"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onAddProvider, modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource("provider.add.new"),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                if (state.availableInstances.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource("provider.no.instances.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.medium),
                        )
                    }
                } else {
                    val sortedInstances = remember(state.availableInstances, currentInstanceId) {
                        state.availableInstances.sortedWith(
                            compareByDescending<ProviderInstance> { it.id == currentInstanceId }
                                .thenBy { it.displayName.lowercase() },
                        )
                    }
                    val instanceListState = rememberLazyListState()
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = instanceListState, modifier = Modifier.fillMaxSize()) {
                            items(sortedInstances, key = { it.id }) { instance ->
                                instanceRow(
                                    instance = instance,
                                    isActive = instance.id == currentInstanceId,
                                    isPending = instance.id == state.pendingInstanceId,
                                    onSelect = { state.selectInstanceForPreview(instance.id) },
                                    onEditOpen = { state.openEditForm(instance.id) },
                                    onDelete = { state.deleteInstance(instance.id) },
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(instanceListState),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(end = 2.dp),
                            style = AppComponents.scrollbarStyle(),
                        )
                    }
                }
            }

            // ── Vertical divider ──────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // ── Right column: model list OR edit form ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                when (val mode = state.rightColumnMode) {
                    is RightColumnMode.EditInstance -> {
                        val instance = state.availableInstances.firstOrNull { it.id == mode.instanceId }
                        if (instance != null) {
                            instanceEditForm(
                                state = state,
                                providerDisplayName = ProviderRegistry.getProviderDisplayName(instance.providerType),
                                onSave = { state.saveEdit(mode.instanceId) },
                                onCancel = { state.cancelEdit() },
                            )
                        }
                    }

                    RightColumnMode.Models -> {
                        val pendingInstance = remember(state.pendingInstanceId) {
                            state.availableInstances.firstOrNull { it.id == state.pendingInstanceId }
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            // ── Right-column header: badge + name + type + model count ────
                            if (pendingInstance != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    providerBadge(pendingInstance.providerType, size = 22)
                                    Text(
                                        text = pendingInstance.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (pendingModels.isNotEmpty() && !state.isLoadingPending) {
                                        Text(
                                            text = stringResource("provider.model.panel.model.count", pendingModels.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                            // ── Model list ────────────────────────────────────────────────
                            Box(modifier = Modifier.weight(1f)) {
                                modelListColumn(
                                    state = state,
                                    searchQuery = searchQuery,
                                    filteredModels = filteredModels,
                                    currentInstanceId = currentInstanceId,
                                    currentModel = currentModel,
                                    totalModelCount = pendingModels.size,
                                    isChatFiltered = isChatFiltered,
                                    showAllModels = showAllModels,
                                    onShowAll = { showAllModels = true },
                                    onSearchChange = { searchQuery = it },
                                    onModelSelected = { onDismiss() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Model list (right column, Models mode) ────────────────────────────────────────────────

@Composable
private fun modelListColumn(
    state: ProviderModelPanelState,
    searchQuery: String,
    filteredModels: List<ModelDTO>,
    currentInstanceId: String,
    currentModel: String,
    totalModelCount: Int,
    isChatFiltered: Boolean,
    showAllModels: Boolean,
    onShowAll: () -> Unit,
    onSearchChange: (String) -> Unit,
    onModelSelected: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        when {
            state.pendingInstanceId.isBlank() -> {
                // No instance selected — nudge the user toward the provider list on the left
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        modifier = Modifier.padding(Spacing.large),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            text = stringResource("provider.model.panel.select.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            state.isLoadingPending -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource("settings.model.loading"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            state.pendingModels.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("settings.model.none"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource("settings.model.search"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                val modelListState = rememberLazyListState()

                // Scroll to the active model when first browsing the active instance
                val isActiveInstance = state.pendingInstanceId == currentInstanceId
                LaunchedEffect(state.pendingInstanceId, filteredModels) {
                    if (isActiveInstance && currentModel.isNotBlank() && filteredModels.isNotEmpty()) {
                        val groupedModels = filteredModels.groupBy { it.provider }
                        val showHeaders = groupedModels.size > 1
                        var flatIndex = 0
                        var found = false
                        for ((_, providerModels) in groupedModels) {
                            if (showHeaders) flatIndex++ // header item
                            for (dto in providerModels) {
                                if (dto.modelId == currentModel) {
                                    found = true
                                    break
                                }
                                flatIndex++
                            }
                            if (found) break
                        }
                        if (found) modelListState.scrollToItem(flatIndex)
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (filteredModels.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource("settings.model.no.match"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Spacing.medium),
                            )
                        }
                    } else {
                        LazyColumn(state = modelListState, modifier = Modifier.fillMaxWidth()) {
                            val groupedModels = filteredModels.groupBy { it.provider }
                            val showHeaders = groupedModels.size > 1
                            groupedModels.forEach { (provider, providerModels) ->
                                if (showHeaders && providerModels.isNotEmpty()) {
                                    item(key = "mhdr_${provider.name}") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                        ) {
                                            Text(
                                                text = provider.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                                items(providerModels, key = { it.modelId }) { dto ->
                                    val isCurrent = dto.modelId == currentModel &&
                                        state.pendingInstanceId == currentInstanceId
                                    AppComponents.themedDropdownMenuItem(
                                        text = { Text(dto.displayName, style = MaterialTheme.typography.bodyMedium) },
                                        onClick = {
                                            state.commitSelection(state.pendingInstanceId, dto.modelId)
                                            onModelSelected()
                                        },
                                        isSelected = isCurrent,
                                    )
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(modelListState),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(end = 2.dp),
                            style = AppComponents.scrollbarStyle(),
                        )
                    }
                }

                // Filtering indicator + "Show all" escape hatch
                if (isChatFiltered && !showAllModels && searchQuery.isBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource("settings.model.chat.filter.label", filteredModels.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        linkButton(onClick = onShowAll) {
                            Text(
                                text = stringResource("settings.model.chat.filter.show.all", totalModelCount),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Instance config edit form (right column, EditInstance mode) ───────────────────────────

@Composable
private fun instanceEditForm(
    state: ProviderModelPanelState,
    providerDisplayName: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource("provider.edit.title", state.editDisplayName.ifBlank { providerDisplayName }),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = providerDisplayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // Scrollable fields
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // Display name
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                Text(
                    text = stringResource("provider.instance.name.label"),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = state.editDisplayName,
                    onValueChange = { state.updateEditDisplayName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = state.editDisplayNameError != null,
                    placeholder = { Text(stringResource("provider.instance.name.placeholder"), style = MaterialTheme.typography.bodySmall) },
                    supportingText = state.editDisplayNameError?.let { error ->
                        { Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
            }

            // Provider-specific config fields
            state.editConfigFields.forEach { field ->
                when (field) {
                    is ProviderConfigField.InfoField -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Text(
                                text = field.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Spacing.medium),
                            )
                        }
                    }

                    is ProviderConfigField.ApiKeyField -> {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                            Text(
                                text = field.label + if (field.required) " *" else "",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AppComponents.appSecretTextField(
                                value = state.editFieldValues[field.name] ?: "",
                                onValueChange = { state.updateEditField(field.name, it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        text = if (field.hasExistingValue) stringResource("provider.apikey.stored") else stringResource("provider.apikey.enter"),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                            )
                        }
                    }

                    is ProviderConfigField.BaseUrlField -> {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                            Text(
                                text = field.label + if (field.required) " *" else "",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = state.editFieldValues[field.name] ?: "",
                                onValueChange = { state.updateEditField(field.name, it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource("settings.placeholder.baseurl"), style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                colors = AppComponents.outlinedTextFieldColors(),
                            )
                        }
                    }
                }
            }

            // Connection error card
            if (state.editConnectionError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = state.editConnectionError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
        ) {
            secondaryButton(onClick = onCancel, enabled = !state.isTestingEdit) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.extraSmall))
                Text(stringResource("settings.cancel"), style = MaterialTheme.typography.bodySmall)
            }
            primaryButton(
                onClick = onSave,
                enabled = !state.isTestingEdit && state.editDisplayNameError == null,
            ) {
                if (state.isTestingEdit) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(Spacing.extraSmall))
                Text(stringResource("settings.save"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── Instance row ──────────────────────────────────────────────────────────────────────────

@Composable
internal fun instanceRow(
    instance: ProviderInstance,
    isActive: Boolean,
    isPending: Boolean,
    onSelect: () -> Unit,
    onEditOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val rowBg = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        isPending -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val providerDisplayName = ProviderRegistry.getProviderDisplayName(instance.providerType)
    val model = instance.settings.defaultModel.ifBlank { "—" }
    val baseUrl = (instance.settings as? HasBaseUrl)?.baseUrl
    val apiKey = (instance.settings as? HasApiKey)?.apiKey
    val apiKeyConfigured = apiKey != null &&
        (apiKey == "***keychain***" || apiKey.startsWith("encrypted:") || apiKey.isNotBlank())

    // Plain multi-line string — themedTooltip is width-constrained and flicker-free inside
    // LazyColumn/popup. AUTO placement picks above/below the row, never overlapping the
    // edit/delete buttons that appear on the right side on hover.
    val tooltipText = buildString {
        append(instance.displayName)
        append("\nType: $providerDisplayName")
        append("\nModel: $model")
        if (baseUrl != null) append("\nBase URL: $baseUrl")
        if (apiKey != null) append("\nAPI Key: ${if (apiKeyConfigured) "✓ Configured" else "Not set"}")
    }

    themedTooltip(
        text = tooltipText,
        placement = TooltipPlacement.AUTO,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBg)
                .hoverable(interactionSource)
                .clickable { onSelect() }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            providerBadge(instance.providerType)

            Text(
                text = instance.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                    isPending -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            when {
                isHovered -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        IconButton(onClick = onEditOpen, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }

                isActive -> {
                    Icon(
                        Icons.Default.RadioButtonChecked,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }

                else -> Spacer(Modifier.size(24.dp))
            }
        }
    }
}
