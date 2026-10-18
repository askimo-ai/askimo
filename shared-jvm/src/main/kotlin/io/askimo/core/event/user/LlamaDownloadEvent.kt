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
 * Emitted when the `llama-server` engine binary download starts (Settings > Local LLM's binary
 * Switch). Shown in the notification footer (deduped/updated in place by [platformId] as the
 * download progresses to completed/failed — see `llamaBinaryDownloadNotificationId`-style
 * handling in `NotificationComponents.kt`, mirroring [WhisperModelDownloadStartedEvent]) so the
 * user can tell a background download has begun even if they navigate away from Settings.
 */
data class LlamaBinaryDownloadStartedEvent(
    /** Stable dedup key — [io.askimo.core.providers.llama.LlamaPlatformId] name. */
    val platformId: String,
    val platformLabel: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Downloading Local LLM engine for '$platformLabel'…"
}

/**
 * Emitted when the `llama-server` engine binary download completes successfully. Shown in the
 * notification footer, replacing the in-progress card for the same [platformId] — so the user is
 * informed even if they left the Local LLM settings screen.
 */
data class LlamaBinaryDownloadCompletedEvent(
    val platformId: String,
    val platformLabel: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Local LLM engine for '$platformLabel' downloaded successfully."
}

/**
 * Emitted when the `llama-server` engine binary download fails. Shown in the notification footer
 * (and immediately surfaces the popup, like other failures), replacing the in-progress card for
 * the same [platformId] — so the user is informed even if they left the Local LLM settings screen.
 */
data class LlamaBinaryDownloadFailedEvent(
    val platformId: String,
    val platformLabel: String,
    val errorMessage: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Failed to download Local LLM engine for '$platformLabel': $errorMessage"
}

/**
 * Emitted when a `.gguf` model download starts (Settings > Local LLM's model list). Shown in the
 * notification footer (deduped/updated in place by [fileName] as the download progresses to
 * completed/failed — see `llamaModelDownloadNotificationId`-style handling in
 * `NotificationComponents.kt`, mirroring [WhisperModelDownloadStartedEvent]) so the user can tell
 * a background download has begun even if they navigate away from Settings.
 */
data class LlamaModelDownloadStartedEvent(
    /** Stable dedup key — [io.askimo.core.providers.llama.LlamaModelEntry.fileName]. */
    val fileName: String,
    val modelLabel: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Downloading model '$modelLabel'…"
}

/**
 * Emitted when a `.gguf` model download completes successfully. Shown in the notification
 * footer, replacing the in-progress card for the same [fileName] — so the user is informed even
 * if they left the Local LLM settings screen.
 */
data class LlamaModelDownloadCompletedEvent(
    val fileName: String,
    val modelLabel: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Model '$modelLabel' downloaded successfully."
}

/**
 * Emitted when a `.gguf` model download fails. Shown in the notification footer (and immediately
 * surfaces the popup, like other failures), replacing the in-progress card for the same
 * [fileName] — so the user is informed even if they left the Local LLM settings screen.
 */
data class LlamaModelDownloadFailedEvent(
    val fileName: String,
    val modelLabel: String,
    val errorMessage: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Failed to download model '$modelLabel': $errorMessage"
}
