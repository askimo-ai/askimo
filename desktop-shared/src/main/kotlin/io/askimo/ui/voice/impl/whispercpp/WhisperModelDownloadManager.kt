/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl.whispercpp

import io.askimo.core.config.AppConfig
import io.askimo.core.config.VoiceConfig
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.WhisperModelDownloadFileReadyEvent
import io.askimo.core.event.user.WhisperModelDownloadCompletedEvent
import io.askimo.core.event.user.WhisperModelDownloadFailedEvent
import io.askimo.core.event.user.WhisperModelDownloadStartedEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files

/**
 * App-scoped (not Composable-scoped) owner of [WhisperModelDownloader] state, so a download
 * started from Settings > Voice survives navigating away and back — fixes the prior
 * `rememberCoroutineScope()`-tied download getting cancelled when the view left composition.
 *
 * Only one download runs at a time ([startDownload] is a no-op if [state] is already non-null).
 * On success, the previously active tier's file is silently deleted (see
 * [deleteStalePreviousModel]) since only one model is ever in use. Start/completion/failure are
 * posted to [EventBus] so the notification bell reflects the outcome from any screen — live byte
 * progress stays out of the bus (to avoid flooding it) and is only exposed via [state].
 */
object WhisperModelDownloadManager {
    private val log = logger<WhisperModelDownloadManager>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param tier Tier currently downloading (or that failed last).
     * @param downloadedBytes Bytes downloaded so far.
     * @param totalBytes Server-reported total size, or `-1` if unknown.
     * @param error Non-null once failed; cleared by [startDownload] or [clearError].
     */
    data class DownloadState(
        val tier: WhisperModelCatalog,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val error: String? = null,
    ) {
        val isActive: Boolean get() = error == null
    }

    private val _state = MutableStateFlow<DownloadState?>(null)

    /** `null` when no download is running and no error is pending acknowledgement. */
    val state: StateFlow<DownloadState?> = _state.asStateFlow()

    /**
     * Starts downloading [tier] from [baseUrl], unless one is already in progress (no-op).
     * On success, persists the path directly to [AppConfig] regardless of whether Settings
     * is still showing.
     */
    fun startDownload(tier: WhisperModelCatalog, baseUrl: String = VoiceConfig().whisperModelBaseUrl) {
        val current = _state.value
        if (current != null && current.isActive) {
            log.debug("Ignoring download request for {} — {} is already downloading", tier, current.tier)
            return
        }

        val tierLabel = LocalizationManager.getString(tier.labelKey)
        // Captured before this download overwrites it, for deleteStalePreviousModel below.
        val previousModelPath = AppConfig.rawVoice.localWhisperModelPath
        _state.value = DownloadState(tier)

        scope.launch {
            EventBus.emit(WhisperModelDownloadStartedEvent(tier.name, tierLabel))
            try {
                val path = WhisperModelDownloader.download(tier, baseUrl) { done, total ->
                    _state.value = _state.value?.copy(downloadedBytes = done, totalBytes = total)
                }
                AppConfig.updateField("voice.localWhisperModelPath", path.toString())
                deleteStalePreviousModel(previousModelPath, path.toString())
                _state.value = null
                EventBus.emit(WhisperModelDownloadFileReadyEvent(tier.name, path.toString()))
                EventBus.emit(WhisperModelDownloadCompletedEvent(tier.name, tierLabel))
            } catch (e: Exception) {
                log.warn("Whisper model download failed for {}", tier, e)
                _state.value = _state.value?.copy(error = e.message ?: "Unknown error")
                EventBus.emit(WhisperModelDownloadFailedEvent(tier.name, tierLabel, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Deletes [previousModelPath] after a successful download — only one model is ever active
     * at a time, so a leftover tier file (up to ~3GB for [WhisperModelCatalog.BEST]) just wastes
     * disk space. Only deletes a recognized [WhisperModelCatalog] path (never a user-pointed
     * one), and skips if it's the same file just downloaded (e.g. re-download after corruption).
     * Failure is logged but never fails the download itself.
     */
    private fun deleteStalePreviousModel(previousModelPath: String, newModelPath: String) {
        if (previousModelPath.isBlank() || previousModelPath == newModelPath) return
        val previousTier = WhisperModelCatalog.entries.find {
            WhisperModelDownloader.modelPath(it).toString() == previousModelPath
        } ?: return // Not a recognized catalog file — leave it alone.

        try {
            if (Files.deleteIfExists(WhisperModelDownloader.modelPath(previousTier))) {
                log.info("Deleted previous Whisper model '{}' after switching to a new tier", previousTier)
            }
        } catch (e: Exception) {
            log.warn("Failed to delete previous Whisper model '{}' after switching tiers", previousTier, e)
        }
    }

    /** Dismisses a failed download's error state (e.g. when the user picks a different tier). */
    fun clearError() {
        if (_state.value?.error != null) _state.value = null
    }
}
