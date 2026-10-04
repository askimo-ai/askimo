/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.executable

import java.io.File

/**
 * Runnable language implementation for Bash/sh scripts.
 *
 * - Single-line commands are pasted directly into the terminal.
 * - Multi-line scripts (or scripts with a shebang) are written to a temp
 *   file and executed via `bash /tmp/askimo_xxx.sh` so the whole script
 *   runs as a unit without line-by-line interpretation issues.
 */
object BashLanguage : RunnableLanguage(setOf("bash", "sh"), "bash") {

    override fun buildTerminalCommand(code: String): String {
        val trimmed = code.trimEnd('\n', '\r')
        val isMultiLine = trimmed.contains('\n')
        val hasShebang = trimmed.startsWith("#!")

        return if (isMultiLine || hasShebang) {
            val tmpFile = File.createTempFile("askimo_", ".sh").apply {
                writeText(code)
                setExecutable(true)
                deleteOnExit()
            }
            "bash ${tmpFile.absolutePath}"
        } else {
            trimmed
        }
    }
}
