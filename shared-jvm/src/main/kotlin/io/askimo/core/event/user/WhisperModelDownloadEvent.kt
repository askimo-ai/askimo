/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.user

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Emitted when a Whisper speech-to-text model download starts (Settings > Voice >
 * [io.askimo.core.config.VoiceProvider.LOCAL_WHISPER_FFM]). Shown in the notification footer
 * (deduped/updated in place by [tierName] as the download progresses to completed/failed — see
 * `indexingNotificationId`-style handling in `NotificationComponents.kt`) so the user can tell a
 * background download has begun even if they navigate away from Settings.
 */
data class WhisperModelDownloadStartedEvent(
    /** Stable dedup key — [io.askimo.ui.voice.impl.whispercpp.WhisperModelCatalog.name]. */
    val tierName: String,
    val tierLabel: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Downloading Whisper model '$tierLabel'…"
}

/**
 * Emitted when a Whisper model download completes successfully. Shown in the notification
 * footer, replacing the in-progress card for the same [tierName] — so the user is informed even
 * if they left the Voice settings screen.
 */
data class WhisperModelDownloadCompletedEvent(
    val tierName: String,
    val tierLabel: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Whisper model '$tierLabel' downloaded successfully."
}

/**
 * Emitted when a Whisper model download fails. Shown in the notification footer (and
 * immediately surfaces the popup, like other failures), replacing the in-progress card for the
 * same [tierName] — so the user is informed even if they left the Voice settings screen.
 */
data class WhisperModelDownloadFailedEvent(
    val tierName: String,
    val tierLabel: String,
    val errorMessage: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Failed to download Whisper model '$tierLabel': $errorMessage"
}
