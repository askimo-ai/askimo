/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.logging

import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import io.askimo.core.util.AskimoHome
import co.touchlab.kermit.Logger as KermitLogger
import kotlinx.io.files.Path as KxPath

/**
 * Configures Kermit (the backend behind [io.askimo.core.logging.Logger]) for the CLI.
 */
fun initializeCliLogging() {
    val logsDir = AskimoHome.logsDir()
    logsDir.toFile().mkdirs()

    val fileWriter = RollingFileLogWriter(
        config = RollingFileLogWriterConfig(
            logFileName = "askimo-cli",
            logFilePath = KxPath(logsDir.toString()),
        ),
    )

    // Intentionally no platformLogWriter() — console output must stay clean for CLI users.
    KermitLogger.setLogWriters(fileWriter)
}
