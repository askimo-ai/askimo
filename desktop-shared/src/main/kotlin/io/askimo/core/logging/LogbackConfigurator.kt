/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Utility to dynamically register/unregister EventBusAppender to logback at runtime,
 * and to reconfigure the FILE appender log directory after AskimoHome is registered.
 */
object LogbackConfigurator {

    /**
     * Reconfigures the FILE RollingFileAppender to write logs under [logsDir].
     *
     * Must be called after [io.askimo.core.util.AskimoHome.register] so the correct
     * home path is known. Logback resolves XML property substitutions at parse time
     * (before Kotlin startup code runs), so environment variables like ASKIMO_HOME
     * are not reliably available in logback.xml — this method is the authoritative fix.
     */
    fun configureLogDirectory(logsDir: Path) {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        logsDir.toFile().mkdirs()

        // The FILE appender is attached to the "io.askimo" logger (see logback.xml)
        val askimoLogger = loggerContext.getLogger("io.askimo")

        @Suppress("UNCHECKED_CAST")
        val fileAppender = askimoLogger.getAppender("FILE") as? RollingFileAppender<ILoggingEvent> ?: return

        fileAppender.stop()

        fileAppender.file = logsDir.resolve("askimo-desktop.log").toString()

        @Suppress("UNCHECKED_CAST")
        val rollingPolicy = fileAppender.rollingPolicy as? TimeBasedRollingPolicy<ILoggingEvent>
        if (rollingPolicy != null) {
            rollingPolicy.stop()
            rollingPolicy.fileNamePattern = logsDir.resolve("askimo-desktop.%d{yyyy-MM-dd}.log").toString()
            rollingPolicy.start()
        }

        fileAppender.start()
    }

    /**
     * Registers EventBusAppender to the "io.askimo" logger.
     * Should be called when developer mode is enabled and active.
     */
    fun registerEventBusAppender() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val askimoLogger = loggerContext.getLogger("io.askimo")

        // Check if already registered
        if (askimoLogger.getAppender("EVENTBUS") != null) {
            return
        }

        val appender = EventBusAppender().apply {
            name = "EVENTBUS"
            context = loggerContext
            start()
        }

        askimoLogger.addAppender(appender)
    }

    /**
     * Unregisters EventBusAppender from the "io.askimo" logger.
     * Should be called when developer mode is turned off.
     */
    fun unregisterEventBusAppender() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val askimoLogger = loggerContext.getLogger("io.askimo")

        val appender = askimoLogger.getAppender("EVENTBUS")
        if (appender != null) {
            askimoLogger.detachAppender(appender)
            appender.stop()
        }
    }
}
