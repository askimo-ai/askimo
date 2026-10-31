/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl.whispercpp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.i18n.LocalizationManager
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.clickableCard

/**
 * Model-tier picker + explicit "Download" button for [VoiceProvider.LOCAL_WHISPER_FFM] — the
 * download runs only when the user clicks it, never lazily on first transcription.
 *
 * The download itself is delegated to [WhisperModelDownloadManager], which runs in an app-scoped
 * coroutine — so it keeps running, and its progress stays observable, even if this composable's
 * caller (Settings > Voice, or `io.askimo.ui.voice.voiceModelRequiredDialog`) leaves composition
 * and remounts later. Completion/failure is also posted to [io.askimo.core.event.EventBus] so
 * the notification bell reflects it regardless of which screen the user is on.
 *
 * Shared (not `private`) so both call sites reuse the same download UI/logic.
 *
 * [modelPath] is the currently persisted [VoiceConfig.localWhisperModelPath]; [onModelPathChange]
 * is called with the downloaded file's path once a download completes (or "" after a delete) so
 * the caller can keep its own cached copy in sync.
 */
@Composable
fun whisperModelDownloadSection(
    modelPath: String,
    onModelPathChange: (String) -> Unit,
) {
    // State lives in WhisperModelDownloadManager (app-scoped), not here — so the download keeps
    // running (and this screen picks its progress back up) even if the user navigates away.
    val downloadState by WhisperModelDownloadManager.state.collectAsState()

    val currentTier = WhisperModelCatalog.entries.find { WhisperModelDownloader.modelPath(it).toString() == modelPath }
    // Prefer the manager's active tier over the persisted path — otherwise a recreated composable
    // mid-download would select the stale tier, breaking the progress/error gating below and
    // leaving a dead Download button.
    var selectedTier by remember {
        mutableStateOf(downloadState?.takeIf { it.isActive }?.tier ?: currentTier ?: WhisperModelCatalog.BALANCED)
    }
    val downloading = downloadState?.tier == selectedTier && downloadState?.isActive == true
    val downloadError = downloadState?.takeIf { it.tier == selectedTier }?.error
    val downloadedBytes = downloadState?.takeIf { it.tier == selectedTier }?.downloadedBytes ?: 0L
    val totalBytes = downloadState?.takeIf { it.tier == selectedTier }?.totalBytes ?: -1L

    var localModelPath by remember(modelPath) { mutableStateOf(modelPath) }
    val isDownloaded = localModelPath.isNotBlank() && WhisperModelDownloader.isDownloaded(selectedTier) &&
        WhisperModelDownloader.modelPath(selectedTier).toString() == localModelPath

    // Syncs localModelPath off downloadState (a StateFlow, always latest) rather than the
    // one-shot WhisperModelDownloadFileReadyEvent, whose replay=0 could miss a completion emitted
    // while this screen wasn't mounted. Detects success as an active→null transition (failure
    // instead leaves state non-null with .error set) and re-reads the fresh path from AppConfig.
    var lastActiveTier by remember { mutableStateOf<WhisperModelCatalog?>(null) }
    LaunchedEffect(downloadState) {
        val state = downloadState
        when {
            state?.isActive == true -> lastActiveTier = state.tier

            state == null && lastActiveTier != null -> {
                val freshPath = AppConfig.rawVoice.localWhisperModelPath
                localModelPath = freshPath
                onModelPathChange(freshPath)
                lastActiveTier = null
            }

            else -> lastActiveTier = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(text = stringResource("settings.voice.whisper_model.title"), style = AppTextStyles.fieldLabel)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            WhisperModelCatalog.entries.forEach { tier ->
                val isSelected = tier == selectedTier
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickableCard {
                            if (!downloading) {
                                selectedTier = tier
                                WhisperModelDownloadManager.clearError()
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.small),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = stringResource(tier.labelKey), style = AppTextStyles.body)
                        Text(
                            text = "${tier.approxSizeBytes / (1024 * 1024)} MB",
                            style = AppTextStyles.caption,
                            color = AppTextStyles.secondaryContent,
                        )
                    }
                }
            }
        }

        when {
            downloading -> {
                val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                val percent = (progress * 100).toInt()
                val downloadedMb = downloadedBytes / (1024 * 1024)
                val totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else selectedTier.approxSizeBytes / (1024 * 1024)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = LocalizationManager.getString(
                            "settings.voice.whisper_model.downloading",
                            percent,
                            downloadedMb,
                            totalMb,
                        ),
                        style = AppTextStyles.caption,
                    )
                    // Lets the user abort an in-progress download — the downloader already
                    // deletes the partial `.part` file on cancellation.
                    OutlinedButton(
                        onClick = { WhisperModelDownloadManager.cancelDownload() },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(stringResource("action.cancel"), style = AppTextStyles.caption)
                    }
                }
            }

            isDownloaded -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.voice.whisper_model.downloaded"),
                    style = AppTextStyles.caption,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = {
                        WhisperModelDownloader.delete(selectedTier)
                        localModelPath = ""
                        onModelPathChange("")
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource("settings.voice.whisper_model.delete"),
                        modifier = Modifier.padding(start = Spacing.extraSmall),
                    )
                }
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                Text(
                    text = stringResource("settings.voice.whisper_model.not_downloaded"),
                    style = AppTextStyles.caption,
                    color = AppTextStyles.secondaryContent,
                )
                Button(
                    onClick = {
                        WhisperModelDownloadManager.startDownload(
                            tier = selectedTier,
                            baseUrl = AppConfig.rawVoice.whisperModelBaseUrl,
                        )
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(stringResource("settings.voice.whisper_model.download"))
                }
                downloadError?.let { error ->
                    Text(
                        text = LocalizationManager.getString("settings.voice.whisper_model.download_failed", error),
                        style = AppTextStyles.caption,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
