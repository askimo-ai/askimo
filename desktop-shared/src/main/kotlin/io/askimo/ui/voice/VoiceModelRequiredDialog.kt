/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.askimo.core.config.AppConfig
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.voice.impl.whispercpp.WhisperModelDownloadManager
import io.askimo.ui.voice.impl.whispercpp.whisperModelDownloadSection

/**
 * Shown instead of silently opening the mic when [VoiceRecordingController.toggle] is invoked
 * while [io.askimo.core.config.VoiceConfig.isLocalWhisperModelMissing] is true — the user picked
 * [io.askimo.core.config.VoiceProvider.LOCAL_WHISPER_FFM] but hasn't downloaded its model yet.
 *
 * Embeds the same [whisperModelDownloadSection] used in Settings > Voice, so starting a download
 * here is identical. The download is owned by the app-scoped [WhisperModelDownloadManager], not
 * this composable, so it keeps running if the user dismisses this dialog mid-download —
 * re-opening it just picks the same progress back up.
 *
 * A successful completion auto-dismisses the dialog; the user clicks 🎤 again to start recording.
 */
@Composable
fun voiceModelRequiredDialog(onDismiss: () -> Unit) {
    val downloadState by WhisperModelDownloadManager.state.collectAsState()
    var wasDownloading by remember { mutableStateOf(downloadState?.isActive == true) }
    LaunchedEffect(downloadState) {
        val state = downloadState
        when {
            state?.isActive == true -> wasDownloading = true

            // active -> null is a successful completion; a failure leaves state non-null with
            // .error set, which whisperModelDownloadSection already surfaces inline.
            wasDownloading && state == null -> {
                wasDownloading = false
                onDismiss()
            }
        }
    }

    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("chat.voice.model_required.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Text(stringResource("chat.voice.model_required.message"), style = AppTextStyles.body)
                whisperModelDownloadSection(
                    modelPath = AppConfig.rawVoice.localWhisperModelPath,
                    onModelPathChange = { newPath ->
                        AppConfig.updateField("voice.localWhisperModelPath", newPath)
                    },
                )
            }
        },
        confirmButton = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.close"))
            }
        },
    )
}
