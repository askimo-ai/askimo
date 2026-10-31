/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.ModelProvider
import io.askimo.core.security.SecureKeyManager
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.voice.impl.SystemTextToSpeechService
import io.askimo.ui.voice.impl.whispercpp.whisperModelDownloadSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Core Voice (speech-to-text / text-to-speech) settings card.
 *
 * [sttProviders]/[ttsProviders] are the only caller-specific knob — pass a narrower list to
 * disable providers that aren't supported, no `if (...)` branching needed in this file; every
 * other field here already renders conditionally off the *currently selected* provider.
 *
 * If a value persisted in [AppConfig.rawVoice] isn't in the passed-in list (e.g. imported from a
 * config file that allowed it), it's clamped to the list's first entry and the fix is persisted
 * back — see the `LaunchedEffect(Unit)` below.
 */
@Composable
fun voiceConfigCard(
    sttProviders: List<VoiceProvider>,
    ttsProviders: List<VoiceProvider>,
) {
    var enabled by remember { mutableStateOf(AppConfig.rawVoice.enabled) }
    // Clamp to the first entry this edition actually offers if the persisted value isn't allowed.
    var sttProvider by remember {
        mutableStateOf(AppConfig.rawVoice.sttProvider.takeIf { it in sttProviders } ?: sttProviders.first())
    }
    var ttsProvider by remember {
        mutableStateOf(AppConfig.rawVoice.ttsProvider.takeIf { it in ttsProviders } ?: ttsProviders.first())
    }
    var sttModel by remember { mutableStateOf(AppConfig.rawVoice.sttModel) }
    var ttsModel by remember { mutableStateOf(AppConfig.rawVoice.ttsModel) }
    var ttsVoice by remember { mutableStateOf(AppConfig.rawVoice.ttsVoice) }
    var ttsSpeed by remember { mutableStateOf(AppConfig.rawVoice.ttsSpeed) }
    var localSttEndpoint by remember { mutableStateOf(AppConfig.rawVoice.localSttEndpoint) }
    var localWhisperModelPath by remember { mutableStateOf(AppConfig.rawVoice.localWhisperModelPath) }
    var localTtsEndpoint by remember { mutableStateOf(AppConfig.rawVoice.localTtsEndpoint) }
    var autoSendTranscript by remember { mutableStateOf(AppConfig.rawVoice.autoSendTranscript) }
    var autoPlayResponses by remember { mutableStateOf(AppConfig.rawVoice.autoPlayResponses) }
    var autoStopOnSilence by remember { mutableStateOf(AppConfig.rawVoice.autoStopOnSilence) }
    var autoStopSilenceDurationMs by remember { mutableStateOf(AppConfig.rawVoice.autoStopSilenceDurationMs) }
    var liveTranscriptionEnabled by remember { mutableStateOf(AppConfig.rawVoice.liveTranscriptionEnabled) }
    var ttsCacheMaxMessages by remember { mutableStateOf(AppConfig.rawVoice.ttsCacheMaxMessages) }
    // Stored in bytes in config; displayed/edited in whole MB for a friendlier UI.
    var ttsCacheMaxMb by remember { mutableStateOf((AppConfig.rawVoice.ttsCacheMaxBytes / (1024 * 1024)).toInt().coerceAtLeast(1)) }
    // Sourced from askimo.yml (voice.open_ai_tts_voices) so new OpenAI voices can be added
    // without an Askimo release. Falls back to built-in defaults if ever emptied out.
    val openAiTtsVoices = AppConfig.rawVoice.openAiTtsVoices.ifEmpty { VoiceConfig().openAiTtsVoices }
    // Loaded async from keychain — starts blank, same pattern as web search / proxy.
    var openAiApiKey by remember { mutableStateOf("") }
    // Guards the debounced save below from firing before the keychain lookup completes — else a
    // slow lookup could let the debounce persist "" first and erase the user's stored key.
    var apiKeyLoaded by remember { mutableStateOf(false) }
    var sttProviderDropdownExpanded by remember { mutableStateOf(false) }
    var ttsProviderDropdownExpanded by remember { mutableStateOf(false) }
    var ttsVoiceDropdownExpanded by remember { mutableStateOf(false) }
    var reuseKeyStatus by remember { mutableStateOf<String?>(null) }
    // Voices installed on this machine for VoiceProvider.SYSTEM, lazily fetched once (shelling
    // out has noticeable latency) — null = not fetched yet, empty = fetched, none found.
    var systemVoices by remember { mutableStateOf<List<String>?>(null) }
    var systemVoiceDropdownExpanded by remember { mutableStateOf(false) }

    // Persist the clamp applied above (if any) so the on-disk config matches what's shown here
    // instead of silently diverging on every load.
    LaunchedEffect(Unit) {
        if (sttProvider != AppConfig.rawVoice.sttProvider) {
            withContext(Dispatchers.IO) { AppConfig.updateField("voice.sttProvider", sttProvider) }
        }
        if (ttsProvider != AppConfig.rawVoice.ttsProvider) {
            withContext(Dispatchers.IO) { AppConfig.updateField("voice.ttsProvider", ttsProvider) }
        }
    }

    LaunchedEffect(ttsProvider) {
        if (ttsProvider == VoiceProvider.SYSTEM && systemVoices == null) {
            systemVoices = withContext(Dispatchers.IO) {
                SystemTextToSpeechService(VoiceConfig()).listAvailableVoices()
            }
        }
    }

    LaunchedEffect(Unit) {
        val resolved = withContext(Dispatchers.IO) { AppConfig.voice }
        openAiApiKey = if (VoiceConfig.isActualKey(resolved.openAiApiKey)) resolved.openAiApiKey else ""
        apiKeyLoaded = true
    }

    // ── Debounced saves for typed fields (keychain I/O for the API key — must NOT block the UI) ──
    LaunchedEffect(openAiApiKey) {
        if (!apiKeyLoaded) return@LaunchedEffect
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.openAiApiKey", openAiApiKey) }
    }
    LaunchedEffect(sttModel) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.sttModel", sttModel) }
    }
    LaunchedEffect(ttsModel) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.ttsModel", ttsModel) }
    }
    LaunchedEffect(ttsVoice) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.ttsVoice", ttsVoice) }
    }
    LaunchedEffect(ttsSpeed) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.ttsSpeed", ttsSpeed) }
    }
    LaunchedEffect(localSttEndpoint) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.localSttEndpoint", localSttEndpoint) }
    }
    LaunchedEffect(localTtsEndpoint) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.localTtsEndpoint", localTtsEndpoint) }
    }
    LaunchedEffect(autoStopSilenceDurationMs) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.autoStopSilenceDurationMs", autoStopSilenceDurationMs) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        // ── General: title + enabled toggle ───────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.cardColors(AppColors.Elevation.RAISED),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("settings.voice.title"),
                        style = AppTextStyles.sectionTitle,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("settings.voice.enabled"),
                            style = AppTextStyles.caption,
                        )
                        Switch(
                            checked = enabled,
                            onCheckedChange = { newValue ->
                                enabled = newValue
                                AppConfig.updateField("voice.enabled", newValue)
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }

                Text(
                    text = stringResource("settings.voice.description"),
                    style = AppTextStyles.caption,
                )
            }
        }

        if (!enabled) return@Column

        // ── Speech-to-Text card ────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.cardColors(AppColors.Elevation.RAISED),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Text(
                    text = stringResource("settings.voice.section.stt"),
                    style = AppTextStyles.sectionTitle,
                )

                voiceProviderSelector(
                    label = stringResource("settings.voice.stt_provider"),
                    providers = sttProviders,
                    selected = sttProvider,
                    expanded = sttProviderDropdownExpanded,
                    onExpandedChange = { sttProviderDropdownExpanded = it },
                    onSelect = { newValue ->
                        sttProvider = newValue
                        AppConfig.updateField("voice.sttProvider", newValue)
                        sttProviderDropdownExpanded = false
                    },
                )

                when (sttProvider) {
                    VoiceProvider.LOCAL_WHISPER_CPP -> endpointField(
                        label = stringResource("settings.voice.local_stt_endpoint"),
                        value = localSttEndpoint,
                        onValueChange = { localSttEndpoint = it },
                    )

                    VoiceProvider.LOCAL_WHISPER_FFM -> whisperModelDownloadSection(
                        modelPath = localWhisperModelPath,
                        onModelPathChange = { newPath ->
                            localWhisperModelPath = newPath
                            AppConfig.updateField("voice.localWhisperModelPath", newPath)
                        },
                    )

                    else -> {}
                }

                // Only applies when dictation is actually configured.
                if (sttProvider != VoiceProvider.LOCAL_WHISPER_FFM) {
                    OutlinedTextField(
                        value = sttModel,
                        onValueChange = { sttModel = it },
                        label = { Text(stringResource("settings.voice.stt_model")) },
                        placeholder = { Text(stringResource("settings.voice.stt_model.placeholder")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = AppColors.outlinedTextFieldColors(),
                    )
                }

                // Shown only when STT uses OpenAI (TTS's matching condition below avoids a duplicate).
                if (sttProvider == VoiceProvider.OPENAI) {
                    openAiApiKeyField(
                        apiKey = openAiApiKey,
                        onApiKeyChange = { newValue ->
                            openAiApiKey = newValue
                            reuseKeyStatus = null
                        },
                        reuseKeyStatus = reuseKeyStatus,
                        onReuseKeyClick = {
                            val existingKey = findExistingOpenAiProviderKey()
                            if (existingKey != null) {
                                openAiApiKey = existingKey
                                reuseKeyStatus = "success"
                            } else {
                                reuseKeyStatus = "none_found"
                            }
                        },
                    )
                }

                // Auto-send: what happens with the transcript once it's ready.
                voiceToggleRow(
                    label = stringResource("settings.voice.auto_send"),
                    description = stringResource("settings.voice.auto_send.description"),
                    checked = autoSendTranscript,
                    onCheckedChange = { newValue ->
                        autoSendTranscript = newValue
                        AppConfig.updateField("voice.autoSendTranscript", newValue)
                    },
                )

                // Auto-stops recording on silence (Siri/Alexa-style), no manual stop needed.
                voiceToggleRow(
                    label = stringResource("settings.voice.auto_stop_silence"),
                    description = stringResource("settings.voice.auto_stop_silence.description"),
                    checked = autoStopOnSilence,
                    onCheckedChange = { newValue ->
                        autoStopOnSilence = newValue
                        AppConfig.updateField("voice.autoStopOnSilence", newValue)
                    },
                )

                if (autoStopOnSilence) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource("settings.voice.auto_stop_silence.duration"),
                                style = AppTextStyles.fieldLabel,
                            )
                            Text(
                                text = String.format(java.util.Locale.ROOT, "%.1fs", autoStopSilenceDurationMs / 1000f),
                                style = AppTextStyles.caption,
                                color = AppTextStyles.secondaryContent,
                            )
                        }
                        Slider(
                            value = autoStopSilenceDurationMs.toFloat(),
                            onValueChange = { autoStopSilenceDurationMs = it.toLong() },
                            valueRange = 1_000f..6_000f,
                            modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
                        )
                        Text(
                            text = stringResource("settings.voice.auto_stop_silence.duration.hint"),
                            style = AppTextStyles.caption,
                        )
                    }
                }

                // Only relevant for a streaming-capable STT provider (currently LOCAL_WHISPER_FFM).
                if (sttProvider == VoiceProvider.LOCAL_WHISPER_FFM) {
                    voiceToggleRow(
                        label = stringResource("settings.voice.live_transcription"),
                        description = stringResource("settings.voice.live_transcription.description"),
                        checked = liveTranscriptionEnabled,
                        onCheckedChange = { newValue ->
                            liveTranscriptionEnabled = newValue
                            AppConfig.updateField("voice.liveTranscriptionEnabled", newValue)
                        },
                    )
                }
            }
        }

        // ── Text-to-Speech card ────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.cardColors(AppColors.Elevation.RAISED),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Text(
                    text = stringResource("settings.voice.section.tts"),
                    style = AppTextStyles.sectionTitle,
                )

                voiceProviderSelector(
                    label = stringResource("settings.voice.tts_provider"),
                    providers = ttsProviders,
                    selected = ttsProvider,
                    expanded = ttsProviderDropdownExpanded,
                    onExpandedChange = { ttsProviderDropdownExpanded = it },
                    onSelect = { newValue ->
                        ttsProvider = newValue
                        AppConfig.updateField("voice.ttsProvider", newValue)
                        ttsProviderDropdownExpanded = false
                        // Normalize the voice when switching provider — a Piper/OS voice id isn't
                        // a valid OpenAI voice name, and vice versa.
                        if (newValue == VoiceProvider.OPENAI && ttsVoice !in openAiTtsVoices) {
                            ttsVoice = "alloy"
                            AppConfig.updateField("voice.ttsVoice", "alloy")
                        } else if (newValue == VoiceProvider.SYSTEM) {
                            // SYSTEM always uses the OS default voice; clear any leftover
                            // OpenAI/Piper voice name, which the OS command would reject.
                            ttsVoice = ""
                            AppConfig.updateField("voice.ttsVoice", "")
                        }
                    },
                )

                when (ttsProvider) {
                    VoiceProvider.LOCAL_PIPER -> endpointField(
                        label = stringResource("settings.voice.local_tts_endpoint"),
                        value = localTtsEndpoint,
                        onValueChange = { localTtsEndpoint = it },
                    )

                    else -> {}
                }

                // SYSTEM has no remote "model" concept (it's a local OS binary) — irrelevant there.
                if (ttsProvider != VoiceProvider.SYSTEM) {
                    OutlinedTextField(
                        value = ttsModel,
                        onValueChange = { ttsModel = it },
                        label = { Text(stringResource("settings.voice.tts_model")) },
                        placeholder = { Text(stringResource("settings.voice.tts_model.placeholder")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = AppColors.outlinedTextFieldColors(),
                    )
                }

                // OpenAI voices are a fixed enum (dropdown); Piper ids are free text (user-installed
                // model names); SYSTEM gets its own dropdown of voices actually installed, plus an
                // "OS default" entry for a blank ttsVoice.
                when (ttsProvider) {
                    VoiceProvider.OPENAI -> voiceOptionSelector(
                        label = stringResource("settings.voice.tts_voice"),
                        options = openAiTtsVoices,
                        selected = ttsVoice.ifBlank { "alloy" },
                        expanded = ttsVoiceDropdownExpanded,
                        onExpandedChange = { ttsVoiceDropdownExpanded = it },
                        onSelect = { newValue ->
                            ttsVoice = newValue
                            AppConfig.updateField("voice.ttsVoice", newValue)
                            ttsVoiceDropdownExpanded = false
                        },
                    )

                    VoiceProvider.SYSTEM -> {
                        val systemDefaultLabel = stringResource("settings.voice.tts_voice.system_default")
                        voiceOptionSelector(
                            label = stringResource("settings.voice.tts_voice"),
                            options = listOf(systemDefaultLabel) + systemVoices.orEmpty(),
                            selected = ttsVoice.ifBlank { systemDefaultLabel },
                            expanded = systemVoiceDropdownExpanded,
                            onExpandedChange = { systemVoiceDropdownExpanded = it },
                            onSelect = { newValue ->
                                val resolved = if (newValue == systemDefaultLabel) "" else newValue
                                ttsVoice = resolved
                                AppConfig.updateField("voice.ttsVoice", resolved)
                                systemVoiceDropdownExpanded = false
                            },
                        )
                    }

                    else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                        OutlinedTextField(
                            value = ttsVoice,
                            onValueChange = { ttsVoice = it },
                            label = { Text(stringResource("settings.voice.tts_voice")) },
                            placeholder = { Text(stringResource("settings.voice.tts_voice.placeholder_piper")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = AppColors.outlinedTextFieldColors(),
                        )
                        Text(
                            text = stringResource("settings.voice.tts_voice.piper_hint"),
                            style = AppTextStyles.caption,
                        )
                    }
                }

                // 0.25x-4.0x (OpenAI's range); applies to OpenAI, Piper, and SYSTEM (see
                // SystemTextToSpeechService.speedArgs for the per-OS mapping).
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = stringResource("settings.voice.tts_speed"), style = AppTextStyles.fieldLabel)
                        Text(
                            text = String.format(java.util.Locale.ROOT, "%.2fx", ttsSpeed),
                            style = AppTextStyles.caption,
                            color = AppTextStyles.secondaryContent,
                        )
                    }
                    Slider(
                        value = ttsSpeed.toFloat(),
                        onValueChange = { ttsSpeed = it.toDouble() },
                        valueRange = 0.25f..4.0f,
                        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
                    )
                    Text(text = stringResource("settings.voice.tts_speed.hint"), style = AppTextStyles.caption)
                }

                // Auto-play: whether a response is read aloud once it's ready.
                voiceToggleRow(
                    label = stringResource("settings.voice.auto_play"),
                    description = stringResource("settings.voice.auto_play.description"),
                    checked = autoPlayResponses,
                    onCheckedChange = { newValue ->
                        autoPlayResponses = newValue
                        AppConfig.updateField("voice.autoPlayResponses", newValue)
                    },
                )

                // Shown only when TTS uses OpenAI and STT doesn't (avoids a duplicate).
                if (ttsProvider == VoiceProvider.OPENAI && sttProvider != VoiceProvider.OPENAI) {
                    openAiApiKeyField(
                        apiKey = openAiApiKey,
                        onApiKeyChange = { newValue ->
                            openAiApiKey = newValue
                            reuseKeyStatus = null
                        },
                        reuseKeyStatus = reuseKeyStatus,
                        onReuseKeyClick = {
                            val existingKey = findExistingOpenAiProviderKey()
                            if (existingKey != null) {
                                openAiApiKey = existingKey
                                reuseKeyStatus = "success"
                            } else {
                                reuseKeyStatus = "none_found"
                            }
                        },
                    )
                }
            }
        }

        // ── Playback cache card — bounds the in-memory TTS audio cache used by the "replay
        // last response" shortcut / repeated 🔊 clicks, so replays skip a fresh synthesis call. ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.cardColors(AppColors.Elevation.RAISED),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource("settings.voice.cache.title"),
                    style = AppTextStyles.sectionTitle,
                )
                Text(
                    text = stringResource("settings.voice.cache.description"),
                    style = AppTextStyles.caption,
                    color = AppTextStyles.secondaryContent,
                )
                AppComponents.settingsIntField(
                    label = stringResource("settings.voice.cache.max_messages"),
                    hint = stringResource("settings.voice.cache.max_messages.hint"),
                    value = ttsCacheMaxMessages,
                    minValue = 1,
                    onValueChange = { newValue ->
                        ttsCacheMaxMessages = newValue
                        AppConfig.updateField("voice.ttsCacheMaxMessages", newValue)
                    },
                )
                AppComponents.settingsIntField(
                    label = stringResource("settings.voice.cache.max_mb"),
                    hint = stringResource("settings.voice.cache.max_mb.hint"),
                    value = ttsCacheMaxMb,
                    minValue = 1,
                    onValueChange = { newValue ->
                        ttsCacheMaxMb = newValue
                        AppConfig.updateField("voice.ttsCacheMaxBytes", newValue.toLong() * 1024 * 1024)
                    },
                )
            }
        }
    }
}

/**
 * Compact OpenAI API key input + "reuse existing provider key" link. Shared by the STT/TTS
 * cards so it sits next to whichever config is using OpenAI (each call site's condition is
 * mutually exclusive, so it's never shown twice).
 */
@Composable
private fun openAiApiKeyField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    reuseKeyStatus: String?,
    onReuseKeyClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        AppComponents.appSecretTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource("settings.voice.api_key")) },
            placeholder = { Text(stringResource("settings.voice.api_key.placeholder")) },
            modifier = Modifier.fillMaxWidth(),
        )
        linkButton(onClick = onReuseKeyClick) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = stringResource("settings.voice.reuse_provider_key"),
                style = AppTextStyles.caption,
                modifier = Modifier.padding(start = Spacing.extraSmall),
            )
        }
        reuseKeyStatus?.let { status ->
            Text(
                text = if (status == "success") {
                    stringResource("settings.voice.reuse_provider_key.success")
                } else {
                    stringResource("settings.voice.reuse_provider_key.none_found")
                },
                style = AppTextStyles.caption,
                color = if (status == "success") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/**
 * Looks up the first configured `OPENAI` [io.askimo.core.providers.ProviderInstance] and resolves
 * its real API key from secure storage, so the user doesn't have to paste the same key twice.
 *
 * Uses the same keychain key format ("instance.&lt;id&gt;") as
 * [io.askimo.core.security.SecureSessionManager] — kept in sync manually since that format is
 * an internal implementation detail, not a public API.
 */
private fun findExistingOpenAiProviderKey(): String? {
    val instance = AppConfig.context.providerInstances.firstOrNull { it.providerType == ModelProvider.OPENAI }
        ?: return null
    val settings = instance.settings
    if (settings !is HasApiKey) return null
    return SecureKeyManager.retrieveSecretKey("instance.${instance.id}")
        ?.takeIf { it.isNotBlank() }
        ?: settings.apiKey.takeIf { it.isNotBlank() && it != "***keychain***" }
}

@Composable
private fun voiceProviderSelector(
    label: String,
    providers: List<VoiceProvider>,
    selected: VoiceProvider,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (VoiceProvider) -> Unit,
    labelColor: Color = AppTextStyles.primaryContent,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTextStyles.fieldLabel.copy(color = labelColor),
            modifier = Modifier.weight(1f).padding(end = Spacing.large),
        )

        Box(modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)) {
            themedTooltip(
                text = stringResource("settings.voice.provider.${selected.name.lowercase()}.description"),
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableCard { onExpandedChange(true) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource("settings.voice.provider.${selected.name.lowercase()}"),
                            style = AppTextStyles.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = Spacing.small),
                        )
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Change provider",
                            tint = AppTextStyles.primaryContent,
                        )
                    }
                }
            }

            AppComponents.dropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                providers.forEachIndexed { index, provider ->
                    AppComponents.themedDropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                Text(
                                    text = stringResource("settings.voice.provider.${provider.name.lowercase()}"),
                                    style = AppTextStyles.body,
                                )
                                Text(
                                    text = stringResource("settings.voice.provider.${provider.name.lowercase()}.description"),
                                    style = AppTextStyles.caption,
                                )
                            }
                        },
                        onClick = { onSelect(provider) },
                        isSelected = provider == selected,
                        showDivider = index < providers.lastIndex,
                    )
                }
            }
        }
    }
}

/**
 * Simple labeled dropdown for a fixed list of string [options] — like [voiceProviderSelector]
 * but without per-option descriptions. Used for OpenAI's fixed TTS voice names, where any value
 * outside the enum is rejected by the API, so free text would be misleading.
 */
@Composable
private fun voiceOptionSelector(
    label: String,
    options: List<String>,
    selected: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    labelColor: Color = AppTextStyles.primaryContent,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTextStyles.fieldLabel.copy(color = labelColor),
            modifier = Modifier.weight(1f).padding(end = Spacing.large),
        )

        Box(modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableCard { onExpandedChange(true) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selected.replaceFirstChar { it.uppercase() },
                        style = AppTextStyles.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = Spacing.small),
                    )
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Change voice",
                        tint = AppTextStyles.primaryContent,
                    )
                }
            }

            AppComponents.dropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                options.forEachIndexed { index, option ->
                    AppComponents.themedDropdownMenuItem(
                        text = {
                            Text(
                                text = option.replaceFirstChar { it.uppercase() },
                                style = AppTextStyles.body,
                            )
                        },
                        onClick = { onSelect(option) },
                        isSelected = option == selected,
                        showDivider = index < options.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun endpointField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    colors: TextFieldColors = AppColors.outlinedTextFieldColors(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = colors,
    )
}

@Composable
private fun voiceToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    labelColor: Color = AppTextStyles.primaryContent,
    descriptionColor: Color = AppTextStyles.secondaryContent,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.large)) {
            Text(text = label, style = AppTextStyles.fieldLabel.copy(color = labelColor))
            Text(text = description, style = AppTextStyles.caption.copy(color = descriptionColor))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}
