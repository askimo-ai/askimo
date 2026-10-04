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
 * Generic event to request a refresh of the sessions list in the sidebar.
 * This event can be emitted by any component that modifies session data and needs
 * the UI to reflect those changes.
 *
 * Use cases:
 * - Session moved to/from a project
 * - Session deleted
 * - Session created
 * - Session properties changed (title, starred, etc.)
 * - Project associations changed
 * - Any other scenario requiring session list refresh
 *
 * @param reason Optional description of why the refresh is needed (for debugging)
 */
data class SessionsRefreshEvent(
    val reason: String? = null,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = reason?.let { "Sessions refresh requested: $it" }
        ?: "Sessions refresh requested"
}
