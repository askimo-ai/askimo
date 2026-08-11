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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedTooltip

internal val MODEL_PANEL_WIDTH = 800.dp

// ── Provider badge ─────────────────────────────────────────────────────────────────────────

@Composable
private fun providerBadge(provider: ModelProvider, size: Int = 26) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = provider.initials,
            style = AppTextStyles.hint,
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

    val pendingDefaultModel = remember(state.pendingInstanceId, state.availableInstances) {
        state.availableInstances.firstOrNull { it.id == state.pendingInstanceId }
            ?.settings?.defaultModel ?: ""
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
                        style = AppTextStyles.fieldLabel,
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
                            style = AppTextStyles.caption,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                } else {
                    val initialActiveInstanceId = remember { currentInstanceId }
                    val sortedInstances = remember(state.availableInstances) {
                        state.availableInstances.sortedWith(
                            compareByDescending<ProviderInstance> { it.id == initialActiveInstanceId }
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
                                    onSelect = {
                                        state.selectInstanceForPreview(instance.id)
                                        if (instance.settings.defaultModel.isNotBlank()) {
                                            state.commitSelection(instance.id, instance.settings.defaultModel)
                                        }
                                    },
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

                // ── Pinned "Add provider" row ─────────────────────────────────────────────
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddProvider() }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource("provider.add.new"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource("provider.add.new"),
                        style = AppTextStyles.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                                        style = AppTextStyles.body,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (pendingModels.isNotEmpty() && !state.isLoadingPending) {
                                        Text(
                                            text = stringResource("provider.model.panel.model.count", pendingModels.size),
                                            style = AppTextStyles.hint,
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
                                    pendingDefaultModel = pendingDefaultModel,
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
    pendingDefaultModel: String,
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
                            style = AppTextStyles.caption,
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
                        AppComponents.loadingSpinner(size = 16.dp)
                        Text(
                            text = stringResource("settings.model.loading"),
                            style = AppTextStyles.caption,
                        )
                    }
                }
            }

            state.pendingModels.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("settings.model.none"),
                        style = AppTextStyles.caption,
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
                            style = AppTextStyles.hint,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    textStyle = AppTextStyles.caption,
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                val modelListState = rememberLazyListState()

                // Pin the default model to the top when not actively searching
                val pinnedModel = remember(filteredModels, pendingDefaultModel, searchQuery) {
                    if (pendingDefaultModel.isNotBlank() && searchQuery.isBlank()) {
                        filteredModels.firstOrNull { it.modelId == pendingDefaultModel }
                    } else {
                        null
                    }
                }
                val groupedModels = remember(filteredModels, pinnedModel) {
                    val remaining = if (pinnedModel != null) filteredModels.filter { it.modelId != pinnedModel.modelId } else filteredModels
                    remaining.groupBy { it.provider }
                }

                // Scroll to pinned (index 0) or find currentModel in grouped list
                val isActiveInstance = state.pendingInstanceId == currentInstanceId
                LaunchedEffect(state.pendingInstanceId, filteredModels) {
                    if (pinnedModel != null) {
                        modelListState.scrollToItem(0)
                    } else if (isActiveInstance && currentModel.isNotBlank() && filteredModels.isNotEmpty()) {
                        val showHeaders = groupedModels.size > 1
                        var flatIndex = 0
                        var found = false
                        for ((_, providerModels) in groupedModels) {
                            if (showHeaders) flatIndex++
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
                                style = AppTextStyles.body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Spacing.medium),
                            )
                        }
                    } else {
                        LazyColumn(state = modelListState, modifier = Modifier.fillMaxWidth()) {
                            val showHeaders = groupedModels.size > 1

                            // ── Pinned default model ──────────────────────────────────────
                            if (pinnedModel != null) {
                                item(key = "pinned_${pinnedModel.modelId}") {
                                    val isCurrent = pinnedModel.modelId == currentModel &&
                                        state.pendingInstanceId == currentInstanceId
                                    AppComponents.themedDropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(pinnedModel.displayName, style = AppTextStyles.body, modifier = Modifier.weight(1f, fill = false))
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            MaterialTheme.colorScheme.tertiaryContainer,
                                                            RoundedCornerShape(4.dp),
                                                        )
                                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                                ) {
                                                    Text(
                                                        text = stringResource("provider.model.default.badge"),
                                                        style = AppTextStyles.hint,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            state.commitSelection(state.pendingInstanceId, pinnedModel.modelId)
                                            onModelSelected()
                                        },
                                        isSelected = isCurrent,
                                    )
                                }
                                item(key = "pinned_divider") {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                                }
                            }

                            // ── Grouped remaining models ──────────────────────────────────
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
                                                style = AppTextStyles.hint,
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
                                        text = { Text(dto.displayName, style = AppTextStyles.body) },
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
                            style = AppTextStyles.hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        linkButton(onClick = onShowAll) {
                            Text(
                                text = stringResource("settings.model.chat.filter.show.all", totalModelCount),
                                style = AppTextStyles.hint,
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
                    style = AppTextStyles.itemTitle,
                )
                Text(
                    text = providerDisplayName,
                    style = AppTextStyles.hint,
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
                    style = AppTextStyles.fieldLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.editDisplayName,
                    onValueChange = { state.updateEditDisplayName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = state.editDisplayNameError != null,
                    placeholder = { Text(stringResource("provider.instance.name.placeholder"), style = AppTextStyles.caption) },
                    supportingText = state.editDisplayNameError?.let { error ->
                        { Text(text = error, color = MaterialTheme.colorScheme.error, style = AppTextStyles.errorText) }
                    },
                    textStyle = AppTextStyles.caption,
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
                                style = AppTextStyles.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Spacing.medium),
                            )
                        }
                    }

                    is ProviderConfigField.ApiKeyField -> {
                        AppComponents.formField(
                            label = field.label,
                            description = field.description,
                            required = field.required,
                        ) {
                            AppComponents.appSecretTextField(
                                value = state.editFieldValues[field.name] ?: "",
                                onValueChange = { state.updateEditField(field.name, it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        text = if (field.hasExistingValue) stringResource("provider.apikey.stored") else stringResource("provider.apikey.enter"),
                                        style = AppTextStyles.body,
                                    )
                                },
                            )
                        }
                    }

                    is ProviderConfigField.BaseUrlField -> {
                        AppComponents.formField(
                            label = field.label,
                            description = field.description,
                            required = field.required,
                        ) {
                            OutlinedTextField(
                                value = state.editFieldValues[field.name] ?: "",
                                onValueChange = { state.updateEditField(field.name, it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource("settings.placeholder.baseurl"), style = AppTextStyles.caption) },
                                textStyle = AppTextStyles.caption,
                                colors = AppComponents.outlinedTextFieldColors(),
                            )
                        }
                    }

                    is ProviderConfigField.SelectField -> {
                        val currentValue = state.editFieldValues[field.name] ?: field.value
                        val selectHint: (@Composable () -> Unit)? = field.options
                            .find { it.value == currentValue }?.description
                            ?.takeIf { it.isNotBlank() }
                            ?.let { desc ->
                                {
                                    Text(
                                        text = desc,
                                        style = AppTextStyles.caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        AppComponents.formField(
                            label = field.label,
                            description = field.description,
                            required = field.required,
                            hint = selectHint,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                field.options.forEach { option ->
                                    if (currentValue == option.value) {
                                        primaryButton(onClick = {}) {
                                            Text(option.label)
                                        }
                                    } else {
                                        secondaryButton(onClick = { state.updateEditField(field.name, option.value) }) {
                                            Text(option.label)
                                        }
                                    }
                                }
                            }
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
                            style = AppTextStyles.caption,
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
                Text(stringResource("settings.cancel"))
            }
            primaryButton(
                onClick = onSave,
                enabled = !state.isTestingEdit && state.editDisplayNameError == null,
            ) {
                Text(stringResource("settings.save"))
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
    // LazyColumn/popup.
    val tooltipText = buildString {
        append(instance.displayName)
        append("\nType: $providerDisplayName")
        append("\nModel: $model")
        if (baseUrl != null) append("\nBase URL: $baseUrl")
        if (apiKey != null) append("\nAPI Key: ${if (apiKeyConfigured) "✓ Configured" else "Not set"}")
    }

    themedTooltip(
        text = tooltipText,
        placement = TooltipPlacement.LEFT,
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
                style = AppTextStyles.caption,
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
