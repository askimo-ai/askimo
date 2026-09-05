/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.context.AppContext
import io.askimo.core.logging.logger
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.gemini.GeminiSettings
import io.askimo.core.security.SecureKeyManager
import io.askimo.core.util.ProcessBuilderExt
import java.io.BufferedWriter
import java.io.File

/**
 * External agent implementation for the
 * [Antigravity CLI](https://antigravity.google) (`agy`)
 */
class AntigravityAgent : ExternalAgentTemplate() {

    override val log = logger<AntigravityAgent>()

    override val id = "antigravity"
    override val name = "Antigravity CLI"
    override val installUrl = "https://antigravity.google"

    override val supportsNativeSkillDiscovery = true

    /**
     * Materializes [skill] into `<workDir>/.agents/skills/<folder-name>/` via a symbolic
     * link — Antigravity's workspace-scoped skill location per its docs ("Where skills
     * live"): `<workspace-root>/.agents/skills/<skill-folder>/` (Antigravity also supports
     * a global `~/.gemini/config/skills/` location and the legacy `.agent/skills` path, but
     * workspace scope mirrors Claude Code's per-workspace materialization and avoids
     * polluting the user's global skill folder with Askimo-managed, run-scoped skills).
     *
     * Uses a symlink rather than copying files (see [ExternalAgentTemplate.materializeSkillFolder])
     * since Antigravity is confirmed to follow symlinked skill folders — cheaper than copying
     * and trivial to clean up (a single link deletion).
     */
    override fun materializeSkill(skill: SkillDefinition, workDir: File): AutoCloseable = materializeSkillSymlink(skill, workDir.toPath().resolve(".agents").resolve("skills"))

    /**
     * Resolves the Google/Gemini API key from:
     * 1. AppContext GeminiSettings (if initialized) — handles keychain/encrypted refs
     * 2. SecureKeyManager direct lookup by provider key "gemini"
     * Returns null if no key is configured (user may rely on OAuth login instead).
     */
    private fun resolveApiKey(): String? {
        // Try AppContext first (handles keychain placeholder + encrypted prefix)
        runCatching {
            val ctx = AppContext.getInstance()
            val settings = ctx.getOrCreateProviderSettings(ModelProvider.GEMINI)
            if (settings is GeminiSettings) {
                val raw = settings.apiKey
                if (raw.isNotBlank() && raw != "***keychain***" && !raw.startsWith("encrypted:")) {
                    return raw
                }
            }
        }
        // Fall back to secure key manager using provider key name "gemini"
        return SecureKeyManager.retrieveSecretKey(ModelProvider.GEMINI.name.lowercase())
    }

    /**
     * Resolves the absolute path to the `agy` executable on `PATH`.
     * Returns null if not found.
     */
    override fun resolveAgentPath(): String? = ProcessBuilderExt.which("agy")

    override val requiresApiKey = true

    override fun isConfigured(): Boolean {
        if (!super.isBinaryAvailable()) return false
        val hasKey = resolveApiKey()?.isNotBlank() == true
        if (!hasKey) log.debug("antigravity CLI found but no GEMINI_API_KEY configured")
        return hasKey
    }

    /**
     * Stores [key] securely and syncs it to AppContext GeminiSettings so both the
     * Skills executor and the chat provider share the same key without re-entry.
     */
    override fun saveApiKey(key: String) {
        if (key.isBlank()) return
        SecureKeyManager.storeSecuredKey(ModelProvider.GEMINI.name.lowercase(), key)
        // Sync to AppContext so the chat Gemini provider picks it up in the same session
        runCatching {
            val ctx = AppContext.getInstance()
            val settings = ctx.getOrCreateProviderSettings(ModelProvider.GEMINI)
            if (settings is GeminiSettings) {
                settings.apiKey = key
            }
        }
        log.debug("Gemini API key saved and synced to provider settings")
    }

    override fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
        resumeSessionId: String?,
    ): List<String> {
        val promptArg = userInput.ifBlank { " " }
        return buildList {
            add(agentPath)
            add("--print")
            add(promptArg)
            add("--output-format")
            add("stream-json")
            add("--dangerously-skip-permissions")
            add("--add-dir")
            add(effectiveWorkDir.absolutePath)
            // `agy` keeps its own conversation store keyed by conversation id; continuing
            // it instead of Askimo replaying prior turns itself.
            // TODO: verify exact flag name against the installed Antigravity CLI version.
            if (!resumeSessionId.isNullOrBlank()) {
                add("--conversation")
                add(resumeSessionId)
            }
        }
    }

    override fun configureProcess(
        builder: ProcessBuilderExt,
        requestedWorkDir: File?,
        effectiveWorkDir: File,
        systemPrompt: String,
        userInput: String,
    ) {
        loadDotEnv(requestedWorkDir)?.forEach { (k, v) -> builder.environment()[k] = v }
        resolveApiKey()?.takeIf { it.isNotBlank() }?.let { key ->
            log.debug("Injecting GEMINI_API_KEY from Askimo provider settings")
            builder.environment()["GEMINI_API_KEY"] = key
        }
    }

    override fun writeStdin(
        writer: BufferedWriter,
        systemPrompt: String,
        userInput: String,
    ) {
        writer.write(buildStdin(systemPrompt))
    }

    override fun filterErrorStderr(stderr: String): String = stderr
        .lines()
        .filter { line -> STDERR_NOISE_PATTERNS.none { line.contains(it) } }
        .joinToString("\n")
        .trim()

    override fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
        onToolCall: (toolName: String, detail: String?) -> Unit,
        onStatus: (String) -> Unit,
        onThinking: (String) -> Unit,
        output: StringBuilder,
    ) {
        val event = AntigravityStreamJsonEventParser.parse(line)
        if (event == null) {
            log.debug("antigravity unparseable line: {}", line)
            return
        }
        log.debug("antigravity event: type={}, line {}", event.type, line)
        when (event.type) {
            "init" -> {
                // Session metadata (cwd, available tools, permission mode) — capture the
                // conversation id for history/session tracking; nothing to show the user.
                val conversationId = event.fields["conversation_id"] as? String
                if (conversationId != null) updateExecutionMetadata(sessionId = conversationId)
            }

            "step_update" -> {
                val stepType = event.fields["step_type"] as? String
                val state = event.fields["state"] as? String
                // Some `agy` versions stream incremental text under `text_delta`; others emit
                // the full text for the step in one shot under `text` or `response` once
                // `state == DONE`. Check all known variants so we don't drop content.
                val textDelta = event.fields["text_delta"] as? String
                    ?: event.fields["text"] as? String
                    ?: event.fields["response"] as? String

                when {
                    !textDelta.isNullOrEmpty() -> {
                        output.append(textDelta)
                        onToken(textDelta)
                    }

                    // "user_input DONE" is just an ack of our own prompt — nothing to surface.
                    stepType == "user_input" -> Unit

                    // A lifecycle update for the final answer step with no text yet (e.g. the
                    // initial RUNNING state before any delta arrives) — status line only.
                    stepType == "agent_response" -> {
                        onStatus(if (state != null) "$stepType ($state)" else stepType)
                    }

                    // Any other `step_type` corresponds to an actual tool invocation (e.g.
                    // `run_command`, `grep_search`, `view_file` — matching the tool names
                    // Antigravity reports in its `init` event's `tools` list). Route it through
                    // `onToolCall` (and thus the dedicated tool-calls UI section) instead of a
                    // generic status line, which previously collapsed every tool step down to
                    // just its last "(DONE)" state and hid the tool activity entirely.
                    stepType != null -> {
                        val detail = formatToolArgs(
                            event.fields.filterKeys { it !in setOf("step_type", "state", "step_index") },
                        ).take(ExternalAgent.TOOL_DETAIL_MAX_LENGTH)
                        onToolCall(stepType, detail.ifBlank { null })
                    }

                    state != null -> onStatus(state)
                }
            }

            "result" -> {
                val status = event.fields["status"] as? String ?: "done"
                val response = event.fields["response"] as? String

                @Suppress("UNCHECKED_CAST")
                val usage = event.fields["usage"] as? Map<String, Any>
                val totalTokens = usage?.get("total_tokens")
                val durationSeconds = event.fields["duration_seconds"]
                updateExecutionUsage(AgentUsageExtractor.extract(event.fields, usage))

                // `agy` doesn't always stream the answer via `step_update.text_delta` — when
                // no step_update carried any text, the full answer is only delivered here in
                // `result.response`. Surface it as the final token so the reply isn't dropped.
                if (!response.isNullOrEmpty() && output.isEmpty()) {
                    output.append(response)
                    onToken(response)
                }

                val summary = buildString {
                    append("result: $status")
                    if (totalTokens != null) append(" | tokens: $totalTokens")
                    if (durationSeconds != null) {
                        val secs = durationSeconds.toString().toDoubleOrNull() ?: 0.0
                        append(" | duration: ${"%.1f".format(secs)}s")
                    }
                }
                onStatus(summary)
            }

            else -> onStatus(AntigravityStreamJsonEventParser.render(event))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Renders a tool call's `args`/`arguments` payload (per the Antigravity hooks doc,
     * `toolCall.args` is a JSON object keyed by tool-specific parameter names such as
     * `CommandLine`, `TargetFile`, `Query`, etc.) into a compact single-line summary for
     * display next to the tool name.
     */
    private fun formatToolArgs(args: Any): String = when (args) {
        is Map<*, *> -> args.entries.joinToString(", ") { (k, v) ->
            val display = v?.toString()?.replace('\n', ' ')?.let {
                if (it.length > 80) it.take(77) + "…" else it
            } ?: "null"
            "$k=$display"
        }

        else -> args.toString()
    }

    private fun buildStdin(systemPrompt: String): String = buildString {
        if (systemPrompt.isNotBlank()) {
            append(systemPrompt.trim())
            append("\n\n---\n\n")
        }
    }

    /**
     * Reads key=value pairs from the first `.env` file found in:
     *   1. [workDir]
     *   2. User home (`~`)
     *   3. `~/.askimo/personal`
     *
     * Lines starting with `#` and blank lines are ignored.
     * Returns `null` if no `.env` file is found.
     */
    private fun loadDotEnv(workDir: File?): Map<String, String>? {
        val candidates = listOfNotNull(
            workDir?.resolve(".env"),
            File(System.getProperty("user.home"), ".env"),
            File(System.getProperty("user.home"), ".askimo/personal/.env"),
        )
        val envFile = candidates.firstOrNull { it.exists() && it.isFile } ?: return null
        log.debug("Loading .env from {}", envFile.absolutePath)
        return envFile.readLines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && it.contains("=") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            }
    }

    companion object {
        /**
         * Stderr lines containing these substrings are noise emitted by the Antigravity CLI
         * regardless of the actual response — filtered out when reporting errors.
         */
        private val STDERR_NOISE_PATTERNS = listOf(
            "256-color support not detected",
            "Ripgrep is not available",
            "Falling back to GrepTool",
        )
    }
}
