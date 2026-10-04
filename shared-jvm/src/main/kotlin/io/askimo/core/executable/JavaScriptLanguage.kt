/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.executable

import java.io.File

/**
 * Runnable language implementation for JavaScript/Node.js scripts.
 *
 * - Single-line expressions are evaluated via `node -e "..."`.
 * - Multi-line scripts are written to a temp file and executed via
 *   `node /tmp/askimo_xxx.js` so the whole script runs as a unit.
 */
object JavaScriptLanguage : RunnableLanguage(setOf("js", "javascript", "node"), "node") {

    override fun buildTerminalCommand(code: String): String {
        val trimmed = code.trimEnd('\n', '\r')
        val isMultiLine = trimmed.contains('\n')

        return if (isMultiLine) {
            val tmpFile = File.createTempFile("askimo_", ".js").apply {
                writeText(code)
                deleteOnExit()
            }
            "node ${tmpFile.absolutePath}"
        } else {
            // Escape backslashes and double quotes for inline eval
            val escaped = trimmed.replace("\\", "\\\\").replace("\"", "\\\"")
            "node -e \"$escaped\""
        }
    }
}
