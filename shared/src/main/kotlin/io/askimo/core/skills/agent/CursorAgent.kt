/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

import io.askimo.core.logging.logger
import io.askimo.core.util.ProcessBuilderExt
import java.io.BufferedWriter

/**
 * External agent implementation for [Cursor](https://cursor.com).
 *
 * Requires the user to be logged in to Cursor before use (`cursor` account login).
 * No API key injection — authentication is handled by the Cursor CLI itself.
 *
 * Invocation:
 * ```
 * agent --print --trust "<userInput>"
 * ```
 * - `userInput` is passed via `--print`.
 * - `systemPrompt` is written to stdin as ambient context.
 * - `--trust` auto-approves directory access without interactive prompts.
 */
class CursorAgent : ExternalAgentTemplate() {

    override val log = logger<CursorAgent>()

    override val id = "cursor"
    override val name = "Cursor"
    override val installUrl = "https://cursor.com/downloads"

    override val commands: List<AgentCommand> = listOf(
        AgentCommand(
            name = "/help",
            description = "Show available Cursor agent commands",
            usage = "/help",
        ),
        AgentCommand(
            name = "/review",
            description = "Review code in the working directory",
            usage = "/review",
        ),
    )

    override val requiresApiKey = false

    override val configurationHint =
        "Log in to Cursor first (open Cursor and sign in), then return here."

    // ── Binary resolution ──────────────────────────────────────────────────

    override fun resolveAgentPath(): String? = runCatching {
        val proc = ProcessBuilderExt("which", "agent")
            .redirectErrorStream(true)
            .start()
        val path = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && path.isNotBlank()) path else null
    }.getOrNull()

    // ── Command construction ────────────────────────────────────────────────

    override fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: java.io.File,
    ): List<String> = buildList {
        add(agentPath)
        add("--print")
        add("--trust")
        if (userInput.isNotBlank()) add(userInput.trim())
    }

    // ── Stdin writing ──────────────────────────────────────────────────────

    override fun writeStdin(
        writer: BufferedWriter,
        systemPrompt: String,
        userInput: String,
    ) {
        if (systemPrompt.isNotBlank()) {
            writer.write(systemPrompt.trim())
            writer.newLine()
        }
    }

    // ── Stdout parsing ─────────────────────────────────────────────────────

    override fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
        onStatus: (String) -> Unit,
        output: StringBuilder,
    ) {
        // Simple line-by-line streaming; no special parsing
        log.debug("Cursor output: $line")
        output.appendLine(line)
        onToken(line + "\n")
    }
}
