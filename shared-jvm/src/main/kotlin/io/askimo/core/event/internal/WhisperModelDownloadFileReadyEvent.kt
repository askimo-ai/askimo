/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Internal-only signal emitted by `WhisperModelDownloadManager` right after a Whisper model
 * download completes and its path has been persisted to [io.askimo.core.config.AppConfig].
 *
 * Lets the Settings > Voice screen refresh its local "downloaded" state immediately if it's
 * still showing when a background download (started before the user navigated away) finishes —
 * not shown in the notification bell (see [io.askimo.core.event.user.WhisperModelDownloadCompletedEvent]
 * for the user-facing version of this event).
 */
data class WhisperModelDownloadFileReadyEvent(
    val tierName: String,
    val path: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Whisper model '$tierName' ready at $path"
}
