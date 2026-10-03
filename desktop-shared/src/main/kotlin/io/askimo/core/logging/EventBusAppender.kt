/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.askimo.core.event.EventBus
import io.askimo.core.event.dev.LoggingEvent
import java.time.Instant

/**
 * Custom Logback appender that forwards log events to EventBus.
 */
class EventBusAppender : AppenderBase<ILoggingEvent>() {

    override fun append(eventObject: ILoggingEvent) {
        try {
            val event = LoggingEvent.LogMessage(
                level = eventObject.level.toString(),
                logger = eventObject.loggerName,
                message = eventObject.formattedMessage,
                timestamp = Instant.ofEpochMilli(eventObject.timeStamp),
            )

            EventBus.post(event)
        } catch (e: Exception) {
            addError("Failed to post log event to EventBus", e)
        }
    }
}
