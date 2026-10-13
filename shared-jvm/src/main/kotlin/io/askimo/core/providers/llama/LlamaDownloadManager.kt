/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.logging.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * App-scoped owner of `llama-server` binary + `.gguf` model download state, so downloads
 * survive navigation away/back (same fix as `WhisperModelDownloadManager`, whose
 * `rememberCoroutineScope()`-driven downloads got cancelled on leaving composition).
 *
 * Model downloads are keyed by [LlamaModelEntry.fileName] since multiple `.gguf` rows can
 * download concurrently; the binary uses a single slot (one binary per platform)
 */
object LlamaDownloadManager {
    private val log = logger<LlamaDownloadManager>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Binary ───────────────────────────────────────────────────────────────────────────────

    /**
     * @param downloadedBytes Bytes downloaded so far.
     * @param totalBytes Server-reported total size, or `-1` if unknown.
     * @param error Non-null once failed; cleared by [startBinaryDownload] or [clearBinaryError].
     */
    data class BinaryDownloadState(
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val error: String? = null,
    )

    private val _binaryDownload = MutableStateFlow<BinaryDownloadState?>(null)

    /** `null` when no binary download is running and no error is pending acknowledgement. */
    val binaryDownload: StateFlow<BinaryDownloadState?> = _binaryDownload.asStateFlow()

    /** Tracks the in-flight binary download [Job] so [cancelBinaryDownload] can cancel it. */
    private var binaryDownloadJob: Job? = null

    /** Starts downloading [platform]'s `llama-server` binary, unless one is already in progress (no-op). */
    fun startBinaryDownload(platform: LlamaServerPlatform) {
        val existing = _binaryDownload.value
        if (existing != null && existing.error == null) {
            log.debug("Ignoring llama-server binary download request — one is already in progress")
            return
        }
        _binaryDownload.value = BinaryDownloadState()
        binaryDownloadJob = scope.launch {
            try {
                LlamaServerDownloader.download(platform) { done, total ->
                    _binaryDownload.update { it?.copy(downloadedBytes = done, totalBytes = total) }
                }
                _binaryDownload.value = null
            } catch (e: CancellationException) {
                // User-initiated cancel (see cancelBinaryDownload) — StreamingDownloader.download
                // already deletes the partial file; just clear the tracked state (not an error).
                _binaryDownload.value = null
                throw e
            } catch (e: Exception) {
                log.warn("llama-server binary download failed", e)
                _binaryDownload.update { it?.copy(error = e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Cancels an in-progress binary download, if any (no-op otherwise). The underlying
     * [io.askimo.core.util.StreamingDownloader] already deletes the partial file on cancellation,
     * so this just needs to cancel the coroutine actually doing the downloading.
     */
    fun cancelBinaryDownload() {
        binaryDownloadJob?.cancel()
    }

    /** Dismisses a failed binary download's error state (e.g. before retrying). */
    fun clearBinaryError() {
        if (_binaryDownload.value?.error != null) _binaryDownload.value = null
    }

    // ── Models ───────────────────────────────────────────────────────────────────────────────

    /** Same shape as [BinaryDownloadState]; kept separate since these are tracked per-[LlamaModelEntry.fileName]. */
    data class ModelDownloadState(
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val error: String? = null,
    )

    private val _modelDownloads = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())

    /** Keyed by [LlamaModelEntry.fileName]; absent = not downloading and no pending error. */
    val modelDownloads: StateFlow<Map<String, ModelDownloadState>> = _modelDownloads.asStateFlow()

    /** Tracks each in-flight model download's [Job] (keyed by file name) so [cancelModelDownload] can cancel it. */
    private val modelDownloadJobs = ConcurrentHashMap<String, Job>()

    /** Starts downloading [entry], unless it's already downloading (no-op). */
    fun startModelDownload(entry: LlamaModelEntry) {
        val fileName = entry.fileName
        val existing = _modelDownloads.value[fileName]
        if (existing != null && existing.error == null) {
            log.debug("Ignoring download request for {} — already downloading", fileName)
            return
        }
        _modelDownloads.update { it + (fileName to ModelDownloadState()) }
        val job = scope.launch {
            try {
                LlamaModelDownloader.download(entry) { done, total ->
                    _modelDownloads.update { it + (fileName to ModelDownloadState(done, total)) }
                }
                _modelDownloads.update { it - fileName }
            } catch (e: CancellationException) {
                // User-initiated cancel (see cancelModelDownload) — StreamingDownloader.download
                // already deletes the partial file; just clear the tracked state (not an error).
                _modelDownloads.update { it - fileName }
                throw e
            } catch (e: Exception) {
                log.warn("Model download failed for {}", fileName, e)
                _modelDownloads.update { it + (fileName to ModelDownloadState(error = e.message ?: "Unknown error")) }
            }
        }
        modelDownloadJobs[fileName] = job
        job.invokeOnCompletion { modelDownloadJobs.remove(fileName, job) }
    }

    /**
     * Cancels [fileName]'s in-progress download, if any (no-op otherwise). The underlying
     * [io.askimo.core.util.StreamingDownloader] already deletes the partial `.part` file on
     * cancellation, so this just needs to cancel the coroutine actually doing the downloading.
     */
    fun cancelModelDownload(fileName: String) {
        modelDownloadJobs[fileName]?.cancel()
    }
}
