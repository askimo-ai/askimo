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
 * Emitted whenever local data changes that must be pushed to the sync server.
 *
 * All write paths — new messages, session renames, starring, project
 * creates/updates — schedule a debounced push via this event.
 *
 * The [reason] field is optional and used only for debug logging.
 */
data class PushDataToServerEvent(
    val reason: String = "data changed",
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Push to server requested: $reason"
}
