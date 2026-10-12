/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.Logger
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
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

private val log = Logger("LocalLlmSettingsSection")

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
 * single [Switch] — mirrors the "enable engine, setup happens automatically" mental model from
 * LM Studio/Ollama/Docker Model Runner, instead of a separate explicit "Download" button:
 * checking it downloads the binary if missing; unchecking it stops the server (if running) and
 * deletes the binary.
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

    // Backfills the version marker for installs from pre-tracking Askimo builds (see
    // LlamaServerDownloader.installedVersion's doc) — otherwise their version never appears here,
    // since an already-downloaded binary starts the Switch "on", giving the user no reason to
    // toggle it off/on to re-trigger download(). No network call: only writes the marker where
    // the install path itself proves the version (macOS/Linux); no-op on Windows, where it can't
    // be proven without a real download — which would violate this page's explicit-download rule.
    LaunchedEffect(platform, isDownloaded) {
        if (platform != null && isDownloaded && installedVersion == null) {
            LlamaServerDownloader.backfillVersionMarkerIfProvable(platform)
            installedVersion = LlamaServerDownloader.installedVersion(platform)
        }
    }

    // Refreshes isDownloaded/installedVersion once a download (started from here, or from a
    // previous visit to this page — see LlamaDownloadManager's doc) finishes, since the download
    // itself now runs app-scoped rather than tied to this Composable's lifecycle — fixes the
    // same bug class previously found with the Whisper STT model download.
    LaunchedEffect(binaryDownloadState, platform) {
        if (platform != null && binaryDownloadState == null) {
            val nowDownloaded = LlamaServerDownloader.isDownloaded(platform)
            if (nowDownloaded != isDownloaded) {
                isDownloaded = nowDownloaded
                installedVersion = if (nowDownloaded) LlamaServerDownloader.installedVersion(platform) else null
            }
        }
    }

    // Starts the stable reverse-proxy endpoint as soon as the engine is enabled — whether that's
    // because a download just finished, or because the binary was already downloaded from a
    // previous session and the user is revisiting this page. Unlike actual model loading, this
    // requires no model at all — it just makes the "Local LLM (built-in)" AI provider connectable
    // (listing downloaded models via GET /models) right away. See startProxy's doc.
    //
    // Also best-effort warms up whichever model was actually resident last session in the
    // background (see AppConfig.localLlm / LlamaServerProcessManager's persistence in
    // startSlotLocked) — purely an invisible latency optimization, not a user-facing control.
    // Model *loading* into memory is otherwise fully automatic/lazy, driven by real chat/
    // embedding requests through the proxy (see LlamaServerProcessManager.ensureModelLoaded), and
    // model *selection* happens in Settings > AI Provider's model picker for the Local LLM
    // instance — exactly like any other provider. This page only manages what's downloaded to
    // disk (see localLlmModelsCard/localLlmModelRow): download/delete, nothing more. Silently
    // no-ops below if the last-used model is no longer downloaded.
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
                                // Must stop the proxy/child process and *wait* for it before
                                // deleting the binary — launching stopProxy() fire-and-forget
                                // while deleting immediately races the shutdown: on Windows in
                                // particular, the executable/shared libraries stay locked while
                                // the process is still exiting, so the delete can fail silently
                                // even though the UI has already flipped to "disabled".
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
                    color = MaterialTheme.colorScheme.error,
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
                            color = if (proxyStatus.running) MaterialTheme.colorScheme.primary else AppTextStyles.secondaryContent,
                        )
                        installedVersion?.let { version ->
                            Text(
                                text = LocalizationManager.getString("settings.local_llm.binary.version", version),
                                style = AppTextStyles.caption,
                                color = AppTextStyles.secondaryContent,
                            )
                        }
                    }
                    // Explicit delete, independent of the enable/disable Switch above — lets
                    // users force a clean reinstall (e.g. ahead of a future upgrade flow)
                    // without having to reason about the Switch's combined "disable AND
                    // delete" semantics.
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
                    displayName = { if (it.isBlank()) stringResource("settings.local_llm.numa_mode.disabled") else it },
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
                downloadProgress(binaryDownloadState?.downloadedBytes ?: 0L, binaryDownloadState?.totalBytes ?: -1L)
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
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Context-window size (`--ctx-size`) field — see [io.askimo.core.config.LocalLlmConfig.ctxSize]
 * for why this is bounded rather than left at the model's native training context. Changes take
 * effect the next time a chat/embedding `llama-server` child process is (re)started, not on an
 * already-running one. [AppConfig.updateField] applies its own 512-minimum backstop clamp, but
 * the shared field below rejects (rather than silently clamps) anything below that minimum, so
 * what's shown as "saved" always matches what's actually persisted.
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
 * Unified download-management card for every `.gguf` model — curated catalog entries
 * ([LlamaModelCatalog.allEntries]) plus ad-hoc Hugging Face search results
 * ([HuggingFaceGgufBrowser]), each with its own download/delete control.
 *
 * Deliberately **not** split into "chat" vs. "embedding" sections: which role a downloaded
 * model actually plays is decided entirely by which field (`defaultModel`/`utilityModel`/
 * `embeddingModel`, etc.) the user assigns its filename to in Settings > AI Provider for the
 * "Local LLM (built-in)" instance — exactly like any other OpenAI-compatible provider, where
 * Askimo has no way to know in advance which of the user's model names are chat vs. embedding
 * models either. [LlamaModelEntry.isEmbedding]/[LlamaModelEntry.poolingType] remain purely
 * process-launch metadata (picking the right `--pooling` value and child-process slot — see
 * [LlamaServerProcessManager]), surfaced here only as a small informational badge per row.
 */
@Composable
private fun localLlmModelsCard() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<List<LlamaModelEntry>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }

    fun runSearch() {
        isSearching = true
        searchError = null
        scope.launch {
            try {
                searchResults = HuggingFaceGgufBrowser.search(query.trim())
                hasSearched = true
            } catch (e: Exception) {
                searchError = e.message
            } finally {
                isSearching = false
            }
        }
    }

    // Curated catalog entries always shown; search results append any not already in the
    // catalog (by fileName) so a search that happens to match a curated model doesn't duplicate
    // its row.
    val catalogFileNames = remember { LlamaModelCatalog.allEntries.map { it.fileName }.toSet() }
    val displayedEntries = LlamaModelCatalog.allEntries + searchResults.filter { it.fileName !in catalogFileNames }

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
                    onClick = { openInFileManager(AskimoHome.llamaModelsDir().toFile()) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource("settings.local_llm.show_folder"),
                        modifier = Modifier.padding(start = Spacing.extraSmall),
                    )
                }
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
            if (isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            searchError?.let { error ->
                Text(
                    text = LocalizationManager.getString("settings.local_llm.hf_browser.search_failed", error),
                    style = AppTextStyles.caption,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (hasSearched && searchResults.isEmpty() && !isSearching && searchError == null) {
                Text(
                    text = stringResource("settings.local_llm.hf_browser.no_results"),
                    style = AppTextStyles.caption,
                    color = AppTextStyles.secondaryContent,
                )
            }

            displayedEntries.forEach { entry ->
                HorizontalDivider()
                localLlmModelRow(entry)
            }
        }
    }
}

@Composable
private fun localLlmModelRow(entry: LlamaModelEntry) {
    val scope = rememberCoroutineScope()
    val downloadsByFileName by LlamaDownloadManager.modelDownloads.collectAsState()
    val downloadState = downloadsByFileName[entry.fileName]
    val downloading = downloadState != null && downloadState.error == null
    var isDownloaded by remember(entry.fileName) { mutableStateOf(LlamaModelDownloader.isDownloaded(entry)) }
    var isDeleting by remember(entry.fileName) { mutableStateOf(false) }
    var deleteError by remember(entry.fileName) { mutableStateOf<String?>(null) }

    // Refreshes isDownloaded once this entry's download (started from here, or from a previous
    // visit to this page — see LlamaDownloadManager's doc) finishes, since the download itself
    // now runs app-scoped rather than tied to this row's Composable lifecycle — fixes the same
    // bug class previously found with the Whisper STT model download.
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
            Column(modifier = Modifier.weight(1f).padding(end = Spacing.large)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    Text(
                        text = entry.label ?: stringResource(entry.labelKey).ifBlank { entry.fileName },
                        style = AppTextStyles.fieldLabel,
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
                val caption = buildString {
                    append(entry.fileName)
                    if (entry.approxSizeBytes > 0) append(" · ${entry.approxSizeBytes / (1024 * 1024)} MB")
                    entry.sourceRepoId?.let { append(" · $it") }
                }
                Text(text = caption, style = AppTextStyles.caption, color = AppTextStyles.secondaryContent)
            }

            when {
                downloading -> {}

                // Selecting which downloaded model is actually used happens in Settings >
                // AI Provider's model picker for the Local LLM instance (it lists every
                // downloaded .gguf via GET /models — see writeModelsList), exactly like any
                // other provider. Loading that model into memory is fully automatic/lazy,
                // driven by the first real chat/embedding request through the proxy (see
                // LlamaServerProcessManager.ensureModelLoaded). This row only manages what's
                // on disk — download/delete, nothing more.
                isDownloaded -> OutlinedButton(
                    enabled = !isDeleting,
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            deleteError = null
                            try {
                                LlamaServerProcessManager.deleteModel(entry)
                                isDownloaded = false
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
            downloadProgress(downloadState.downloadedBytes, downloadState.totalBytes)
        }
        downloadState?.error?.let { error ->
            Text(
                text = LocalizationManager.getString("settings.local_llm.download_failed", error),
                style = AppTextStyles.caption,
                color = MaterialTheme.colorScheme.error,
            )
        }
        deleteError?.let { error ->
            Text(
                text = error,
                style = AppTextStyles.caption,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun downloadProgress(downloadedBytes: Long, totalBytes: Long) {
    val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
    )
    val percent = (progress * 100).toInt()
    val downloadedMb = downloadedBytes / (1024 * 1024)
    val totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0L
    Text(
        text = LocalizationManager.getString("settings.local_llm.downloading", percent, downloadedMb, totalMb),
        style = AppTextStyles.caption,
    )
}

/**
 * Opens a file or directory in the system's file manager. Platform-aware (macOS/Linux/Windows),
 * same approach as `AdvancedSettingsSection`'s log-folder button.
 */
private fun openInFileManager(file: File) {
    try {
        when {
            Platform.isMac -> Runtime.getRuntime().exec(arrayOf("open", file.absolutePath))
            Platform.isLinux -> Runtime.getRuntime().exec(arrayOf("xdg-open", file.absolutePath))
            Platform.isWindows -> Runtime.getRuntime().exec(arrayOf("explorer", file.absolutePath))
            else -> if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
        }
    } catch (e: Exception) {
        log.error("Failed to open local LLM models folder: ${file.absolutePath}", e)
    }
}
