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
 * Emitted when the user clicks the Run button on a code block in the chat.
 *
 * @param code          The source code / script to run.
 * @param language      The fence language tag (e.g. "bash", "sh").
 * @param couldExecute  When true the terminal will submit the command immediately.
 *                      When false (default) the command is only pasted into the
 *                      terminal input so the user can review it before pressing Enter.
 */
data class RunCodeEvent(
    val code: String,
    val language: String,
    val couldExecute: Boolean = false,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Run $language code (autoExecute=$couldExecute)"
}
