/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.askimo.core.context.AppContext
import io.askimo.core.context.getConfigInfo
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstanceService
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.llama.LlamaServerCatalog
import io.askimo.core.providers.llama.LlamaServerDownloader
import io.askimo.core.providers.llama.LlamaServerProcessManager
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleTemplate
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.shell.notificationIcon
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

// ── Footer AI config section ───────────────────────────────────────────────────────────────

/**
 * Small colored badge overlaid on the footer's provider icon, shown only when the active
 * instance is the built-in Local LLM provider ([OpenAiCompatibleTemplate.ASKIMO_LOCAL_LLM])
 * **and** the engine binary is installed (gated by the caller via
 * [LlamaServerDownloader.binaryEnabled] — see [aiConfigInfo]) — surfaces whether
 * [LlamaServerProcessManager]'s reverse proxy/child process is actually reachable *before* the
 * user sends a message and hits a proxy error (e.g. after manually stopping the engine in
 * Settings or the footer pill).
 */
@Composable
private fun localLlmStatusBadge(
    proxyRunning: Boolean,
    modelLoaded: Boolean,
    port: Int?,
    onClick: () -> Unit,
) {
    val (color, tooltip) = when {
        !proxyRunning -> MaterialTheme.colorScheme.error to stringResource("footer.local_llm.stopped")
        !modelLoaded -> AppColors.warningColor() to stringResource("footer.local_llm.idle", port ?: 0)
        else -> MaterialTheme.colorScheme.primary to stringResource("footer.local_llm.ready", port ?: 0)
    }
    themedTooltip(text = tooltip) {
        // Outer box is the "ring" (card's own background color) that visually cuts the badge
        // out from the icon behind it, like an avatar online-status indicator.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(enabled = !proxyRunning, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Standalone "Local LLM" status pill shown in the footer next to the notification bell —
 * independent of which provider instance is currently active, since the `llama-server` engine
 * can be started/stopped from Settings (or right here) regardless of what's selected for chat
 * right now. Clicking it toggles the proxy: starts it when stopped, stops it when running.
 * Visibility is bound to [LlamaServerDownloader.binaryEnabled] (whether the engine binary is
 * downloaded) — reflects a Settings-page enable/disable toggle immediately in the same session,
 * and correctly shows/hides again after an app restart since that flag is re-seeded from disk.
 */
@Composable
private fun localLlmEngineStatusPill() {
    val platform = remember { LlamaServerCatalog.detectCurrentPlatform() }
    LaunchedEffect(platform) {
        if (platform != null) {
            LlamaServerDownloader.refreshBinaryStatus(platform)
        }
    }
    val binaryDownloaded by LlamaServerDownloader.binaryEnabled.collectAsState()
    if (!binaryDownloaded) return

    val scope = rememberCoroutineScope()
    val proxyStatus by LlamaServerProcessManager.proxyStatus.collectAsState()
    val llmStatus by LlamaServerProcessManager.status.collectAsState()

    // Status color still reflects the 3-state reality (stopped/idle/ready); the icon now
    // separately conveys the *action* a click will take (▶ to start, ■ to stop), so the
    // affordance isn't just "a colored dot" — it's unambiguous even without reading the tooltip.
    val (statusColor, tooltip) = when {
        !proxyStatus.running -> MaterialTheme.colorScheme.error to stringResource("footer.local_llm.stopped")
        !llmStatus.running -> AppColors.warningColor() to stringResource("footer.local_llm.idle", llmStatus.port ?: proxyStatus.port ?: 0)
        else -> MaterialTheme.colorScheme.primary to stringResource("footer.local_llm.ready", llmStatus.port ?: proxyStatus.port ?: 0)
    }
    val actionIcon = if (proxyStatus.running) Icons.Default.Stop else Icons.Default.PlayArrow

    themedTooltip(text = tooltip) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(statusColor.copy(alpha = 0.12f))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable {
                    scope.launch {
                        runCatching {
                            if (proxyStatus.running) {
                                LlamaServerProcessManager.stopProxy()
                            } else {
                                LlamaServerProcessManager.startProxy()
                            }
                        }
                    }
                }
                .padding(horizontal = Spacing.extraSmall, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = actionIcon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = statusColor,
            )
            Text(
                text = stringResource("footer.local_llm.label"),
                style = AppTextStyles.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun aiConfigInfo(
    onAddProvider: () -> Unit,
) {
    val appContext = remember { get<AppContext>(AppContext::class.java) }
    val providerInstanceService = remember { get<ProviderInstanceService>(ProviderInstanceService::class.java) }
    val scope = rememberCoroutineScope()
    var configInfo by remember { mutableStateOf(appContext.getConfigInfo()) }

    LaunchedEffect(Unit) {
        EventBus.internalEvents.collect { event ->
            if (event is ModelChangedEvent) {
                configInfo = appContext.getConfigInfo()
            }
        }
    }

    val isLocalLlm = remember(configInfo.instanceId) {
        (appContext.getActiveInstance()?.settings as? OpenAiCompatibleSettings)
            ?.let { it.isTemplate && it.templateName == OpenAiCompatibleTemplate.ASKIMO_LOCAL_LLM.name } == true
    }
    val proxyStatus by LlamaServerProcessManager.proxyStatus.collectAsState()
    val llmStatus by LlamaServerProcessManager.status.collectAsState()

    // Badge visibility is gated on whether the llama-server binary is installed (same signal as
    // localLlmEngineStatusPill) — not on whether the proxy is currently enabled/disabled, which
    // only affects the badge's *color* (see localLlmStatusBadge's proxyRunning/modelLoaded
    // params). A user who's merely stopped the engine (still installed) should still see the
    // badge turn red, rather than it vanishing entirely.
    val localLlmPlatform = remember { LlamaServerCatalog.detectCurrentPlatform() }
    LaunchedEffect(localLlmPlatform) {
        if (localLlmPlatform != null) {
            LlamaServerDownloader.refreshBinaryStatus(localLlmPlatform)
        }
    }
    val localLlmBinaryInstalled by LlamaServerDownloader.binaryEnabled.collectAsState()

    val noProvider = configInfo.provider == ModelProvider.UNKNOWN

    // When no provider is configured, show a prominent "Add Provider" CTA so
    // users have an obvious entry point directly in the footer.
    if (noProvider) {
        themedTooltip(text = stringResource("provider.setup.required.title")) {
            Card(
                modifier = Modifier
                    .clickableCard { onAddProvider() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .widthIn(min = 120.dp, max = 320.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.extraSmall),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource("provider.setup.empty.state.button"),
                        style = AppTextStyles.caption,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        return
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
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.extraSmall),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isLocalLlm && localLlmBinaryInstalled) {
                            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                                localLlmStatusBadge(
                                    proxyRunning = proxyStatus.running,
                                    modelLoaded = llmStatus.running,
                                    port = llmStatus.port ?: proxyStatus.port,
                                    onClick = { scope.launch { runCatching { LlamaServerProcessManager.startProxy() } } },
                                )
                            }
                        }
                    }
                    val instanceLabel = configInfo.instanceDisplayName.ifBlank {
                        ProviderRegistry.getProviderDisplayName(configInfo.provider)
                    }
                    Text(
                        text = instanceLabel,
                        style = AppTextStyles.caption,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (configInfo.model.isNotBlank()) {
                        Text(
                            text = "·",
                            style = AppTextStyles.caption,
                        )
                        Text(
                            text = configInfo.model,
                            style = AppTextStyles.caption,
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.sidebarSurfaceColor()),
    ) {
        HorizontalDivider()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.large, vertical = Spacing.small),
        ) {
            // Centre — unified provider + model selector
            Box(modifier = Modifier.align(Alignment.Center)) {
                aiConfigInfo(onAddProvider = onAddProvider)
            }

            // Right — Local LLM engine status + notification bell
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                localLlmEngineStatusPill()
                notificationIcon(onShowUpdateDetails = onShowUpdateDetails)
            }
        }
    }
}
