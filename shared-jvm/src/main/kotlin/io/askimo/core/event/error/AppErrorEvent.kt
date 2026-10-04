/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.error

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Generic application error event for errors that do not warrant a dedicated event type.
 *
 * Use this for non-critical, "fire-and-forget" UI errors where no other component needs
 * to react programmatically. The [title] and [message] are already-resolved strings so
 * that [shared] stays free of any UI/i18n dependency — resolution happens at the call site.
 *
 * For errors that significantly impact UX flow (e.g. model unavailable, send failure),
 * prefer a dedicated event type so that other components can react specifically.
 *
 * @param title Short, user-facing title for the error dialog.
 * @param message Detailed, user-facing description of what went wrong.
 * @param cause Optional underlying throwable, used for debug logging only.
 */
data class AppErrorEvent(
    val title: String,
    val message: String,
    val cause: Throwable? = null,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.ERROR

    override fun getDetails() = "[$source] $title: $message"
}
