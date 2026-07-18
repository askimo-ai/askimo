/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.askimo.core.VersionInfo
import io.askimo.core.context.AppContext
import io.askimo.core.context.getConfigInfo
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.providers.ProviderInstanceService
import io.askimo.core.providers.ProviderRegistry
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.shell.notificationIcon
import org.koin.java.KoinJavaComponent.get

// ── Footer AI config section ───────────────────────────────────────────────────────────────

@Composable
private fun aiConfigInfo(
    onAddProvider: () -> Unit,
) {
    val appContext = remember { get<AppContext>(AppContext::class.java) }
    val providerInstanceService = remember { get<ProviderInstanceService>(ProviderInstanceService::class.java) }
    val scope = rememberCoroutineScope()
    var configInfo by remember { mutableStateOf(appContext.getConfigInfo()) }

    // Keep configInfo in sync with model/instance changes broadcast on the event bus.
    LaunchedEffect(Unit) {
        EventBus.internalEvents.collect { event ->
            if (event is ModelChangedEvent) {
                configInfo = appContext.getConfigInfo()
            }
        }
    }

    val panelState = remember(appContext) { ProviderModelPanelState(scope, appContext, providerInstanceService) }
    var panelExpanded by remember { mutableStateOf(false) }

    // Measure the trigger card width so the panel can be centred beneath it.
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            triggerWidthPx = coords.size.width
        },
    ) {
        // ── Trigger button ────────────────────────────────────────────────────────────────
        themedTooltip(text = stringResource("system.ai.provider.tooltip", ProviderRegistry.getProviderDisplayName(configInfo.provider))) {
            Card(
                modifier = Modifier
                    .clickableCard {
                        if (!panelExpanded) {
                            panelState.init()
                            panelExpanded = true
                        }
                    }
                    .widthIn(min = 120.dp, max = 320.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    val instanceLabel = configInfo.instanceDisplayName.ifBlank {
                        ProviderRegistry.getProviderDisplayName(configInfo.provider)
                    }
                    Text(
                        text = instanceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (configInfo.model.isNotBlank()) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = configInfo.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = if (panelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Two-column panel ──────────────────────────────────────────────────────────────
        // Shift left so the panel centre aligns with the trigger card centre.
        val triggerWidthDp = with(density) { triggerWidthPx.toDp() }
        val menuOffsetX = -(MODEL_PANEL_WIDTH / 2 - triggerWidthDp / 2)

        providerModelPanel(
            expanded = panelExpanded,
            state = panelState,
            currentInstanceId = configInfo.instanceId,
            currentModel = configInfo.model,
            menuOffset = DpOffset(x = menuOffsetX, y = 0.dp),
            onDismiss = {
                panelExpanded = false
                panelState.reset()
            },
            onAddProvider = {
                panelExpanded = false
                panelState.reset()
                onAddProvider()
            },
        )
    }
}

// ── Footer bar ─────────────────────────────────────────────────────────────────────────────

@Composable
fun footerBar(
    onShowUpdateDetails: () -> Unit = {},
    onAddProvider: () -> Unit = {},
    onShowAbout: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppComponents.sidebarSurfaceColor()),
    ) {
        HorizontalDivider()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            themedTooltip(text = stringResource("menu.about")) {
                Text(
                    text = "v${VersionInfo.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickableCard { onShowAbout() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            // Centre — unified provider + model selector
            Box(modifier = Modifier.align(Alignment.Center)) {
                aiConfigInfo(onAddProvider = onAddProvider)
            }

            // Right — notification bell
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                notificationIcon(onShowUpdateDetails = onShowUpdateDetails)
            }
        }
    }
}
