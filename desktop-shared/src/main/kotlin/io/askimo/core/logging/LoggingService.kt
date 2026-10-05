/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.logging

import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import co.touchlab.kermit.platformLogWriter
import io.askimo.core.util.AskimoHome
import java.nio.file.Path
import co.touchlab.kermit.Logger as KermitLogger
import kotlinx.io.files.Path as KxPath

/**
 * Desktop-specific LoggingService that integrates Kermit with Logback.
 * Used only by Desktop applications that can use logback-classic.
 *
 * Manages log levels for both Kermit and Logback loggers dynamically at runtime.
 * File logging is configured via logback.xml and can be reconfigured dynamically.
 */
object LoggingService {
    private val log = Logger("LoggingService")
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

        val fileConfig = RollingFileLogWriterConfig(
            logFileName = "askimo-desktop",
            logFilePath = KxPath(resolvedLogsDir.toString()),
        )

        val fileWriter = RollingFileLogWriter(config = fileConfig)

        KermitLogger.setLogWriters(
            platformLogWriter(), // Keeps Logcat (Android) / OSLog (iOS) / Console (JS) active
            fileWriter,
        )

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

        KermitLogger.setMinSeverity(kermitSeverity)

        log.info("Log level updated to: ${level.name}")
    }

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
