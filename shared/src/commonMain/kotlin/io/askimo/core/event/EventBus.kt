/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Central event bus for all application events.
 * Events are automatically routed to appropriate channels based on event.type property.
 */
object EventBus {
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Developer events only (shown in developer dialog when enabled)
    private val _developerEvents = MutableSharedFlow<Event>(
        replay = 100, // Keep history for developer view
        extraBufferCapacity = 500,
    )

    // User events only (shown to end users)
    private val _userEvents = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 100,
    )

    // Internal events (component-to-component communication, not shown in UI)
    private val _internalEvents = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 200,
    )

    // Error events (critical errors that need user attention)
    private val _errorEvents = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 100,
    )

    /**
     * Developer-only events (requires developer mode enabled)
     * Subscribe to this for debugging/development tools
     */
    val developerEvents: SharedFlow<Event> = _developerEvents.asSharedFlow()

    /**
     * User-facing events (shown to end users)
     * Subscribe to this for user notifications and alerts
     */
    val userEvents: SharedFlow<Event> = _userEvents.asSharedFlow()

    /**
     * Internal events for component-to-component communication
     * Subscribe to this for reacting to system-level changes (model changes, indexing completion, etc.)
     */
    val internalEvents: SharedFlow<Event> = _internalEvents.asSharedFlow()

    /**
     * Error events (critical errors that need user attention)
     * Subscribe to this for displaying error dialogs and notifications
     */
    val errorEvents: SharedFlow<Event> = _errorEvents.asSharedFlow()

    /**
     * Emit an event - automatically routes to the appropriate channel
     * based on event.type property
     */
    suspend fun emit(event: Event) {
        when (event.type) {
            EventType.DEVELOPER -> _developerEvents.emit(event)
            EventType.INTERNAL -> _internalEvents.emit(event)
            EventType.USER -> _userEvents.emit(event)
            EventType.ERROR -> _errorEvents.emit(event)
        }
    }

    /**
     * Non-suspending emit (uses launch internally)
     */
    fun post(event: Event) {
        eventScope.launch {
            emit(event)
        }
    }
}

