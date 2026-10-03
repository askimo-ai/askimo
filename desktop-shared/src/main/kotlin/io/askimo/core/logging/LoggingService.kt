/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import co.touchlab.kermit.Severity
import io.askimo.core.util.AskimoHome
import org.slf4j.LoggerFactory
import java.nio.file.Path
import co.touchlab.kermit.Logger as KermitLogger

/**
 * Desktop-specific LoggingService that integrates Kermit with Logback.
 * Used only by Desktop applications that can use logback-classic.
 *
 * Manages log levels for both Kermit and Logback loggers dynamically at runtime.
 * File logging is configured via logback.xml and can be reconfigured dynamically.
 */
object LoggingService {
    private val log = io.askimo.core.logging.Logger("LoggingService")
    private var currentLogLevel: LogLevel = LogLevel.INFO
    private var logDirectory: Path? = null

    /**
     * Initializes the logging service.
     * Should be called early in application startup, after AskimoHome is registered.
     *
     * @param logsDir Path to the directory where logs should be written.
     *                Defaults to AskimoHome.logsDir() if not specified.
     */
    fun initialize(logsDir: Path? = null) {
        val resolvedLogsDir = logsDir ?: AskimoHome.logsDir()
        logDirectory = resolvedLogsDir
        resolvedLogsDir.toFile().mkdirs()

        log.info("Logging service initialized. Log directory: $resolvedLogsDir")
    }

    /**
     * Updates the log level for io.askimo loggers.
     * Affects both Kermit and Logback logging.
     *
     * @param level The desired log level
     */
    fun updateLogLevel(level: LogLevel) {
        currentLogLevel = level

        // Update Kermit's log level
        val kermitSeverity = when (level) {
            LogLevel.TRACE -> Severity.Verbose
            LogLevel.DEBUG -> Severity.Debug
            LogLevel.INFO -> Severity.Info
            LogLevel.WARN -> Severity.Warn
            LogLevel.ERROR -> Severity.Error
        }

        try {
            KermitLogger.setMinSeverity(kermitSeverity)
        } catch (_: Exception) {
            // Kermit 2.2.0 might not have setMinSeverity
            System.err.println("Note: Could not set Kermit severity")
        }

        // Update Logback's log level as well (more reliable for JVM)
        updateLogbackLevel(level)

        log.info("Log level updated to: ${level.name}")
    }

    /**
     * Updates the logback logger level dynamically.
     */
    private fun updateLogbackLevel(level: LogLevel) {
        val logbackLevel = when (level) {
            LogLevel.TRACE -> Level.TRACE
            LogLevel.DEBUG -> Level.DEBUG
            LogLevel.INFO -> Level.INFO
            LogLevel.WARN -> Level.WARN
            LogLevel.ERROR -> Level.ERROR
        }

        try {
            val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
            val askimoLogger = loggerContext.getLogger("io.askimo")
            askimoLogger.level = logbackLevel
        } catch (_: Exception) {
            System.err.println("Failed to update logback level")
        }
    }

    /**
     * Gets the current log level.
     *
     * @return The current log level
     */
    fun getCurrentLogLevel(): LogLevel = currentLogLevel

    /**
     * Gets the path to the current log file.
     * Returns the path to the daily log file.
     * Uses AskimoHome.logsDir() if not yet initialized.
     *
     * @return Path to the log file
     */
    fun getLogFilePath(): Path? = try {
        val dir = logDirectory ?: AskimoHome.logsDir()
        dir.resolve("askimo-desktop.log")
    } catch (_: Exception) {
        null
    }

    /**
     * Gets the log directory path.
     * Aligns with AskimoHome.logsDir().
     *
     * @return Path to the log directory
     */
    fun getLogDirectory(): Path = logDirectory ?: AskimoHome.logsDir()
}
