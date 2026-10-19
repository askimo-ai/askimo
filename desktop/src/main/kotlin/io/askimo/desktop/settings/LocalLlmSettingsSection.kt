/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.providers.llama.HuggingFaceGgufBrowser
import io.askimo.core.providers.llama.LlamaDownloadManager
import io.askimo.core.providers.llama.LlamaModelCatalog
import io.askimo.core.providers.llama.LlamaModelDownloader
import io.askimo.core.providers.llama.LlamaModelEntry
import io.askimo.core.providers.llama.LlamaServerCatalog
import io.askimo.core.providers.llama.LlamaServerDownloader
import io.askimo.core.providers.llama.LlamaServerPlatform
import io.askimo.core.providers.llama.LlamaServerProcessManager
import io.askimo.core.util.AskimoHome
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.util.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings page for Askimo's built-in local LLM — downloading the `llama-server` binary and
 * `.gguf` model weights needed to use the "Local LLM (built-in)"
 * `io.askimo.core.providers.openaicompatible.OpenAiCompatibleTemplate.ASKIMO_LOCAL_LLM` provider
 * without installing Ollama/LM Studio.
 *
 * This page only manages *downloads* (binary + models) — once a model is downloaded, it's
 * selected as the chat/utility/embedding model for a "Local LLM (built-in)" provider instance the
 * normal way, from Settings > AI Provider > Add Provider, exactly like any other provider's model
 * picker (the field just holds the `.gguf` file name instead of a cloud model id).
 *
 * All downloads are explicit (user clicks "Download") — never triggered lazily on first use,
 * same rule already applied to the Whisper STT model in [voiceSettingsSection].
 */
@Composable
fun localLlmSettingsSection() {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = Spacing.extraLarge, top = Spacing.extraLarge, bottom = Spacing.extraLarge, end = Spacing.scrollbarGutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("settings.local_llm"),
                    style = AppTextStyles.pageTitle,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )
                Text(
                    text = stringResource("settings.local_llm.description"),
                    style = AppTextStyles.bodySecondary,
                    color = AppTextStyles.secondaryContent,
                )

                localLlmBinaryCard()
                localLlmModelsCard()
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

/**
 * Enables/disables the platform-matching `llama-server` binary (see [LlamaServerCatalog]) via a
 * single [Switch] — "enable engine, setup happens automatically", like LM Studio/Ollama/Docker
 * Model Runner. Checking downloads the binary if missing; unchecking stops the server (if
 * running) and deletes the binary.
 */
@Composable
private fun localLlmBinaryCard() {
    val scope = rememberCoroutineScope()
    val platform: LlamaServerPlatform? = remember { LlamaServerCatalog.detectCurrentPlatform() }

    val binaryDownloadState by LlamaDownloadManager.binaryDownload.collectAsState()
    val downloading = binaryDownloadState != null && binaryDownloadState?.error == null
    var isDownloaded by remember {
        mutableStateOf(platform != null && LlamaServerDownloader.isDownloaded(platform))
    }
    var installedVersion by remember {
        mutableStateOf(platform?.let { LlamaServerDownloader.installedVersion(it) })
    }

    // Backfills the version marker for pre-tracking installs (see
    // LlamaServerDownloader.installedVersion). An already-downloaded binary starts the Switch
    // "on" with no other trigger to detect its version. No network call; no-op on Windows.
    LaunchedEffect(platform, isDownloaded) {
        if (platform != null && isDownloaded && installedVersion == null) {
            LlamaServerDownloader.backfillVersionMarkerIfProvable(platform)
            installedVersion = LlamaServerDownloader.installedVersion(platform)
        }
    }

    // Refreshes isDownloaded/installedVersion once a download finishes, since downloads run
    // app-scoped rather than tied to this Composable's lifecycle (see LlamaDownloadManager).
    LaunchedEffect(binaryDownloadState, platform) {
        if (platform != null && binaryDownloadState == null) {
            val nowDownloaded = LlamaServerDownloader.isDownloaded(platform)
            if (nowDownloaded != isDownloaded) {
                isDownloaded = nowDownloaded
                installedVersion = if (nowDownloaded) LlamaServerDownloader.installedVersion(platform) else null
            }
        }
    }

    // Starts the reverse-proxy as soon as the engine is enabled — no model needed, just makes the
    // provider connectable via GET /models (see startProxy). Also best-effort warms up the last
    // session's resident model in the background; model loading itself stays automatic/lazy via
    // real requests (see LlamaServerProcessManager.ensureModelLoaded). No-op if that model is no
    // longer downloaded.
    LaunchedEffect(platform, isDownloaded) {
        if (platform != null && isDownloaded) {
            LlamaServerProcessManager.startProxy()

            val lastFileName = AppConfig.localLlm.lastSelectedModelFileName
            if (lastFileName != null && LlamaServerProcessManager.status.value.modelFileName != lastFileName) {
                val entry = LlamaModelCatalog.fromFileName(lastFileName)
                    ?: LlamaModelEntry(
                        fileName = lastFileName,
                        labelKey = "",
                        approxSizeBytes = 0,
                        downloadUrl = "",
                        isEmbedding = AppConfig.localLlm.lastSelectedModelIsEmbedding,
                    )
                if (LlamaModelDownloader.isDownloaded(entry)) {
                    runCatching { LlamaServerProcessManager.loadModel(entry) }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = Spacing.large)) {
                    Text(text = stringResource("settings.local_llm.binary.title"), style = AppTextStyles.sectionTitle)
                    Text(
                        text = stringResource("settings.local_llm.binary.description"),
                        style = AppTextStyles.caption,
                        color = AppTextStyles.secondaryContent,
                    )
                }

                if (platform != null) {
                    Switch(
                        checked = isDownloaded || downloading,
                        enabled = !downloading,
                        onCheckedChange = { checked ->
                            if (checked) {
                                LlamaDownloadManager.startBinaryDownload(platform)
                            } else {
                                // Stop the proxy/process and *wait* before deleting — deleting
                                // fire-and-forget races shutdown (on Windows especially, the
                                // executable stays locked until the process fully exits).
                                scope.launch {
                                    LlamaServerProcessManager.stopProxy()
                                    LlamaServerDownloader.delete(platform)
                                    isDownloaded = false
                                    installedVersion = null
                                }
                            }
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }

            if (platform == null) {
                Text(
                    text = stringResource("settings.local_llm.binary.unsupported_platform"),
                    style = AppTextStyles.caption,
                    color = AppColors.warningColor(),
                )
                return@Column
            }

            HorizontalDivider()

            if (isDownloaded) {
                val proxyStatus by LlamaServerProcessManager.proxyStatus.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = if (proxyStatus.running) {
                                LocalizationManager.getString(
                                    "settings.local_llm.server.running",
                                    proxyStatus.port?.toString() ?: "-",
                                )
                            } else {
                                stringResource("settings.local_llm.server.stopped")
                            },
                            style = AppTextStyles.caption,
                            color = if (proxyStatus.running) {
                                AppColors.statusIconColor(AppColors.StatusTone.SUCCESS)
                            } else {
                                AppTextStyles.secondaryContent
                            },
                        )
                        installedVersion?.let { version ->
                            Text(
                                text = LocalizationManager.getString("settings.local_llm.binary.version", version),
                                style = AppTextStyles.caption,
                                color = AppTextStyles.secondaryContent,
                            )
                        }
                    }
                    // Explicit delete, separate from the Switch above — lets users force a clean
                    // reinstall without the Switch's combined "disable AND delete" semantics.
                    OutlinedButton(
                        enabled = !downloading,
                        onClick = {
                            scope.launch {
                                LlamaServerProcessManager.stopProxy()
                                LlamaServerDownloader.delete(platform)
                                isDownloaded = false
                                installedVersion = null
                            }
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource("settings.local_llm.delete"),
                            modifier = Modifier.padding(start = Spacing.extraSmall),
                        )
                    }
                }
            }

            if (isDownloaded) {
                localLlmCtxSizeField()
                HorizontalDivider()
                AppComponents.settingsIntField(
                    label = stringResource("settings.local_llm.batch_size.label"),
                    hint = stringResource("settings.local_llm.batch_size.hint"),
                    value = AppConfig.localLlm.batchSize,
                    minValue = 1,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.batchSize", newValue) },
                )
                AppComponents.settingsIntField(
                    label = stringResource("settings.local_llm.ubatch_size.label"),
                    hint = stringResource("settings.local_llm.ubatch_size.hint"),
                    value = AppConfig.localLlm.ubatchSize,
                    minValue = 1,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.ubatchSize", newValue) },
                )
                localLlmCacheTypeField(
                    label = stringResource("settings.local_llm.cache_type_k.label"),
                    hint = stringResource("settings.local_llm.cache_type_k.hint"),
                    value = AppConfig.localLlm.cacheTypeK,
                    configField = "localLlm.cacheTypeK",
                )
                localLlmCacheTypeField(
                    label = stringResource("settings.local_llm.cache_type_v.label"),
                    hint = stringResource("settings.local_llm.cache_type_v.hint"),
                    value = AppConfig.localLlm.cacheTypeV,
                    configField = "localLlm.cacheTypeV",
                )
                AppComponents.settingsIntField(
                    label = stringResource("settings.local_llm.n_gpu_layers.label"),
                    hint = stringResource("settings.local_llm.n_gpu_layers.hint"),
                    value = AppConfig.localLlm.nGpuLayers,
                    minValue = 0,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.nGpuLayers", newValue) },
                )
                HorizontalDivider()
                Text(text = stringResource("settings.local_llm.advanced.title"), style = AppTextStyles.fieldLabel)
                AppComponents.settingsBooleanField(
                    label = stringResource("settings.local_llm.flash_attn.label"),
                    hint = stringResource("settings.local_llm.flash_attn.hint"),
                    value = AppConfig.localLlm.flashAttn,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { checked -> AppConfig.updateField("localLlm.flashAttn", checked) },
                )
                AppComponents.settingsBooleanField(
                    label = stringResource("settings.local_llm.no_mmap.label"),
                    hint = stringResource("settings.local_llm.no_mmap.hint"),
                    value = AppConfig.localLlm.noMmap,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { checked -> AppConfig.updateField("localLlm.noMmap", checked) },
                )
                AppComponents.settingsBooleanField(
                    label = stringResource("settings.local_llm.mlock.label"),
                    hint = stringResource("settings.local_llm.mlock.hint"),
                    value = AppConfig.localLlm.mlock,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { checked -> AppConfig.updateField("localLlm.mlock", checked) },
                )
                AppComponents.settingsIntField(
                    label = stringResource("settings.local_llm.num_parallel.label"),
                    hint = stringResource("settings.local_llm.num_parallel.hint"),
                    value = AppConfig.localLlm.numParallel,
                    minValue = 1,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.numParallel", newValue) },
                )
                AppComponents.settingsBooleanField(
                    label = stringResource("settings.local_llm.cont_batching.label"),
                    hint = stringResource("settings.local_llm.cont_batching.hint"),
                    value = AppConfig.localLlm.contBatching,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { checked -> AppConfig.updateField("localLlm.contBatching", checked) },
                )
                AppComponents.settingsBooleanField(
                    label = stringResource("settings.local_llm.no_warmup.label"),
                    hint = stringResource("settings.local_llm.no_warmup.hint"),
                    value = AppConfig.localLlm.noWarmup,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { checked -> AppConfig.updateField("localLlm.noWarmup", checked) },
                )
                AppComponents.settingsDropdownField(
                    label = stringResource("settings.local_llm.numa_mode.label"),
                    hint = stringResource("settings.local_llm.numa_mode.hint"),
                    value = AppConfig.localLlm.numaMode,
                    options = NUMA_MODES,
                    displayName = { it.ifBlank { stringResource("settings.local_llm.numa_mode.disabled") } },
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.numaMode", newValue) },
                )
                AppComponents.settingsIntField(
                    label = stringResource("settings.local_llm.n_predict.label"),
                    hint = stringResource("settings.local_llm.n_predict.hint"),
                    value = AppConfig.localLlm.nPredict,
                    minValue = -1,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.nPredict", newValue) },
                )
                AppComponents.settingsDoubleField(
                    label = stringResource("settings.local_llm.rope_freq_base.label"),
                    hint = stringResource("settings.local_llm.rope_freq_base.hint"),
                    value = AppConfig.localLlm.ropeFreqBase,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.ropeFreqBase", newValue) },
                )
                AppComponents.settingsDoubleField(
                    label = stringResource("settings.local_llm.rope_freq_scale.label"),
                    hint = stringResource("settings.local_llm.rope_freq_scale.hint"),
                    value = AppConfig.localLlm.ropeFreqScale,
                    labelStyle = AppTextStyles.fieldLabel,
                    hintStyle = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    onValueChange = { newValue -> AppConfig.updateField("localLlm.ropeFreqScale", newValue) },
                )
            }

            if (downloading) {
                downloadProgress(
                    binaryDownloadState?.downloadedBytes ?: 0L,
                    binaryDownloadState?.totalBytes ?: -1L,
                    onCancel = { LlamaDownloadManager.cancelBinaryDownload() },
                )
            } else if (!isDownloaded) {
                Text(
                    text = stringResource("settings.local_llm.binary.not_downloaded"),
                    style = AppTextStyles.caption,
                    color = AppTextStyles.secondaryContent,
                )
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
}

/**
 * Context-window size (`--ctx-size`) field — see [io.askimo.core.config.LocalLlmConfig.ctxSize]
 * for why it's bounded. Takes effect on the next (re)started `llama-server` process, not an
 * already-running one. Rejects (rather than silently clamps) values below the 512 minimum so
 * what's shown as "saved" matches what's actually persisted.
 */
@Composable
private fun localLlmCtxSizeField() {
    AppComponents.settingsIntField(
        label = stringResource("settings.local_llm.ctx_size.label"),
        hint = stringResource("settings.local_llm.ctx_size.hint"),
        value = AppConfig.localLlm.ctxSize,
        minValue = 512,
        onValueChange = { newValue -> AppConfig.updateField("localLlm.ctxSize", newValue) },
    )
}

/** KV-cache quantization types accepted by llama-server's `--cache-type-k`/`--cache-type-v`. */
private val KV_CACHE_TYPES = listOf("f16", "q8_0", "q4_0", "q4_1", "q5_0", "q5_1", "iq4_nl")

/** NUMA strategies accepted by llama-server's `--numa <mode>`; `""` means the flag isn't passed. */
private val NUMA_MODES = listOf("", "distribute", "isolate", "numactl")

/**
 * Dropdown field for `--cache-type-k`/`--cache-type-v` — see
 * [io.askimo.core.config.LocalLlmConfig.cacheTypeK]'s doc for why quantizing these shrinks KV
 * cache memory, and why `--flash-attn` is auto-applied whenever either is non-`f16`.
 */
@Composable
private fun localLlmCacheTypeField(
    label: String,
    hint: String,
    value: String,
    configField: String,
) {
    AppComponents.settingsDropdownField(
        label = label,
        hint = hint,
        value = value,
        options = KV_CACHE_TYPES,
        onValueChange = { newValue -> AppConfig.updateField(configField, newValue) },
    )
}

/**
 * Download-management card for every `.gguf` model — curated embedding entries
 * ([LlamaModelCatalog.allEntries]) plus live Hugging Face results ([HuggingFaceGgufBrowser]).
 * Chat models default to HF's most-downloaded GGUF repos ([HuggingFaceGgufBrowser.popularModels]),
 * narrowed by search — see [LlamaModelCatalog] for why only embedding models stay curated.
 *
 * Layout: downloaded models first (stay manageable even once off the live results), then search,
 * then browsable catalog/search results.
 *
 * Not split into "chat" vs "embedding" sections: a model's role is decided by which field
 * (`defaultModel`/`utilityModel`/`embeddingModel`) it's assigned to in Settings > AI Provider.
 * [LlamaModelEntry.isEmbedding]/[LlamaModelEntry.poolingType] are just process-launch metadata
 * (`--pooling`, child-process slot — see [LlamaServerProcessManager]), shown as a small badge.
 */
@Composable
private fun localLlmModelsCard() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    // Off by default: HuggingFaceGgufBrowser.search's restrictToChatModels filters to Hugging
    // Face's pipeline_tag=="text-generation"/"conversational" repos, hiding non-chat GGUF repos
    // (e.g. embedding/vision/detection models) that otherwise look identical from the file
    // listing alone. Letting the user flip it on covers repos HF tagged wrong or didn't tag.
    var showAllModels by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<List<LlamaModelEntry>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var downloadedEntries by remember { mutableStateOf<List<LlamaModelEntry>>(emptyList()) }
    var diskScanTrigger by remember { mutableStateOf(0) }
    // Guards against the auto-triggered blank-query popular-models request racing a
    // user-triggered search — both write searchResults/isSearching independently, so without
    // cancelling the previous job, whichever completes last wins even if it's stale.
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun runSearch() {
        searchJob?.cancel()
        isSearching = true
        searchError = null
        val job: Job = scope.launch {
            try {
                // Blank query = Hugging Face's most-downloaded GGUF repos overall (see
                // popularModels) — also what's auto-loaded on first composition below.
                val restrictToChatModels = !showAllModels
                val results = if (query.isBlank()) {
                    HuggingFaceGgufBrowser.popularModels(restrictToChatModels = restrictToChatModels)
                } else {
                    HuggingFaceGgufBrowser.search(query.trim(), restrictToChatModels = restrictToChatModels)
                }
                searchResults = results
                hasSearched = true
            } catch (e: CancellationException) {
                // Superseded by a newer search — new job owns the state now.
                throw e
            } catch (e: Exception) {
                searchError = e.message
            } finally {
                // Only the current job may clear isSearching, else a cancelled job's finally
                // could flip it back to false while the superseding job is still loading.
                if (searchJob === coroutineContext[Job]) isSearching = false
            }
        }
        searchJob = job
    }

    // Loads the default "Popular on Hugging Face" list on open — chat models aren't curated
    // (see LlamaModelCatalog), so otherwise only the curated embedding entries would show.
    // Re-keyed on showAllModels so flipping the toggle re-runs the current query/popular list
    // immediately, without needing a manual re-search.
    LaunchedEffect(showAllModels) { runSearch() }

    // Rescans disk for every downloaded model (see listDownloadedEntries) — keeps a model
    // manageable even after it's no longer in the current search/popular results. Keyed by the
    // file-name *set*, not the map itself: downloadedBytes changes every buffer, which would
    // otherwise re-trigger this (and its disk scan) constantly during a download.
    val modelDownloads by LlamaDownloadManager.modelDownloads.collectAsState()
    val downloadKeys = remember(modelDownloads) { modelDownloads.keys.toSet() }
    LaunchedEffect(downloadKeys, diskScanTrigger) {
        downloadedEntries = withContext(Dispatchers.IO) { LlamaModelDownloader.listDownloadedEntries() }
    }

    // On disk, regardless of search/popular results — shown above the search box.
    val downloadedFileNames = remember(downloadedEntries) { downloadedEntries.map { it.fileName }.toSet() }

    // Entries currently downloading, from LlamaDownloadManager's own state. Without this, an
    // in-progress download not in a fresh search result would vanish from the page — its file
    // also isn't on disk yet, so downloadedEntries can't cover this gap either.
    val activeDownloadEntries = remember(modelDownloads) { modelDownloads.values.map { it.entry } }

    // Curated + live results + anything downloading, minus anything already downloaded (shown
    // above instead) so a model never appears twice with conflicting Download/Delete controls.
    val browseEntries = (LlamaModelCatalog.allEntries + searchResults + activeDownloadEntries)
        .distinctBy { it.fileName }
        .filter { it.fileName !in downloadedFileNames }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = Spacing.large)) {
                    Text(text = stringResource("settings.local_llm.models.title"), style = AppTextStyles.sectionTitle)
                    Text(
                        text = stringResource("settings.local_llm.models.description"),
                        style = AppTextStyles.caption,
                        color = AppTextStyles.secondaryContent,
                    )
                }
                OutlinedButton(
                    onClick = { Platform.openInFileManager(AskimoHome.llamaModelsDir().toFile()) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource("settings.local_llm.show_folder"),
                        modifier = Modifier.padding(start = Spacing.extraSmall),
                    )
                }
            }

            // Downloaded models always come first, regardless of search state.
            if (downloadedEntries.isNotEmpty()) {
                Text(
                    text = stringResource("settings.local_llm.models.downloaded_title"),
                    style = AppTextStyles.fieldLabel,
                )
                downloadedEntries.forEach { entry ->
                    HorizontalDivider()
                    localLlmModelRow(entry, onModelDeleted = { diskScanTrigger++ })
                }
                HorizontalDivider()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource("settings.local_llm.hf_browser.search_placeholder")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                )
                Button(
                    onClick = { runSearch() },
                    enabled = !isSearching,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(stringResource("settings.local_llm.hf_browser.search"))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = showAllModels,
                    onCheckedChange = { showAllModels = it },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                Text(
                    text = stringResource("settings.local_llm.hf_browser.show_all"),
                    style = AppTextStyles.caption,
                    color = AppTextStyles.secondaryContent,
                    modifier = Modifier
                        .clickable { showAllModels = !showAllModels }
                        .pointerHoverIcon(PointerIcon.Hand),
                )
            }
            if (isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            searchError?.let { error ->
                Text(
                    text = LocalizationManager.getString("settings.local_llm.hf_browser.search_failed", error),
                    style = AppTextStyles.caption,
                    color = AppColors.warningColor(),
                )
            }
            if (hasSearched && searchResults.isEmpty() && !isSearching && searchError == null) {
                Text(
                    text = stringResource("settings.local_llm.hf_browser.no_results"),
                    style = AppTextStyles.caption,
                    color = AppTextStyles.secondaryContent,
                )
            }

            browseEntries.forEach { entry ->
                HorizontalDivider()
                localLlmModelRow(entry, onModelDeleted = { diskScanTrigger++ })
            }
        }
    }
}

/**
 * Formats a raw count (e.g. Hugging Face downloads/likes) as a compact human-readable string,
 * e.g. `1_234_567 -> "1.2M"`, `8_400 -> "8.4K"`. Values under 1000 are shown as-is.
 */
private fun formatMetricCount(count: Long): String = when {
    count >= 1_000_000 -> "${(count / 100_000 / 10.0).let { "%.1f".format(it) }}M"
    count >= 1_000 -> "${(count / 100 / 10.0).let { "%.1f".format(it) }}K"
    else -> count.toString()
}

/** Parses a Hugging Face API ISO-8601 timestamp (e.g. `lastModified`) into a short local date, e.g. `2026-10-12`. */
private fun formatHfTimestamp(isoTimestamp: String): String = try {
    DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(isoTimestamp))
} catch (_: Exception) {
    isoTimestamp.take(10)
}

@Composable
private fun localLlmModelRow(entry: LlamaModelEntry, onModelDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val downloadsByFileName by LlamaDownloadManager.modelDownloads.collectAsState()
    val downloadState = downloadsByFileName[entry.fileName]
    val downloading = downloadState != null && downloadState.error == null
    var isDownloaded by remember(entry.fileName) { mutableStateOf(LlamaModelDownloader.isDownloaded(entry)) }
    var isDeleting by remember(entry.fileName) { mutableStateOf(false) }
    var deleteError by remember(entry.fileName) { mutableStateOf<String?>(null) }

    // Refreshes isDownloaded once this entry's download (from here or a previous visit) finishes,
    // since downloads run app-scoped rather than tied to this row's lifecycle.
    LaunchedEffect(downloadState, entry.fileName) {
        if (downloadState == null) {
            val nowDownloaded = LlamaModelDownloader.isDownloaded(entry)
            if (nowDownloaded != isDownloaded) isDownloaded = nowDownloaded
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    val title = entry.label ?: stringResource(entry.labelKey).ifBlank { entry.fileName }
                    Text(
                        text = title,
                        style = AppTextStyles.fieldLabel,
                        color = if (entry.sourceRepoId != null) MaterialTheme.colorScheme.primary else AppTextStyles.primaryContent,
                        textDecoration = if (entry.sourceRepoId != null) TextDecoration.Underline else null,
                        modifier = entry.sourceRepoId?.let { repoId ->
                            Modifier
                                .clickable {
                                    runCatching { Desktop.getDesktop().browse(URI("https://huggingface.co/$repoId")) }
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                        } ?: Modifier,
                    )
                    if (entry.isEmbedding) {
                        Text(
                            text = stringResource("settings.local_llm.models.embedding_badge"),
                            style = AppTextStyles.caption,
                            color = AppTextStyles.secondaryContent,
                            modifier = Modifier
                                .background(AppColors.surfaceColor(AppColors.Elevation.RECESSED), shape = MaterialTheme.shapes.small)
                                .padding(horizontal = Spacing.extraSmall, vertical = 2.dp),
                        )
                    }
                }
                // Raw .gguf file name is just an on-disk implementation detail, not useful to a
                // user deciding whether to download — the repo link above (when present) is what's
                // actually actionable, so it's left out of this caption entirely. pipelineTag
                // (e.g. "text-generation") is shown instead when Hugging Face reported one.
                val caption = buildString {
                    if (entry.approxSizeBytes > 0) append("${entry.approxSizeBytes / (1024 * 1024)} MB")
                    entry.pipelineTag?.let {
                        if (isNotEmpty()) append("  ·  ")
                        append(it)
                    }
                }
                if (caption.isNotEmpty()) {
                    Text(
                        text = caption,
                        style = AppTextStyles.caption,
                        color = AppTextStyles.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Hugging Face popularity/freshness metadata — null (and omitted) for curated
                // catalog entries.
                val hfMeta = buildString {
                    entry.downloads?.let { append("⬇ ${formatMetricCount(it)}") }
                    entry.likes?.let {
                        if (isNotEmpty()) append("  ·  ")
                        append("♥ ${formatMetricCount(it)}")
                    }
                    entry.lastModified?.let {
                        if (isNotEmpty()) append("  ·  ")
                        append(LocalizationManager.getString("settings.local_llm.models.updated_on", formatHfTimestamp(it)))
                    }
                }
                if (hfMeta.isNotEmpty()) {
                    Text(
                        text = hfMeta,
                        style = AppTextStyles.caption,
                        color = AppTextStyles.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when {
                downloading -> {}

                // Which model is actually used is picked in Settings > AI Provider's picker;
                // loading into memory is automatic/lazy on first request (see
                // LlamaServerProcessManager.ensureModelLoaded) — this row only manages disk state.
                isDownloaded -> OutlinedButton(
                    enabled = !isDeleting,
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            deleteError = null
                            try {
                                LlamaServerProcessManager.deleteModel(entry)
                                isDownloaded = false
                                onModelDeleted()
                            } catch (e: Exception) {
                                deleteError = e.message ?: "Failed to delete model."
                            } finally {
                                isDeleting = false
                            }
                        }
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource("settings.local_llm.delete"),
                        modifier = Modifier.padding(start = Spacing.extraSmall),
                    )
                }

                else -> Button(
                    onClick = { LlamaDownloadManager.startModelDownload(entry) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(stringResource("settings.local_llm.binary.download"))
                }
            }
        }

        if (downloading) {
            downloadProgress(
                downloadState.downloadedBytes,
                downloadState.totalBytes,
                onCancel = { LlamaDownloadManager.cancelModelDownload(entry.fileName) },
            )
        }
        downloadState?.error?.let { error ->
            Text(
                text = LocalizationManager.getString("settings.local_llm.download_failed", error),
                style = AppTextStyles.caption,
                color = AppColors.warningColor(),
            )
        }
        deleteError?.let { error ->
            Text(
                text = error,
                style = AppTextStyles.caption,
                color = AppColors.warningColor(),
            )
        }
    }
}

@Composable
private fun downloadProgress(downloadedBytes: Long, totalBytes: Long, onCancel: (() -> Unit)? = null) {
    val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
    )
    val percent = (progress * 100).toInt()
    val downloadedMb = downloadedBytes / (1024 * 1024)
    val totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0L
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = LocalizationManager.getString("settings.local_llm.downloading", percent, downloadedMb, totalMb),
            style = AppTextStyles.caption,
        )
        // Lets the user abort an in-progress download — StreamingDownloader already deletes the
        // partial `.part` file on cancellation, so this never leaves stray files on disk.
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Text(stringResource("action.cancel"), style = AppTextStyles.caption)
            }
        }
    }
}
