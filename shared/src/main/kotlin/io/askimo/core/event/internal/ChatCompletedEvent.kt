/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Emitted when a chat session finishes generating a response (success, error, or cancellation).
 */
data class ChatCompletedEvent(
    val sessionId: String,
    val failed: Boolean = false,
    /**
     * Short, plain-text (markdown stripped) preview of the completed AI response, capped to a
     * small length (see `stripMarkdownForPreview`). Null on failure/cancellation, or an empty
     * string when no response text is available. Only surfaced to the user when they've
     * explicitly opted in via `NotificationsConfig.showDetails`
     * (see `io.askimo.core.config.NotificationsConfig`) -- kept private/generic otherwise.
     */
    val preview: String? = null,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Chat completed for session $sessionId (failed=$failed)"
}
