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
 * Internal event fired when user requests to invalidate caches.
 * This triggers cache cleanup across various cache systems.
 */
data class InvalidateCacheEvent(
    override val timestamp: Instant = Clock.System.now(),
) : Event {
    override val source: EventSource = EventSource.SYSTEM
    override val type: EventType = EventType.INTERNAL

    override fun getDetails(): String = "Cache invalidation requested"
}
