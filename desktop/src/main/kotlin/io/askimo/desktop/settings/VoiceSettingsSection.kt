/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** [VoiceProvider] entries valid for speech-to-text (LOCAL_PIPER is TTS-only). */
private val sttProviders = listOf(VoiceProvider.OPENAI, VoiceProvider.LOCAL_WHISPER_CPP)

/** [VoiceProvider] entries valid for text-to-speech (LOCAL_WHISPER_CPP is STT-only). */
private val ttsProviders = listOf(VoiceProvider.OPENAI, VoiceProvider.LOCAL_PIPER)

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
                    .padding(start = Spacing.extraLarge, top = Spacing.extraLarge, bottom = Spacing.extraLarge, end = 36.dp),
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
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
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
    var localSttEndpoint by remember { mutableStateOf(AppConfig.rawVoice.localSttEndpoint) }
    var localTtsEndpoint by remember { mutableStateOf(AppConfig.rawVoice.localTtsEndpoint) }
    var autoSendTranscript by remember { mutableStateOf(AppConfig.rawVoice.autoSendTranscript) }
    var autoPlayResponses by remember { mutableStateOf(AppConfig.rawVoice.autoPlayResponses) }
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

    val showApiKeyField = sttProvider == VoiceProvider.OPENAI || ttsProvider == VoiceProvider.OPENAI

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
    LaunchedEffect(localSttEndpoint) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.localSttEndpoint", localSttEndpoint) }
    }
    LaunchedEffect(localTtsEndpoint) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("voice.localTtsEndpoint", localTtsEndpoint) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.bannerCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // ── Header row: title + enabled toggle ───────────────────────────────
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

            if (!enabled) return@Column

            HorizontalDivider()

            // ── Speech-to-Text section ────────────────────────────────────────────
            Text(
                text = stringResource("settings.voice.section.stt"),
                style = AppTextStyles.groupTitle,
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

                else -> {}
            }

            OutlinedTextField(
                value = sttModel,
                onValueChange = { sttModel = it },
                label = { Text(stringResource("settings.voice.stt_model")) },
                placeholder = { Text(stringResource("settings.voice.stt_model.placeholder")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

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

            HorizontalDivider()

            // ── Text-to-Speech section ────────────────────────────────────────────
            Text(
                text = stringResource("settings.voice.section.tts"),
                style = AppTextStyles.groupTitle,
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

            OutlinedTextField(
                value = ttsModel,
                onValueChange = { ttsModel = it },
                label = { Text(stringResource("settings.voice.tts_model")) },
                placeholder = { Text(stringResource("settings.voice.tts_model.placeholder")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
                    )
                    Text(
                        text = stringResource("settings.voice.tts_voice.piper_hint"),
                        style = AppTextStyles.caption,
                    )
                }
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

            // ── OpenAI API key (separate from any OPENAI chat provider instance key) ──
            if (showApiKeyField) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    AppComponents.appSecretTextField(
                        value = openAiApiKey,
                        onValueChange = { newValue ->
                            openAiApiKey = newValue
                            reuseKeyStatus = null
                        },
                        label = { Text(stringResource("settings.voice.api_key")) },
                        placeholder = { Text(stringResource("settings.voice.api_key.placeholder")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    linkButton(
                        onClick = {
                            val existingKey = findExistingOpenAiProviderKey()
                            if (existingKey != null) {
                                openAiApiKey = existingKey
                                reuseKeyStatus = "success"
                            } else {
                                reuseKeyStatus = "none_found"
                            }
                        },
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(
                            text = stringResource("settings.voice.reuse_provider_key"),
                            style = AppTextStyles.caption,
                            modifier = Modifier.padding(start = 4.dp),
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTextStyles.fieldLabel,
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
                            .padding(12.dp),
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTextStyles.fieldLabel,
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
                        .padding(12.dp),
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun voiceToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.large)) {
            Text(text = label, style = AppTextStyles.fieldLabel)
            Text(text = description, style = AppTextStyles.caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}
