/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.ModelProvider
import io.askimo.core.security.SecureKeyManager
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.voice.impl.whispercpp.WhisperModelCatalog
import io.askimo.ui.voice.impl.whispercpp.WhisperModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** [VoiceProvider] entries valid for speech-to-text (LOCAL_PIPER is TTS-only). [VoiceProvider.NONE]
 *  lets the user disable dictation only, while keeping TTS enabled. */
private val sttProviders = listOf(VoiceProvider.NONE, VoiceProvider.OPENAI, VoiceProvider.LOCAL_WHISPER_CPP, VoiceProvider.LOCAL_WHISPER_FFM)

/** [VoiceProvider] entries valid for text-to-speech (LOCAL_WHISPER_CPP is STT-only). [VoiceProvider.NONE]
 *  lets the user disable auto-play/playback only, while keeping dictation enabled. */
private val ttsProviders = listOf(VoiceProvider.NONE, VoiceProvider.OPENAI, VoiceProvider.LOCAL_PIPER)

@Composable
fun voiceSettingsSection() {
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
                    text = stringResource("settings.voice"),
                    style = AppTextStyles.pageTitle,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                Text(
                    text = stringResource("settings.voice.description"),
                    style = AppTextStyles.bodySecondary,
                    color = AppTextStyles.secondaryContent,
                )

                voiceConfigCard()
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

@Composable
private fun voiceConfigCard() {
    var enabled by remember { mutableStateOf(AppConfig.rawVoice.enabled) }
    var sttProvider by remember { mutableStateOf(AppConfig.rawVoice.sttProvider) }
    var ttsProvider by remember { mutableStateOf(AppConfig.rawVoice.ttsProvider) }
    var sttModel by remember { mutableStateOf(AppConfig.rawVoice.sttModel) }
    var ttsModel by remember { mutableStateOf(AppConfig.rawVoice.ttsModel) }
    var ttsVoice by remember { mutableStateOf(AppConfig.rawVoice.ttsVoice) }
    var ttsSpeed by remember { mutableStateOf(AppConfig.rawVoice.ttsSpeed) }
    var localSttEndpoint by remember { mutableStateOf(AppConfig.rawVoice.localSttEndpoint) }
    var localWhisperModelPath by remember { mutableStateOf(AppConfig.rawVoice.localWhisperModelPath) }
    var localTtsEndpoint by remember { mutableStateOf(AppConfig.rawVoice.localTtsEndpoint) }
    var autoSendTranscript by remember { mutableStateOf(AppConfig.rawVoice.autoSendTranscript) }
    var autoPlayResponses by remember { mutableStateOf(AppConfig.rawVoice.autoPlayResponses) }
    var ttsCacheMaxMessages by remember { mutableStateOf(AppConfig.rawVoice.ttsCacheMaxMessages) }
    // Stored in bytes in config; displayed/edited in whole MB for a friendlier UI.
    var ttsCacheMaxMb by remember { mutableStateOf((AppConfig.rawVoice.ttsCacheMaxBytes / (1024 * 1024)).toInt().coerceAtLeast(1)) }
    // Sourced from askimo.yml (voice.open_ai_tts_voices) instead of hardcoded — when OpenAI
    // ships a new voice, users can add it to their config file themselves and pick it up
    // immediately, without waiting for an Askimo release. Falls back to the built-in defaults
    // if the list was ever emptied out.
    val openAiTtsVoices = AppConfig.rawVoice.openAiTtsVoices.ifEmpty { VoiceConfig().openAiTtsVoices }
    // API key loaded async from keychain — starts blank, same pattern as web search / proxy.
    var openAiApiKey by remember { mutableStateOf("") }
    // Guards the debounced save below from firing on the initial blank value before the keychain
    // lookup in LaunchedEffect(Unit) completes — without this, a slow/delayed lookup could let the
    // 500ms debounce persist "" first and erase the user's stored key just from opening this screen.
    var apiKeyLoaded by remember { mutableStateOf(false) }
    var sttProviderDropdownExpanded by remember { mutableStateOf(false) }
    var ttsProviderDropdownExpanded by remember { mutableStateOf(false) }
    var ttsVoiceDropdownExpanded by remember { mutableStateOf(false) }
    var reuseKeyStatus by remember { mutableStateOf<String?>(null) }

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

                // Everything below only applies when dictation is actually enabled — when the
                // user picks NONE, there's no model/endpoint/key to configure and auto-send is
                // meaningless (nothing will ever be transcribed).
                if (sttProvider != VoiceProvider.NONE) {
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

                    // OpenAI API key — only relevant here when STT is actually using OpenAI, so it
                    // sits right next to the model field it configures instead of a separate card.
                    // (If TTS is also OpenAI, this is the only place the key field is shown — see
                    // the matching condition in the TTS card below — to avoid duplicating it.)
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

                    // Auto-send belongs to dictation (STT) — it decides what happens with the
                    // transcribed text once it's ready.
                    voiceToggleRow(
                        label = stringResource("settings.voice.auto_send"),
                        description = stringResource("settings.voice.auto_send.description"),
                        checked = autoSendTranscript,
                        onCheckedChange = { newValue ->
                            autoSendTranscript = newValue
                            AppConfig.updateField("voice.autoSendTranscript", newValue)
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
                        // Normalize the stored voice when switching into OpenAI — a Piper voice id
                        // (e.g. "en_US-lessac-medium") is not a valid OpenAI voice name.
                        if (newValue == VoiceProvider.OPENAI && ttsVoice !in openAiTtsVoices) {
                            ttsVoice = "alloy"
                            AppConfig.updateField("voice.ttsVoice", "alloy")
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

                // Everything below only applies when playback is actually enabled — when the
                // user picks NONE, there's no model/voice/speed/key to configure and auto-play
                // is meaningless (nothing will ever be synthesized).
                if (ttsProvider != VoiceProvider.NONE) {
                    OutlinedTextField(
                        value = ttsModel,
                        onValueChange = { ttsModel = it },
                        label = { Text(stringResource("settings.voice.tts_model")) },
                        placeholder = { Text(stringResource("settings.voice.tts_model.placeholder")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = AppColors.outlinedTextFieldColors(),
                    )

                    // OpenAI's voice names are a fixed enum — offer a dropdown so users can't type an
                    // invalid value. Piper voice ids are arbitrary local model names the user installs
                    // themselves, so that case stays free text with a hint of the expected format.
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

                    // Playback speed (0.25x–4.0x, OpenAI's supported range) — applies to both OpenAI
                    // and Piper TTS providers since both accept the same `speed` field.
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

                    // Auto-play belongs to voice output (TTS) — it decides what happens once an
                    // AI response is ready to be read aloud.
                    voiceToggleRow(
                        label = stringResource("settings.voice.auto_play"),
                        description = stringResource("settings.voice.auto_play.description"),
                        checked = autoPlayResponses,
                        onCheckedChange = { newValue ->
                            autoPlayResponses = newValue
                            AppConfig.updateField("voice.autoPlayResponses", newValue)
                        },
                    )

                    // OpenAI API key — shown here only when TTS uses OpenAI and STT doesn't also
                    // use it (the STT card already shows it in that case), so it never duplicates.
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
        }

        // ── Playback cache card — bounds the in-memory TTS audio cache used by the "replay
        // last response" shortcut / repeated 🔊 clicks, so replays skip a fresh synthesis call.
        // Hidden entirely when TTS is disabled — there would be nothing to cache. ──
        if (ttsProvider != VoiceProvider.NONE) {
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
                    voiceIntField(
                        label = stringResource("settings.voice.cache.max_messages"),
                        hint = stringResource("settings.voice.cache.max_messages.hint"),
                        value = ttsCacheMaxMessages,
                        minValue = 1,
                        onValueChange = { newValue ->
                            ttsCacheMaxMessages = newValue
                            AppConfig.updateField("voice.ttsCacheMaxMessages", newValue)
                        },
                    )
                    voiceIntField(
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
}

/**
 * Compact OpenAI API key input + "reuse existing provider key" link — reused by both the STT
 * and TTS cards so it appears right next to whichever model config is actually using OpenAI,
 * instead of in a separate always-visible card. Only shown once even when both STT and TTS use
 * OpenAI (see the mutually-exclusive conditions at each call site).
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

/**
 * Model-tier picker + explicit "Download" button for [VoiceProvider.LOCAL_WHISPER_FFM] — the
 * download runs only when the user clicks it (never lazily on first transcription), per the
 * explicit-in-Settings decision: the provider shouldn't silently kick off a multi-GB download
 * the first time a user tries to use voice input.
 *
 * [modelPath] is the currently persisted [VoiceConfig.localWhisperModelPath]; [onModelPathChange]
 * is called with the downloaded file's absolute path once a download completes (or with "" after
 * a delete), so the caller can persist it via `AppConfig.updateField`.
 */
@Composable
private fun whisperModelDownloadSection(
    modelPath: String,
    onModelPathChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentTier = WhisperModelCatalog.entries.find { WhisperModelDownloader.modelPath(it).toString() == modelPath }
    var selectedTier by remember { mutableStateOf(currentTier ?: WhisperModelCatalog.BALANCED) }
    var downloading by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val isDownloaded = modelPath.isNotBlank() && WhisperModelDownloader.isDownloaded(selectedTier) &&
        WhisperModelDownloader.modelPath(selectedTier).toString() == modelPath

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
                                downloadError = null
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
                Text(
                    text = LocalizationManager.getString(
                        "settings.voice.whisper_model.downloading",
                        percent,
                        downloadedMb,
                        totalMb,
                    ),
                    style = AppTextStyles.caption,
                )
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
                        downloading = true
                        downloadError = null
                        downloadedBytes = 0L
                        totalBytes = -1L
                        scope.launch {
                            try {
                                val path = WhisperModelDownloader.download(
                                    tier = selectedTier,
                                    baseUrl = AppConfig.rawVoice.whisperModelBaseUrl,
                                ) { done, total ->
                                    downloadedBytes = done
                                    totalBytes = total
                                }
                                onModelPathChange(path.toString())
                            } catch (e: Exception) {
                                downloadError = e.message
                            } finally {
                                downloading = false
                            }
                        }
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

/**
 * Numeric input field for voice cache size settings — mirrors the validation pattern used by
 * `AdvancedSettingsSection.ragIntField`: shows a raw editable number while focused, reformats
 * with locale grouping on blur, flags invalid/out-of-range input with [OutlinedTextField.isError]
 * instead of silently clamping while the user is still typing, and only calls [onValueChange]
 * with an already-validated value (never blank/non-numeric/below [minValue]).
 */
@Composable
private fun voiceIntField(
    label: String,
    hint: String,
    value: Int,
    minValue: Int,
    onValueChange: (Int) -> Unit,
    labelColor: Color = AppTextStyles.primaryContent,
    hintColor: Color = AppTextStyles.secondaryContent,
    colors: TextFieldColors = AppColors.outlinedTextFieldColors(),
) {
    var lastValidValue by remember { mutableStateOf(value) }
    var textValue by remember { mutableStateOf(LocalizationManager.formatNumber(value)) }
    var isEditing by remember { mutableStateOf(false) }
    var showSavedIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value != lastValidValue) {
            lastValidValue = value
            if (!isEditing) textValue = LocalizationManager.formatNumber(value)
        }
    }

    LaunchedEffect(showSavedIndicator) {
        if (showSavedIndicator) {
            delay(2000.milliseconds)
            showSavedIndicator = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(text = label, style = AppTextStyles.body.copy(color = labelColor))
        val parsedValue = textValue.toIntOrNull()
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        isEditing = true
                        textValue = lastValidValue.toString()
                    } else {
                        isEditing = false
                        textValue.toIntOrNull()?.takeIf { it >= minValue }?.let { validInt ->
                            if (validInt != lastValidValue) {
                                lastValidValue = validInt
                                onValueChange(validInt)
                                showSavedIndicator = true
                            }
                        }
                        // Reject non-numeric / below-minimum input by reverting to the last
                        // valid value instead of persisting garbage.
                        textValue = LocalizationManager.formatNumber(lastValidValue)
                    }
                },
            textStyle = AppTextStyles.body.copy(color = Color.Unspecified),
            singleLine = true,
            isError = isEditing && (parsedValue == null || parsedValue < minValue),
            trailingIcon = {
                AnimatedVisibility(visible = showSavedIndicator, enter = fadeIn(), exit = fadeOut()) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Saved",
                        tint = AppTextStyles.primaryContent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            colors = colors,
        )
        Text(text = hint, style = AppTextStyles.caption.copy(color = hintColor))
    }
}
