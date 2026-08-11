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
 * Emitted after a message bookmark is successfully toggled.
 *
 * Consumed by [SessionsViewModel] to keep the sidebar bookmark-count badge
 * in sync without a full reload.
 *
 * @param sessionId The session that owns the bookmarked message.
 * @param delta     +1 when a bookmark was added, -1 when it was removed.
 */
data class BookmarkToggledEvent(
    val sessionId: String,
    val delta: Int,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails() = "Bookmark toggled in session $sessionId (delta=$delta)"
}
