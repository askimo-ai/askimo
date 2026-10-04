/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.dev

import io.askimo.core.event.DeveloperEvent
import io.askimo.core.event.EventSource
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface LoggingEvent : DeveloperEvent {
    override val source: EventSource get() = EventSource.SYSTEM

    data class LogMessage(
        val level: String,
        val logger: String,
        val message: String,
        override val timestamp: Instant = Clock.System.now(),
    ) : LoggingEvent {
        override fun getDetails(): String = "[$level] $logger: $message"
    }
}
