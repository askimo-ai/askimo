/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.commands

import org.jline.reader.ParsedLine

/**
 * Documents the exit command for `:help`. Actual exit is handled earlier in
 * ChatCli's REPL loop (intercepts "exit"/"quit"/":exit"/":quit" before dispatch).
 */
class ExitCommandHandler : CommandHandler {
    override val keyword = ":exit"
    override val description = "Exit the interactive session (aliases: exit, quit, :quit)"

    override fun handle(line: ParsedLine) {
        // No-op: handled directly in ChatCli's main loop.
    }
}
