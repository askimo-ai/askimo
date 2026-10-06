/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event

import kotlin.time.Instant

/**
 * Classification of events for routing and handling
 */
enum class EventType {
    /**
     * Events shown to end users in the UI (notifications, errors, success messages)
     */
    USER,

    /**
     * Events for internal component communication (not shown in UI)
     * Examples: ModelChangedEvent, ProjectIndexedEvent, MemorySummarizedEvent
     */
    INTERNAL,

    /**
     * Error events that need user attention (shown as error dialogs/notifications)
     */
    ERROR,
}

/**
 * Base interface for all events in the system.
 */
interface Event {
    /**
     * Type of event determines routing and visibility
     */
    val type: EventType get() = EventType.USER

    /** When the event occurred */
    val timestamp: Instant

    /** Event source/category for filtering */
    val source: EventSource

    /** Get displayable details for this event */
    fun getDetails(): String
}

/**
 * Event sources/categories for organization and filtering
 */
enum class EventSource {
    SYSTEM,
}
