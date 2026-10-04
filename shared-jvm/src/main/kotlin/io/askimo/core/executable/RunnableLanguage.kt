/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.executable

import io.askimo.core.logging.logger
import io.askimo.core.util.ProcessBuilderExt

/**
 * Represents a code language that can be executed inline in the terminal.
 *
 * Each entry declares:
 *  - [aliases]    — fence identifiers that map to this language (e.g. "bash", "sh")
 *  - [executable] — the binary that must exist on PATH for the Run button to appear
 *
 * To add a new runnable language, add a new object and register it in [all].
 */
sealed class RunnableLanguage(
    val aliases: Set<String>,
    val executable: String,
) {
    /**
     * Builds the terminal command string to paste for the given [code].
     * Implementations may write temp files, prepend installers, etc.
     */
    abstract fun buildTerminalCommand(code: String): String

    companion object {
        private val log = logger<RunnableLanguage>()

        /** Registry of all supported runnable languages — add new entries here. */
        private val registered: List<RunnableLanguage> by lazy {
            listOf(BashLanguage, PythonLanguage, JavaScriptLanguage)
        }

        fun resolve(language: String?): RunnableLanguage? {
            if (language == null) return null
            val lang = registered.firstOrNull { it.aliases.contains(language.lowercase()) }
                ?: return null
            return if (isExecutableAvailable(lang.executable)) lang else null
        }

        private fun isExecutableAvailable(executable: String): Boolean = try {
            val whichCommand = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
            ProcessBuilderExt(whichCommand, executable)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readLine()
                .isNullOrBlank().not()
        } catch (_: Exception) {
            log.error("Error checking executable availability: $executable")
            false
        }
    }
}
