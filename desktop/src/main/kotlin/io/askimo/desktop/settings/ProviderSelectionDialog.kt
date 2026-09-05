/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderEntry
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.filterChatModels
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import java.awt.Desktop
import java.net.URI

@Composable
fun providerWizardDialog(viewModel: ProviderWizardViewModel) {
    val title = when (viewModel.wizardStep) {
        WizardStep.MODEL -> stringResource("settings.model.select.title")

        WizardStep.TYPE_PICKER -> stringResource("provider.type.picker.title")

        WizardStep.CONFIG -> {
            if (viewModel.isAddingNewInstance) {
                // Prefer the pre-filled display name so template providers (Groq, NVIDIA NIM, etc.)
                // show their own name rather than the generic "OpenAI Compatible" type label.
                val providerName = viewModel.newInstanceDisplayName.ifBlank {
                    ProviderRegistry.getProviderDisplayName(viewModel.selectedProvider!!)
                }
                stringResource("provider.configure.new.title", providerName)
            } else {
                stringResource("provider.edit.title", viewModel.editingInstance?.displayName ?: "")
            }
        }
    }

    // ── Model-picker search state hoisted here so stickyHeader can reference it.
    // remember(wizardStep) resets both values whenever the user navigates to a new step.
    var searchQuery by remember(viewModel.wizardStep) { mutableStateOf("") }
    var showAll by remember(viewModel.wizardStep) { mutableStateOf(false) }

    val chatModels = remember(viewModel.availableModels) { filterChatModels(viewModel.availableModels) }
    val isFiltered = chatModels.size < viewModel.availableModels.size
    val displayModels = if (showAll || !isFiltered) viewModel.availableModels else chatModels
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

    val modelPickerReady = viewModel.wizardStep == WizardStep.MODEL &&
        !viewModel.isLoadingModels &&
        viewModel.modelError == null &&
        viewModel.availableModels.isNotEmpty()

    AppComponents.scaffoldDialog(
        onDismissRequest = { viewModel.closeProviderWizard() },
        onCloseRequest = { viewModel.closeProviderWizard() },
        width = 860.dp,
        showSectionDividers = true,
        title = {
            Text(text = title, style = AppTextStyles.pageTitle)
        },
        stickyHeader = if (modelPickerReady) {
            {
                val providerDisplayName = viewModel.selectedProvider
                    ?.let { ProviderRegistry.getProviderDisplayName(it) } ?: ""
                Text(
                    text = stringResource("settings.model.select", providerDisplayName),
                    style = AppTextStyles.body,
                )

                viewModel.pendingModelForNewProvider?.let { selectedModel ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = AppColors.cardColors(AppColors.Elevation.RAISED)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = stringResource("settings.model.selected"), style = AppTextStyles.fieldLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = selectedModel, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource("settings.model.search.placeholder")) },
                    label = { Text(stringResource("settings.model.search")) },
                    singleLine = true,
                    colors = AppColors.outlinedTextFieldColors(),
                )

                if (searchQuery.isNotBlank() && filteredModels.isNotEmpty()) {
                    Text(
                        text = stringResource("settings.model.filtered", filteredModels.size, displayModels.size),
                        style = AppTextStyles.caption,
                    )
                }
            }
        } else {
            null
        },
        actions = {
            // Left side: testing indicator + Cancel
            if (viewModel.wizardStep == WizardStep.CONFIG && viewModel.isFetchingModelsForConfig) {
                val infiniteTransition = rememberInfiniteTransition(label = "dots")
                val tick by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 4f,
                    animationSpec = infiniteRepeatable(animation = tween(1_200, easing = LinearEasing)),
                    label = "dots_tick",
                )
                val dots = ".".repeat((tick.toInt() % 4).coerceAtLeast(1))
                Text(
                    text = stringResource("settings.test.connection.testing") + dots,
                    style = AppTextStyles.caption,
                )
                Spacer(Modifier.width(Spacing.small))
            }
            secondaryButton(onClick = { viewModel.closeProviderWizard() }) {
                Text(stringResource("settings.cancel"))
            }

            Spacer(Modifier.weight(1f))

            // Right side: navigation + primary action
            when (viewModel.wizardStep) {
                WizardStep.MODEL -> {
                    secondaryButton(onClick = { viewModel.wizardBack() }) {
                        Text(stringResource("action.back"))
                    }
                    Spacer(Modifier.width(Spacing.small))
                    primaryButton(
                        onClick = { viewModel.saveProvider() },
                        enabled = viewModel.pendingModelForNewProvider != null,
                    ) {
                        Text(stringResource("settings.save"))
                    }
                }

                WizardStep.CONFIG -> {
                    secondaryButton(onClick = { viewModel.wizardBack() }) {
                        Text(stringResource("action.back"))
                    }
                    Spacer(Modifier.width(Spacing.small))

                    if (viewModel.isAddingNewInstance) {
                        // Add mode: explicit Next button, enabled once connection verified
                        primaryButton(
                            onClick = { viewModel.advanceToModelPicker() },
                            enabled = viewModel.connectionTestSuccess && !viewModel.isFetchingModelsForConfig,
                        ) {
                            if (viewModel.isFetchingModelsForConfig) {
                                AppComponents.loadingSpinner(size = 16.dp)
                                Spacer(Modifier.width(Spacing.small))
                            }
                            Text(stringResource("action.next"))
                        }
                    } else {
                        // Edit mode: save directly
                        primaryButton(
                            onClick = { viewModel.saveProvider() },
                            enabled = !viewModel.isTestingConnection && !viewModel.isFetchingModelsForConfig,
                        ) {
                            if (viewModel.isTestingConnection) {
                                AppComponents.loadingSpinner(size = 16.dp)
                                Spacer(Modifier.width(Spacing.small))
                            }
                            Text(stringResource("settings.save"))
                        }
                    }
                }

                WizardStep.TYPE_PICKER -> {
                    primaryButton(
                        onClick = { viewModel.connectEntry() },
                        enabled = viewModel.selectedEntry != null,
                    ) {
                        Text(stringResource("provider.connect.button"))
                    }
                }
            }
        },
    ) {
        when (viewModel.wizardStep) {
            WizardStep.TYPE_PICKER -> providerTypePickerScreen(viewModel)

            WizardStep.CONFIG -> Column(modifier = Modifier.padding(vertical = AppComponents.dialogSectionSpacing)) {
                instanceConfigScreen(viewModel)
            }

            WizardStep.MODEL -> Column(modifier = Modifier.padding(vertical = AppComponents.dialogSectionSpacing)) {
                modelPickerScreen(
                    viewModel = viewModel,
                    filteredModels = filteredModels,
                    searchQuery = searchQuery,
                    isFiltered = isFiltered,
                    showAll = showAll,
                    onShowAll = { showAll = true },
                )
            }
        }
    }
}

// ── Screen 2: Provider type picker (two-column master-detail) ─────────────────────────────

@Composable
private fun providerTypePickerScreen(viewModel: ProviderWizardViewModel) {
    val entries = viewModel.availableEntries
    val mainEntries = remember(entries) { entries.filterNot { it is ProviderEntry.Custom } }
    val hasCustom = remember(entries) { entries.any { it is ProviderEntry.Custom } }

    var searchQuery by remember(viewModel.wizardStep) { mutableStateOf("") }
    val filteredMainEntries = remember(mainEntries, searchQuery) {
        if (searchQuery.isBlank()) {
            mainEntries
        } else {
            mainEntries.filter { entry ->
                val name = when (entry) {
                    is ProviderEntry.Native -> entry.provider.name
                    is ProviderEntry.Template -> entry.template.displayName
                    is ProviderEntry.Custom -> ""
                }
                name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(460.dp),
    ) {
        // ── Left column: search field + scrollable provider list ──────────────────────────
        val listState = rememberLazyListState()
        Column(modifier = Modifier.width(260.dp).fillMaxHeight()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                placeholder = { Text(stringResource("provider.search.placeholder"), style = AppTextStyles.caption) },
                singleLine = true,
                colors = AppColors.outlinedTextFieldColors(),
                textStyle = AppTextStyles.caption,
            )
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (filteredMainEntries.isEmpty() && searchQuery.isNotBlank()) {
                        item(key = "no_results") {
                            Text(
                                text = stringResource("provider.search.no_results"),
                                style = AppTextStyles.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = Spacing.medium),
                            )
                        }
                    } else {
                        items(filteredMainEntries, key = { entry ->
                            when (entry) {
                                is ProviderEntry.Native -> "native_${entry.provider.name}"
                                is ProviderEntry.Template -> "template_${entry.template.name}"
                                is ProviderEntry.Custom -> "custom"
                            }
                        }) { entry ->
                            providerPickerEntryRow(
                                entry = entry,
                                isSelected = viewModel.selectedEntry == entry,
                                onClick = { viewModel.selectEntryForPreview(entry) },
                            )
                        }
                    }

                    if (hasCustom) {
                        item(key = "custom_divider") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.extraSmall))
                        }
                        item(key = "custom_entry") {
                            providerPickerEntryRow(
                                entry = ProviderEntry.Custom,
                                isSelected = viewModel.selectedEntry is ProviderEntry.Custom,
                                onClick = { viewModel.selectEntryForPreview(ProviderEntry.Custom) },
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                    style = AppComponents.scrollbarStyle(),
                )
            }
        }

        // ── Vertical divider ──────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        // ── Right column: detail panel or empty hint ──────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(Spacing.medium),
        ) {
            val selected = viewModel.selectedEntry
            if (selected == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource("provider.type.picker.select.hint"),
                        style = AppTextStyles.caption,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                providerPickerDetail(entry = selected)
            }
        }
    }
}

// ── Provider picker — entry badge ─────────────────────────────────────────────────────────

@Composable
private fun entryBadge(entry: ProviderEntry) {
    val bg = when (entry) {
        is ProviderEntry.Native -> MaterialTheme.colorScheme.secondaryContainer
        is ProviderEntry.Template -> MaterialTheme.colorScheme.tertiaryContainer
        is ProviderEntry.Custom -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when (entry) {
        is ProviderEntry.Native -> MaterialTheme.colorScheme.onSecondaryContainer
        is ProviderEntry.Template -> MaterialTheme.colorScheme.onTertiaryContainer
        is ProviderEntry.Custom -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val initials = when (entry) {
        is ProviderEntry.Native -> entry.provider.initials
        is ProviderEntry.Template -> entry.template.initials
        is ProviderEntry.Custom -> "+"
    }
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = AppTextStyles.hint,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1,
        )
    }
}

// ── Provider picker — left-column row ─────────────────────────────────────────────────────

@Composable
private fun providerPickerEntryRow(
    entry: ProviderEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val displayName = when (entry) {
        is ProviderEntry.Native -> ProviderRegistry.getProviderDisplayName(entry.provider)
        is ProviderEntry.Template -> entry.template.displayName
        is ProviderEntry.Custom -> stringResource("provider.other.title")
    }
    val bg = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val textColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entryBadge(entry = entry)
        Text(
            text = displayName,
            style = AppTextStyles.caption,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Provider picker — right-column detail ─────────────────────────────────────────────────

@Composable
private fun providerPickerDetail(
    entry: ProviderEntry,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        when (entry) {
            is ProviderEntry.Native -> {
                Text(
                    text = ProviderRegistry.getProviderDisplayName(entry.provider),
                    style = AppTextStyles.sectionTitle,
                )
                val description = ProviderRegistry.getProviderShortDescription(entry.provider)
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = AppTextStyles.bodySecondary,
                    )
                }
                val apiKeyUrl = ProviderRegistry.getProviderApiKeyUrl(entry.provider)
                if (apiKeyUrl.isNotBlank()) {
                    linkButton(
                        onClick = {
                            try {
                                Desktop.getDesktop().browse(URI(apiKeyUrl))
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Text(
                            text = stringResource("provider.template.get.apikey"),
                            style = AppTextStyles.caption,
                        )
                    }
                }
            }

            is ProviderEntry.Template -> {
                Text(
                    text = entry.template.displayName,
                    style = AppTextStyles.sectionTitle,
                )
                Text(
                    text = stringResource(entry.template.taglineKey),
                    style = AppTextStyles.bodySecondary,
                )

                if (entry.template.apiKeyUrl.isNotBlank()) {
                    linkButton(
                        onClick = {
                            try {
                                Desktop.getDesktop().browse(URI(entry.template.apiKeyUrl))
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Text(
                            text = stringResource("provider.template.get.apikey"),
                            style = AppTextStyles.caption,
                        )
                    }
                }

                // Setup instructions specific to this template
                Card(colors = AppColors.cardColors(AppColors.Elevation.RAISED)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = stringResource(entry.template.helpTextKey), style = AppTextStyles.caption)
                    }
                }
            }

            is ProviderEntry.Custom -> {
                Text(
                    text = stringResource("provider.other.title"),
                    style = AppTextStyles.sectionTitle,
                )
                Text(
                    text = stringResource("provider.other.description"),
                    style = AppTextStyles.bodySecondary,
                )
            }
        }
    }
}

@Composable
private fun instanceConfigScreen(viewModel: ProviderWizardViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        // Instance display-name field
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(text = stringResource("provider.instance.name.label"), style = AppTextStyles.sectionTitle)
            OutlinedTextField(
                value = if (viewModel.isAddingNewInstance) viewModel.newInstanceDisplayName else viewModel.editingInstanceDisplayName,
                onValueChange = {
                    if (viewModel.isAddingNewInstance) {
                        viewModel.updateNewInstanceDisplayName(it)
                    } else {
                        viewModel.updateEditingInstanceDisplayName(it)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = viewModel.displayNameError != null,
                placeholder = { Text(stringResource("provider.instance.name.placeholder")) },
                supportingText = viewModel.displayNameError?.let { error ->
                    { Text(text = error, style = AppTextStyles.errorText) }
                },
                colors = AppColors.outlinedTextFieldColors(),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.small))

        // Provider config fields (API key, base URL, etc.)
        if (viewModel.providerConfigFields.isNotEmpty()) {
            Text(text = stringResource("provider.configure.prompt"), style = AppTextStyles.sectionTitle)

            viewModel.providerConfigFields.forEach { field ->
                when (field) {
                    is ProviderConfigField.InfoField -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = field.message, style = AppTextStyles.caption)
                            }
                        }
                    }

                    else -> {
                        val fieldHint: (@Composable () -> Unit)? = if (field is ProviderConfigField.SelectField) {
                            val currentValue = viewModel.providerFieldValues[field.name] ?: field.value
                            field.options.find { it.value == currentValue }?.description
                                ?.takeIf { it.isNotBlank() }
                                ?.let { desc -> { Text(text = desc, style = AppTextStyles.caption) } }
                        } else {
                            null
                        }

                        AppComponents.formField(
                            label = field.label,
                            description = field.description,
                            required = field.required,
                            hint = fieldHint,
                        ) {
                            when (field) {
                                is ProviderConfigField.ApiKeyField -> {
                                    AppComponents.appSecretTextField(
                                        value = viewModel.providerFieldValues[field.name] ?: "",
                                        onValueChange = { viewModel.updateProviderField(field.name, it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = {
                                            Text(if (field.hasExistingValue) stringResource("provider.apikey.stored") else stringResource("provider.apikey.enter"))
                                        },
                                    )
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.small),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                            Text(text = stringResource("provider.apikey.security.message"), style = AppTextStyles.caption)
                                            linkButton(onClick = {
                                                try {
                                                    Desktop.getDesktop().browse(URI("https://$DOMAIN/security/"))
                                                } catch (_: Exception) {}
                                            }, modifier = Modifier.padding(0.dp)) {
                                                Text(text = stringResource("provider.apikey.security.link"), style = AppTextStyles.caption)
                                            }
                                        }
                                    }
                                }

                                is ProviderConfigField.BaseUrlField -> {
                                    OutlinedTextField(
                                        value = viewModel.providerFieldValues[field.name] ?: "",
                                        onValueChange = { viewModel.updateProviderField(field.name, it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        placeholder = { Text(stringResource("settings.placeholder.baseurl")) },
                                        colors = AppColors.outlinedTextFieldColors(),
                                    )
                                }

                                is ProviderConfigField.SelectField -> {
                                    val currentValue = viewModel.providerFieldValues[field.name] ?: field.value
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                        field.options.forEach { option ->
                                            if (currentValue == option.value) {
                                                primaryButton(onClick = {}) {
                                                    Text(option.label)
                                                }
                                            } else {
                                                secondaryButton(onClick = { viewModel.updateProviderField(field.name, option.value) }) {
                                                    Text(option.label)
                                                }
                                            }
                                        }
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                }
            }

            // Help link
            viewModel.selectedProvider?.let { provider ->
                linkButton(
                    onClick = {
                        try {
                            Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/providers/${provider.name.lowercase()}/"))
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.padding(0.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Help", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.extraSmall))
                    Text(
                        text = stringResource("provider.setup.guide", ProviderRegistry.getProviderDisplayName(provider)),
                        style = AppTextStyles.caption,
                    )
                }
            }

            // Connection error
            if (viewModel.connectionError != null) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(Spacing.medium), horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                        Column {
                            Text(text = viewModel.connectionError ?: "", style = AppTextStyles.body, color = MaterialTheme.colorScheme.onErrorContainer)
                            viewModel.connectionErrorHelp?.let {
                                Spacer(Modifier.height(Spacing.extraSmall))
                                Text(text = it, style = AppTextStyles.caption, color = AppColors.cardSecondaryContentColor(MaterialTheme.colorScheme.onErrorContainer))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Screen 4: Model picker ─────────────────────────────────────────────────────────────────

@Composable
private fun modelPickerScreen(
    viewModel: ProviderWizardViewModel,
    filteredModels: List<ModelDTO>,
    searchQuery: String,
    isFiltered: Boolean,
    showAll: Boolean,
    onShowAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        when {
            viewModel.isLoadingModels -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    AppComponents.loadingSpinner()
                    Text(text = stringResource("settings.model.loading"), modifier = Modifier.padding(start = Spacing.large))
                }
            }

            viewModel.modelError != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(text = viewModel.modelError ?: "", color = MaterialTheme.colorScheme.error, style = AppTextStyles.body)
                    viewModel.modelErrorHelp?.let {
                        Card(colors = AppColors.cardColors(AppColors.Elevation.RAISED)) {
                            Text(text = it, style = AppTextStyles.caption, modifier = Modifier.padding(Spacing.medium))
                        }
                    }
                }
            }

            viewModel.availableModels.isEmpty() -> {
                Text(text = stringResource("settings.model.none"), style = AppTextStyles.bodySecondary)
            }

            else -> {
                // Description, selected-model card and search field are in stickyHeader.
                // Only the model list scrolls here.
                if (filteredModels.isEmpty()) {
                    Text(
                        text = stringResource("settings.model.no.match"),
                        style = AppTextStyles.caption,
                        modifier = Modifier.padding(Spacing.large),
                    )
                } else {
                    groupedModelListAsCards(
                        models = filteredModels,
                        selectedModelId = viewModel.pendingModelForNewProvider,
                        onModelClick = { viewModel.selectModelForNewProvider(it) },
                        showHeaders = true,
                    )
                }

                if (isFiltered && !showAll && searchQuery.isBlank()) {
                    linkButton(onClick = onShowAll, modifier = Modifier.padding(top = Spacing.small)) {
                        Text(text = "Show all ${viewModel.availableModels.size} models", style = AppTextStyles.caption)
                    }
                }
            }
        }
    }
}
