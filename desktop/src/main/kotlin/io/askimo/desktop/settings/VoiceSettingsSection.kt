/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.askimo.core.config.VoiceProvider
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.settings.voiceConfigCard

/** [VoiceProvider] entries valid for speech-to-text (LOCAL_PIPER is TTS-only) in the community
 *  edition. */
private val sttProviders = listOf(VoiceProvider.OPENAI, VoiceProvider.LOCAL_WHISPER_CPP, VoiceProvider.LOCAL_WHISPER_FFM)

/** [VoiceProvider] entries valid for text-to-speech (LOCAL_WHISPER_CPP is STT-only) in the
 *  community edition. */
private val ttsProviders = listOf(VoiceProvider.OPENAI, VoiceProvider.LOCAL_PIPER, VoiceProvider.SYSTEM)

/**
 * Community-edition Voice settings page — thin wrapper around the shared
 * [io.askimo.ui.settings.voiceConfigCard], passing the full set of providers this edition
 * supports. See that composable's kdoc for why no further branching is needed here.
 */
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

                voiceConfigCard(sttProviders = sttProviders, ttsProviders = ttsProviders)
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
