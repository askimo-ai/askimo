/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.context.AppContext
import io.askimo.core.context.getConfigInfo
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderSettings
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.monitoring.SystemResourceMonitor
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.shell.notificationIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import java.awt.Desktop
import java.net.URI

private val log = currentFileLogger()

@Composable
private fun aiConfigInfo(onConfigureAiProvider: () -> Unit) {
    val appContext = remember { get<AppContext>(AppContext::class.java) }
    var configInfo by remember { mutableStateOf(appContext.getConfigInfo()) }

    LaunchedEffect(Unit) {
        EventBus.internalEvents.collect { event ->
            if (event is ModelChangedEvent) {
                configInfo = appContext.getConfigInfo()
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        providerButton(
            currentProvider = configInfo.provider,
            onConfigureProvider = onConfigureAiProvider,
        )

        modelDropdown(
            currentProvider = configInfo.provider,
            currentModel = configInfo.model,
            onModelSelected = { newModel ->
                val appContext = get<AppContext>(AppContext::class.java)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        appContext.params.model = newModel
                        appContext.save()
                        EventBus.emit(ModelChangedEvent(configInfo.provider, newModel))
                    } catch (e: Exception) {
                        log.error("Failed to change model to $newModel for provider ${configInfo.provider}", e)
                    }
                }
            },
        )
    }
}

@Composable
private fun providerButton(
    currentProvider: ModelProvider,
    onConfigureProvider: () -> Unit,
) {
    themedTooltip(
        text = stringResource("system.ai.provider.tooltip", currentProvider.name),
    ) {
        Card(
            modifier = Modifier
                .clickableCard { onConfigureProvider() }
                .widthIn(min = 100.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = currentProvider.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun modelDropdown(
    currentProvider: ModelProvider,
    currentModel: String,
    onModelSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<ModelDTO>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val appContext = remember { get<AppContext>(AppContext::class.java) }
    val scope = rememberCoroutineScope()

    // Load models when provider changes or when dropdown is opened
    LaunchedEffect(currentProvider, expanded) {
        if (expanded && availableModels.isEmpty()) {
            isLoading = true
            scope.launch {
                try {
                    val settings = appContext.getOrCreateProviderSettings(currentProvider)
                    val factory = appContext.getModelFactory(currentProvider)
                    if (factory != null) {
                        @Suppress("UNCHECKED_CAST")
                        val models = (factory as ChatModelFactory<ProviderSettings>)
                            .availableModels(settings)
                        availableModels = models
                    }
                } catch (e: Exception) {
                    log.error("Can not get models", e)
                    availableModels = emptyList()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Reset models and search when provider changes
    LaunchedEffect(currentProvider) {
        availableModels = emptyList()
        searchQuery = ""
    }

    Box {
        Card(
            modifier = Modifier
                .clickableCard { expanded = true }
                .widthIn(min = 60.dp, max = 250.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentModel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AppComponents.dropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            when {
                isLoading -> {
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource("settings.model.loading"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {},
                        enabled = false,
                    )
                }

                availableModels.isEmpty() -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource("settings.model.none"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }

                else -> {
                    // Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = stringResource("settings.model.search"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = AppComponents.outlinedTextFieldColors(),
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Filter models based on search
                    val filteredModels = availableModels.filter {
                        it.displayName.contains(searchQuery, ignoreCase = true) ||
                            it.modelId.contains(searchQuery, ignoreCase = true)
                    }

                    // Scrollable list with fixed dimensions to avoid intrinsic measurement issues
                    val listState = rememberLazyListState()

                    Box(
                        modifier = Modifier
                            .width(300.dp)
                            .height(400.dp),
                    ) {
                        if (filteredModels.isEmpty()) {
                            // Show "no results" message if search yields nothing
                            Text(
                                text = stringResource("settings.model.no.results"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            // Use LazyColumn so item clicks are not swallowed by the scroll modifier
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val groupedModels = filteredModels.groupBy { it.provider }
                                val showHeaders = groupedModels.size > 1
                                groupedModels.forEach { (provider, providerModels) ->
                                    if (showHeaders && providerModels.isNotEmpty()) {
                                        item(key = "header_$provider") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = dto.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                            },
                                            onClick = {
                                                onModelSelected(dto.modelId)
                                                expanded = false
                                                searchQuery = ""
                                            },
                                            leadingIcon = if (dto.modelId == currentModel) {
                                                {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = "Current model",
                                                        tint = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                        )
                                    }
                                }
                            }

                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(listState),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(end = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Footer bar component showing system resources, notifications, and version info.
 */
@Composable
fun footerBar(
    onShowUpdateDetails: () -> Unit = {},
    onConfigureAiProvider: () -> Unit = {},
) {
    val resourceMonitor = remember { get<SystemResourceMonitor>(SystemResourceMonitor::class.java) }
    val appContext = remember { get<AppContext>(AppContext::class.java) }

    val memoryUsage by resourceMonitor.memoryUsageMB.collectAsState()
    val cpuUsage by resourceMonitor.cpuUsagePercent.collectAsState()
    val metrics by appContext.telemetry.metricsFlow.collectAsState()
    var telemetryExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(resourceMonitor) {
        resourceMonitor.startMonitoring(intervalMillis = 2000)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppComponents.sidebarSurfaceColor()),
    ) {
        // Top border
        HorizontalDivider()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .widthIn(max = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("system.memory") + ":",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "$memoryUsage MB",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("system.cpu") + ":",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "%.1f%%".format(cpuUsage),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Box(modifier = Modifier.align(Alignment.Center)) {
                aiConfigInfo(onConfigureAiProvider = onConfigureAiProvider)
            }

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                themedTooltip(
                    text = if (telemetryExpanded) {
                        stringResource("telemetry.hide")
                    } else {
                        stringResource("telemetry.show")
                    },
                ) {
                    IconButton(
                        onClick = { telemetryExpanded = !telemetryExpanded },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = if (telemetryExpanded) {
                                stringResource("telemetry.hide")
                            } else {
                                stringResource("telemetry.show")
                            },
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                themedTooltip(text = stringResource("system.share.feedback")) {
                    TextButton(
                        onClick = {
                            runCatching {
                                Desktop.getDesktop().browse(URI("https://$DOMAIN/contact/"))
                            }
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(
                            text = stringResource("system.share.feedback"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                notificationIcon(onShowUpdateDetails = onShowUpdateDetails)
            }
        }

        if (telemetryExpanded) {
            HorizontalDivider()
            telemetryPanel(metrics, 250.dp)
        }
    }
}
