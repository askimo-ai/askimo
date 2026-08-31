/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.logging.logger
import io.askimo.core.util.ProcessBuilderExt
import java.io.BufferedWriter
import java.io.File

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

    override fun resolveAgentPath(): String? = ProcessBuilderExt.which("agent")

    // ── Command construction ────────────────────────────────────────────────

    override fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
        resumeSessionId: String?,
    ): List<String> = buildList {
        add(agentPath)
        add("--print")
        add("--trust")
        add("--workspace")
        add(effectiveWorkDir.absolutePath)
        add("--stream-partial-output")
        add("--output-format")
        add("stream-json")
        // Cursor CLI keeps its own chat/session store; `--resume` continues it instead
        // of Askimo replaying prior turns itself.
        // TODO: verify exact flag name against the installed Cursor CLI version.
        if (!resumeSessionId.isNullOrBlank()) {
            add("--resume")
            add(resumeSessionId)
        }
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
        onThinking: (String) -> Unit,
        output: StringBuilder,
    ) {
        val event = CursorStreamJsonEventParser.parse(line)
        if (event == null) {
            log.debug("cursor unparseable line: {}", line)
            return
        }
        log.debug("cursor event: type={} subtype={} line {}", event.type, event.subtype, line)

        when (event.type) {
            "system" -> {
                // First init event carries stable session/workspace metadata.
                if (event.subtype == "init") {
                    val sessionId = event.fields["session_id"] as? String
                    val cwd = event.fields["cwd"] as? String
                    if (!sessionId.isNullOrBlank() || !cwd.isNullOrBlank()) {
                        updateExecutionMetadata(sessionId = sessionId, workspaceDir = cwd)
                    }
                }
                onStatus(CursorStreamJsonEventParser.renderStatus(event))
            }

            "thinking" -> {
                if (event.content.isNotBlank()) {
                    onThinking(event.content)
                } else {
                    onStatus(CursorStreamJsonEventParser.renderStatus(event))
                }
            }

            "assistant" -> {
                // Cursor emits incremental tokens here but also a final summary event that
                // duplicates all of them. We skip streaming from assistant entirely and rely
                // on the "result" event for the final output.
            }

            "result" -> {
                if (!event.isError) {
                    val resultText = event.fields["result"] as? String
                    if (!resultText.isNullOrBlank()) {
                        output.append(resultText)
                        onToken(resultText)
                    }
                }
                // Cursor's usage shape is unconfirmed — best-effort extraction via the shared
                // generic extractor; verify/correct key names against real CLI output.
                @Suppress("UNCHECKED_CAST")
                val usage = event.fields["usage"] as? Map<String, Any>
                updateExecutionUsage(AgentUsageExtractor.extract(event.fields, usage))
                onStatus(CursorStreamJsonEventParser.renderStatus(event))
            }

            else -> {
                // Unknown event type — show as status
                onStatus(CursorStreamJsonEventParser.renderStatus(event))
            }
        }
    }
}
