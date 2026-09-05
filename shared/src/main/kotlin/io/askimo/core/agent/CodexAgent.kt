/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
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
 * codex -C <workDir> exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check \
 *                          --json -
 * ```
 * The combined system prompt + user input is written to **stdin** (triggered by the `-` prompt arg).
 * - `-C <workDir>`                                 Set the agent working directory — a *global*
 *                                                   flag; must precede `exec`, or Codex's arg
 *                                                   parser rejects it ("unexpected argument '-C'").
 * - `exec`                                         Non-interactive subcommand.
 * - `--dangerously-bypass-approvals-and-sandbox`   Auto-approve all tool actions, no sandbox.
 * - `--skip-git-repo-check`                        Allow running outside a git repo.
 * - `--json`                                       Emit structured JSONL events on stdout (see
 *                                                   [CodexStreamJsonEventParser]) instead of the
 *                                                   human-readable transcript Codex otherwise prints.
 * - `-`                                            Read prompt from stdin.
 *
 * Session files are persisted on disk (no `--ephemeral`) so a follow-up turn can be
 * continued via `codex exec resume <THREAD_ID>` instead of Askimo replaying prior turns —
 * `THREAD_ID` is the id captured from this agent's `thread.started` event (see
 * `parseStdoutLine`), not a separately-issued "session id".
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

    // NOT relied upon: although Codex documents REPO-scoped `.agents/skills` discovery
    // (`$CWD/.agents/skills`, `$CWD/../.agents/skills`, `$REPO_ROOT/.agents/skills`), that
    // discovery was confirmed unreliable outside a git-tracked working directory — a
    // materialized skill in a non-git workdir went completely unseen by the model even
    // though the file was copied correctly (see `materializeSkill` below). Keeping this
    // `false` guarantees `buildAgenticSystemPrompt` always inlines the full skill
    // catalog/content directly into the prompt sent to Codex, so it works regardless of
    // whether the workspace is a git repo or which Codex version is installed.
    override val supportsNativeSkillDiscovery = false

    /**
     * Materializes [skill] into `<workDir>/.agents/skills/<folder-name>/` — the REPO-scoped
     * `$CWD/.agents/skills` location from Codex's documented skill-discovery spec. Kept as a
     * best-effort extra (harmless, and helps if `workDir` happens to be a git repo where this
     * scope is confirmed to work) — but per [supportsNativeSkillDiscovery] above, the full
     * skill content is *always* also inlined directly into the system prompt, since this
     * folder's discovery is not reliable for non-git workspaces.
     *
     * Uses a copy (see [ExternalAgentTemplate.materializeSkillFolder]) rather than a symlink
     * since symlink support isn't confirmed for Codex the way it is for Antigravity.
     */
    override fun materializeSkill(skill: SkillDefinition, workDir: File): AutoCloseable = materializeSkillFolder(skill, workDir.toPath().resolve(".agents").resolve("skills"))

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
        // `-C`/`--cd` is a *global* flag — Codex's clap-based arg parser rejects it if placed
        // after the `exec` subcommand ("unexpected argument '-C' found"), so it must come
        // before `exec` here, not alongside the other `exec`-specific flags below.
        add("-C")
        add(effectiveWorkDir.absolutePath)
        add("exec")
        // Codex keeps its own rollout/thread store; `resume <thread_id>` (captured from this
        // agent's `thread.started` event, see `parseStdoutLine`) continues it instead of Askimo
        // replaying prior turns itself.
        // TODO: verify exact subcommand/flag against the installed Codex CLI version.
        if (!resumeSessionId.isNullOrBlank()) {
            add("resume")
            add(resumeSessionId)
        }
        add("--dangerously-bypass-approvals-and-sandbox")
        add("--skip-git-repo-check")
        // Emits structured JSONL events on stdout — `thread.started`/`turn.*` lifecycle events
        // plus `item.started`/`item.completed` (wrapping an `agent_message`, `command_execution`,
        // etc. — see `CodexStreamJsonEventParser`) — instead of the human-readable transcript
        // Codex otherwise prints, so `parseStdoutLine` can distinguish tool calls from plain
        // response text instead of streaming every line as raw token text.
        add("--json")
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
        // With `--json`, the full transcript (tool calls, assistant messages, etc.) is on
        // stdout as structured events — stderr should now only carry the startup banner and
        // genuine warnings/errors, so it's safe (and much less noisy) to just surface it as-is.
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
        val event = CodexStreamJsonEventParser.parse(line)
        if (event == null) {
            log.debug("codex unparseable line: {}", line)
            return
        }
        log.debug("codex event: type={} line {}", event.type, line)
        when (event.type) {
            "thread.started" -> {
                val threadId = event.fields["thread_id"] as? String
                if (!threadId.isNullOrBlank()) updateExecutionMetadata(sessionId = threadId)
                onStatus("codex thread started")
            }

            "turn.started" -> Unit

            // pure lifecycle marker — nothing to surface

            "turn.completed" -> {
                @Suppress("UNCHECKED_CAST")
                val usage = event.fields["usage"] as? Map<String, Any>
                if (usage != null) updateExecutionUsage(AgentUsageExtractor.extract(event.fields, usage))
                onStatus("codex turn complete")
            }

            "turn.failed" -> {
                @Suppress("UNCHECKED_CAST")
                val error = event.fields["error"] as? Map<String, Any>
                val message = error?.get("message") as? String ?: "Codex turn failed"
                onStatus("result: error | $message")
                reportResultError(message)
            }

            // The item's own `type` field (not the envelope's `type`) discriminates what kind
            // of item this is — see [handleCompletedItem].
            "item.completed" -> handleCompletedItem(
                CodexStreamJsonEventParser.item(event.fields),
                onToken,
                onToolCall,
                onThinking,
                output,
            )

            // `item.started` only ever precedes a `command_execution` item with no output yet
            // (`aggregated_output` empty, `exit_code` null) — nothing worth surfacing mid-flight;
            // the matching `item.completed` carries the full command + output and is handled above.
            "item.started" -> Unit

            "error" -> {
                val message = event.fields["message"] as? String ?: "Codex reported an error"
                onStatus("result: error | $message")
                reportResultError(message)
            }

            else -> log.debug("codex unhandled event type: {}", event.type)
        }
    }

    /**
     * Handles a completed `item` payload from an `item.completed` event — see
     * [CodexStreamJsonEventParser] for the envelope shape. `item["type"]` (not the outer event's
     * `type`) discriminates what kind of item this is:
     * - `agent_message` — the model's response text (`text`) — surfaced as response tokens.
     * - `command_execution` — a shell command Codex ran (`command`, `exit_code`,
     *   `aggregated_output`) — surfaced as a tool call.
     * - `reasoning` — visible chain-of-thought text (`text`), unconfirmed against a real run but
     *   handled defensively per Codex's documented protocol shape.
     * - `mcp_tool_call` — an MCP tool invocation, unconfirmed against a real run but handled
     *   defensively the same way.
     */
    private fun handleCompletedItem(
        item: Map<String, Any>?,
        onToken: (String) -> Unit,
        onToolCall: (toolName: String, detail: String?) -> Unit,
        onThinking: (String) -> Unit,
        output: StringBuilder,
    ) {
        if (item == null) return
        when (item["type"] as? String) {
            "agent_message" -> {
                val text = item["text"] as? String
                if (!text.isNullOrBlank()) {
                    output.append(text)
                    onToken(text)
                }
            }

            "reasoning" -> {
                val text = item["text"] as? String
                if (!text.isNullOrBlank()) onThinking(text)
            }

            "command_execution" -> {
                val command = item["command"] as? String
                val exitCode = item["exit_code"]
                val detail = buildString {
                    append(command ?: "")
                    if (exitCode != null) append(" (exit $exitCode)")
                }.take(ExternalAgent.TOOL_DETAIL_MAX_LENGTH)
                onToolCall("exec", detail)
            }

            "mcp_tool_call" -> {
                val toolName = item["tool"] as? String ?: item["name"] as? String ?: "mcp_tool"
                val args = item["arguments"] ?: item["args"]
                onToolCall(toolName, args?.toString()?.take(ExternalAgent.TOOL_DETAIL_MAX_LENGTH))
            }

            else -> log.debug("codex unhandled item type: {}", item["type"])
        }
    }
}
