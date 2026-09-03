/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.context.AppContext
import io.askimo.core.logging.logger
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.openai.OpenAiSettings
import io.askimo.core.security.SecureKeyManager
import io.askimo.core.util.ProcessBuilderExt
import java.io.BufferedWriter
import java.io.File

/**
 * External agent implementation for [OpenAI Codex CLI](https://github.com/openai/codex).
 *
 * Install: `npm install -g @openai/codex`
 * Requires an OpenAI API key (`OPENAI_API_KEY`).
 *
 * Invocation:
 * ```
 * codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check \
 *            -C <workDir> -
 * ```
 * The combined system prompt + user input is written to **stdin** (triggered by the `-` prompt arg).
 * - `exec`                                         Non-interactive subcommand.
 * - `--dangerously-bypass-approvals-and-sandbox`   Auto-approve all tool actions, no sandbox.
 * - `--skip-git-repo-check`                        Allow running outside a git repo.
 * - `-C <workDir>`                                 Set the agent working directory.
 * - `-`                                            Read prompt from stdin.
 *
 * Session files are persisted on disk (no `--ephemeral`) so a follow-up turn can be
 * continued via `codex exec resume <SESSION_ID>` instead of Askimo replaying prior turns.
 */
class CodexAgent : ExternalAgentTemplate() {

    override val log = logger<CodexAgent>()

    override val id = "codex"
    override val name = "Codex (OpenAI)"
    override val installUrl = "https://github.com/openai/codex"
    override val requiresApiKey = true

    override val commands: List<AgentCommand> = listOf(
        AgentCommand(
            name = "/help",
            description = "Show available Codex commands",
            usage = "/help",
        ),
    )

    /**
     * Resolves the OpenAI API key from:
     * 1. AppContext OpenAiSettings (if initialized) — handles keychain/encrypted refs
     * 2. SecureKeyManager direct lookup by provider key "openai"
     */
    private fun resolveApiKey(): String? {
        runCatching {
            val ctx = AppContext.getInstance()
            val settings = ctx.getOrCreateProviderSettings(ModelProvider.OPENAI)
            if (settings is OpenAiSettings) {
                val raw = settings.apiKey
                if (raw.isNotBlank() && raw != "***keychain***" && !raw.startsWith("encrypted:")) {
                    return raw
                }
            }
        }
        return SecureKeyManager.retrieveSecretKey(ModelProvider.OPENAI.providerKey())
    }

    override fun resolveAgentPath(): String? = ProcessBuilderExt.which("codex")

    override fun isConfigured(): Boolean {
        if (!super.isBinaryAvailable()) return false
        val hasKey = resolveApiKey()?.isNotBlank() == true
        if (!hasKey) log.debug("codex CLI found but no OPENAI_API_KEY configured")
        return hasKey
    }

    /**
     * Stores [key] securely and syncs it to AppContext OpenAiSettings so both the
     * Skills executor and the chat provider share the same key without re-entry.
     */
    override fun saveApiKey(key: String) {
        if (key.isBlank()) return
        SecureKeyManager.storeSecuredKey(ModelProvider.OPENAI.providerKey(), key)
        runCatching {
            val ctx = AppContext.getInstance()
            val settings = ctx.getOrCreateProviderSettings(ModelProvider.OPENAI)
            if (settings is OpenAiSettings) {
                settings.apiKey = key
            }
        }
        log.debug("OpenAI API key saved and synced to provider settings")
    }

    override fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
        resumeSessionId: String?,
    ): List<String> = buildList {
        add(agentPath)
        add("exec")
        // Codex keeps its own rollout/session store; `resume <id>` continues it instead
        // of Askimo replaying prior turns itself.
        // TODO: verify exact subcommand/flag against the installed Codex CLI version.
        if (!resumeSessionId.isNullOrBlank()) {
            add("resume")
            add(resumeSessionId)
        }
        add("--dangerously-bypass-approvals-and-sandbox")
        add("--skip-git-repo-check")
        add("-C")
        add(effectiveWorkDir.absolutePath)
        add("-") // read prompt from stdin
    }

    override fun configureProcess(
        builder: ProcessBuilderExt,
        requestedWorkDir: File?,
        effectiveWorkDir: File,
        systemPrompt: String,
        userInput: String,
    ) {
        resolveApiKey()?.takeIf { it.isNotBlank() }?.let { key ->
            log.debug("Injecting OPENAI_API_KEY from Askimo provider settings")
            builder.environment()["OPENAI_API_KEY"] = key
        }
    }

    override fun writeStdin(
        writer: BufferedWriter,
        systemPrompt: String,
        userInput: String,
    ) {
        if (systemPrompt.isNotBlank()) {
            writer.write(systemPrompt.trim())
            writer.write("\n\n---\n\n")
        }
        if (userInput.isNotBlank()) {
            writer.write(userInput.trim())
        }
    }

    override fun onStderrLine(line: String, onStatus: (String) -> Unit) {
        captureSessionId(line)
        if (line.isNotBlank()) onStatus(line)
    }

    override fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
        onToolCall: (toolName: String, detail: String?) -> Unit,
        onStatus: (String) -> Unit,
        onThinking: (String) -> Unit,
        output: StringBuilder,
    ) {
        // TODO: Codex prints raw stdout lines rather than structured tool-call events —
        // no reliable way yet to distinguish an actual tool invocation from plain text, so
        // everything still streams via onToken. Revisit if Codex CLI adds structured events.
        captureSessionId(line)
        output.appendLine(line)
        onToken(line + "\n")
    }

    /**
     * Best-effort extraction of the rollout/session id Codex prints at the start of a run
     * (e.g. `session id: <uuid>`), so a follow-up turn can resume this same conversation via
     * `codex exec resume <id>` instead of Askimo replaying prior turns itself.
     * TODO: verify the exact line format against the installed Codex CLI version.
     */
    private fun captureSessionId(line: String) {
        val match = SESSION_ID_PATTERN.find(line) ?: return
        updateExecutionMetadata(sessionId = match.groupValues[1])
    }

    companion object {
        private val SESSION_ID_PATTERN = Regex(
            """session[\s_-]?id[:=]\s*([0-9a-fA-F-]{8,})""",
            RegexOption.IGNORE_CASE,
        )
    }
}
