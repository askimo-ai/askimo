/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.system

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * User event fired when a new version of the application is available.
 * This is a user-facing event that triggers update notifications.
 */
data class UpdateAvailableEvent(
    val currentVersion: String,
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    override val timestamp: Instant = Clock.System.now(),
) : Event {
    override val source: EventSource = EventSource.SYSTEM
    override val type: EventType = EventType.USER

    override fun getDetails(): String = buildString {
        appendLine("✨ Askimo v$latestVersion is ready! You're on v$currentVersion.")
        if (releaseNotes.isNotBlank()) {
            appendLine()
            append(releaseNotes.lines().take(4).joinToString("\n"))
        }
    }.trimEnd()
}
