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
 * Generic event to request a refresh of the plans list.
 * This event can be emitted by any component that modifies plan data and needs
 * the UI to reflect those changes.
 *
 * Use cases:
 * - Plan created or saved
 * - Plan deleted
 * - Plans pulled from server (sync)
 * - Any other scenario requiring plan list refresh
 *
 * @param reason Optional description of why the refresh is needed (for debugging)
 */
data class PlansRefreshEvent(
    val reason: String? = null,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = reason?.let { "Plans refresh requested: $it" }
        ?: "Plans refresh requested"
}
