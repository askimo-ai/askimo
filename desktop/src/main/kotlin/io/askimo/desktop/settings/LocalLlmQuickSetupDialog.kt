/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.providers.llama.HuggingFaceGgufBrowser
import io.askimo.core.providers.llama.LlamaDownloadManager
import io.askimo.core.providers.llama.LlamaModelDownloader
import io.askimo.core.providers.llama.LlamaModelEntry
import io.askimo.core.providers.llama.LlamaServerCatalog
import io.askimo.core.providers.llama.LlamaServerDownloader
import io.askimo.core.providers.llama.LlamaServerPlatform
import io.askimo.core.providers.llama.LlamaServerProcessManager
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast first-run path for the built-in local LLM provider, launched from the "Use Built-in
 * Local AI" CTA in the no-provider-configured dialog (`Main.kt`). Minimizes decisions versus
 * the full [localLlmSettingsSection] Settings page (manual switch, full HF search, tuning,
 * deletion):
 *
 * 1. **Binary**: auto-downloads `llama-server`, then starts the reverse proxy.
 * 2. **Model**: shows one starter pick per family (Gemma, Llama, Qwen — see
 *    [HuggingFaceGgufBrowser.starterPicks]), ~3-5 GB each, one-click download; "Browse all
 *    models" opens full Settings for anything else.
 * 3. On a (non-embedding) model's download completing, [autoFinishLocalLlmProviderSetup]
 *    creates & activates the "Local LLM (built-in)" provider and shows a success state.
 *
 * Assumes the hardware-capability check (`LlamaCapabilityChecker`) already passed — the caller
 * gates entry to this dialog.
 */
@Composable
fun localLlmQuickSetupDialog(onDismiss: () -> Unit, onOpenFullSettings: () -> Unit) {
    val platform: LlamaServerPlatform? = remember { LlamaServerCatalog.detectCurrentPlatform() }

    // ── Phase 1: binary download + engine start ────────────────────────────────────────────
    val binaryDownloadState by LlamaDownloadManager.binaryDownload.collectAsState()
    val binaryDownloading = binaryDownloadState != null && binaryDownloadState?.error == null
    var isBinaryDownloaded by remember {
        mutableStateOf(platform != null && LlamaServerDownloader.isDownloaded(platform))
    }

    // Kicks off the download as soon as the dialog opens — this dialog's whole reason to exist
    // is skipping the manual "enable" switch in localLlmBinaryCard. Also auto-retries a prior
    // failure, since this flow has no Retry action of its own; startBinaryDownload() no-ops if
    // a download is already in progress, so calling it unconditionally here is safe.
    LaunchedEffect(platform) {
        if (platform != null && !isBinaryDownloaded) {
            LlamaDownloadManager.startBinaryDownload(platform)
        }
    }

    // Refreshes isBinaryDownloaded once the download finishes — downloads run app-scoped, not
    // tied to this dialog's lifecycle (see LlamaDownloadManager).
    LaunchedEffect(binaryDownloadState, platform) {
        if (platform != null && binaryDownloadState == null) {
            val nowDownloaded = LlamaServerDownloader.isDownloaded(platform)
            if (nowDownloaded != isBinaryDownloaded) isBinaryDownloaded = nowDownloaded
        }
    }

    LaunchedEffect(platform, isBinaryDownloaded) {
        if (platform != null && isBinaryDownloaded) {
            LlamaServerProcessManager.startProxy()
        }
    }

    val proxyStatus by LlamaServerProcessManager.proxyStatus.collectAsState()

    // ── Phase 2: recommended models ─────────────────────────────────────────────────────────
    var recommendedModels by remember { mutableStateOf<List<LlamaModelEntry>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isBinaryDownloaded) {
        if (isBinaryDownloaded && recommendedModels.isEmpty() && !isLoadingModels) {
            isLoadingModels = true
            modelsError = null
            try {
                // starterPicks targets well-known families (Gemma/Llama/Qwen) directly and
                // prefers a ~3-5 GB quant per family — see its class doc for why that beats
                // popularModels() here (raw download-count sorting frequently surfaces
                // non-chat/esoteric repos before a recognizable family name).
                recommendedModels = HuggingFaceGgufBrowser.starterPicks()
            } catch (e: Exception) {
                modelsError = e.message ?: "Failed to load recommended models."
            } finally {
                isLoadingModels = false
            }
        }
    }

    // Rescans disk whenever a tracked download's key set changes (same pattern as
    // localLlmModelsCard) — the signal this dialog needs to know a model finished downloading.
    val modelDownloads by LlamaDownloadManager.modelDownloads.collectAsState()
    val downloadKeys = remember(modelDownloads) { modelDownloads.keys.toSet() }
    var downloadedEntries by remember { mutableStateOf<List<LlamaModelEntry>>(emptyList()) }
    LaunchedEffect(downloadKeys) {
        downloadedEntries = withContext(Dispatchers.IO) { LlamaModelDownloader.listDownloadedEntries() }
    }

    var autoFinishHandled by remember { mutableStateOf(false) }
    var autoFinishSucceeded by remember { mutableStateOf(false) }
    LaunchedEffect(proxyStatus.running, downloadedEntries) {
        if (autoFinishHandled || !proxyStatus.running) return@LaunchedEffect
        val chatEntry = downloadedEntries.firstOrNull { !it.isEmbedding } ?: return@LaunchedEffect
        autoFinishHandled = true
        autoFinishSucceeded = withContext(Dispatchers.IO) { autoFinishLocalLlmProviderSetup(chatEntry) }
    }

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 560.dp,
        title = { Text(text = stringResource("settings.local_llm.quick_setup.title"), style = AppTextStyles.pageTitle) },
        actions = {
            Spacer(Modifier.weight(1f))
            if (autoFinishHandled) {
                primaryButton(onClick = onDismiss) {
                    Text(stringResource("settings.local_llm.quick_setup.done"))
                }
            } else {
                secondaryButton(onClick = onDismiss) {
                    Text(stringResource("action.cancel"))
                }
            }
        },
    ) {
        if (platform == null) {
            Text(
                text = stringResource("settings.local_llm.binary.unsupported_platform"),
                style = AppTextStyles.body,
                color = AppColors.warningColor(),
            )
            return@scaffoldDialog
        }

        when {
            !isBinaryDownloaded -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(text = stringResource("settings.local_llm.quick_setup.phase1.title"), style = AppTextStyles.sectionTitle)
                    Text(
                        text = stringResource("settings.local_llm.quick_setup.phase1.description"),
                        style = AppTextStyles.bodySecondary,
                        color = AppTextStyles.secondaryContent,
                    )
                    if (binaryDownloading) {
                        downloadProgress(binaryDownloadState?.downloadedBytes ?: 0L, binaryDownloadState?.totalBytes ?: -1L)
                    }
                    binaryDownloadState?.error?.let { error ->
                        Text(
                            text = LocalizationManager.getString("settings.local_llm.download_failed", error),
                            style = AppTextStyles.caption,
                            color = AppColors.warningColor(),
                        )
                    }
                }
            }

            autoFinishHandled -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(
                        text = stringResource(
                            if (autoFinishSucceeded) "settings.local_llm.quick_setup.success.title" else "settings.local_llm.quick_setup.success.fallback_title",
                        ),
                        style = AppTextStyles.sectionTitle,
                    )
                    Text(
                        text = stringResource(
                            if (autoFinishSucceeded) "settings.local_llm.quick_setup.success.description" else "settings.local_llm.quick_setup.success.fallback_description",
                        ),
                        style = AppTextStyles.bodySecondary,
                        color = AppTextStyles.secondaryContent,
                    )
                }
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(text = stringResource("settings.local_llm.quick_setup.phase2.title"), style = AppTextStyles.sectionTitle)
                    Text(
                        text = stringResource("settings.local_llm.quick_setup.phase2.description"),
                        style = AppTextStyles.bodySecondary,
                        color = AppTextStyles.secondaryContent,
                    )

                    when {
                        isLoadingModels -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Spacing.small))
                            Text(stringResource("settings.local_llm.quick_setup.phase2.loading"), style = AppTextStyles.caption)
                        }

                        modelsError != null -> Text(
                            text = LocalizationManager.getString("settings.local_llm.quick_setup.phase2.error", modelsError ?: ""),
                            style = AppTextStyles.caption,
                            color = AppColors.warningColor(),
                        )

                        recommendedModels.isEmpty() -> Text(
                            text = stringResource("settings.local_llm.quick_setup.phase2.empty"),
                            style = AppTextStyles.caption,
                            color = AppTextStyles.secondaryContent,
                        )

                        else -> recommendedModels.forEach { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = AppColors.cardColors(AppColors.Elevation.RAISED),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.medium)) {
                                    localLlmModelRow(entry, onModelDeleted = {})
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = onOpenFullSettings,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(stringResource("settings.local_llm.quick_setup.browse_all"))
                    }
                }
            }
        }
    }
}
