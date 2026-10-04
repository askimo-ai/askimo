/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.error

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import kotlin.time.Clock
import kotlin.time.Instant

class SendMessageErrorEvent(
    val throwable: Throwable,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.ERROR

    override fun getDetails() = "Can not send message. Reason ${throwable.message}"
}
