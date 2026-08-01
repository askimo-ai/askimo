/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.system

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * A [EventType.USER]-visible event emitted when an error occurs while processing or
 * dispatching another event in the shell layer.
 *
 * This event acts as a wrapper so that unexpected failures in event handlers are
 * surfaced to the user (e.g. shown in the notification popup) rather than silently
 * swallowed. The original exception is preserved via [cause] for debugging.
 *
 * @property cause The original exception that triggered this error event.
 * @property title Optional short unlocalized title shown as the card header in the
 *   notification popup (e.g. "MCP Tool Error"). Falls back to the generic i18n key
 *   `event.shell.error` when not provided.
 * @property errorMessage Optional unlocalized human-readable context message describing
 *   what operation failed (e.g. "Could not load available models"). Localization should
 *   be applied in the UI rendering layer, not here. Falls back to [cause].message in
 *   [getDetails] when not provided.
 */
class ShellErrorEvent(
    override val timestamp: Instant = Instant.now(),
    val cause: Throwable,
    val title: String? = null,
    val errorMessage: String? = null,
) : Event {
    override val source: EventSource = EventSource.SYSTEM
    override val type: EventType = EventType.USER

    override fun getDetails(): String = errorMessage ?: cause.message ?: "Error"
}
