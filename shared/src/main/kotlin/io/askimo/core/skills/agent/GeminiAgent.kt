/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

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
 * [Gemini CLI](https://github.com/google-gemini/gemini-cli).
 *
 * Install: `npm install -g @google/gemini-cli`
 * Free tier: 60 requests/min, 1 000 requests/day with a Google account.
 *
 * Invocation:
 * ```
 * gemini -p "<userInput>" --output-format stream-json --yolo
 * ```
 * - `-p / --prompt`         Non-interactive (headless) mode; appended to stdin content.
 * - `--output-format stream-json` Newline-delimited JSON events streamed in real-time.
 * - `--yolo`                Auto-approve all tool actions (no interactive confirmation).
 *
 * The skill system prompt is written to **stdin** so it acts as ambient context
 * that Gemini receives before the `-p` user prompt.
 *
 * Prompt written to stdin:
 * ```
 * <systemPrompt>
 *
 * ---
 *
 * ```
 * Then `-p` carries the `userInput` (or a single space when blank so `-p` is always present).
 */
class GeminiAgent : ExternalAgentTemplate() {

    override val log = logger<GeminiAgent>()

    override val id = "gemini"
    override val name = "Gemini CLI"
    override val installUrl = "https://github.com/google-gemini/gemini-cli"

    override val commands: List<AgentCommand> = listOf(
        AgentCommand(
            name = "/tools",
            description = "Display available tools",
            usage = "/tools [desc|nodesc]",
            subCommands = listOf(
                AgentCommand("desc", "Show tool names with full descriptions"),
                AgentCommand("nodesc", "Show tool names only"),
            ),
        ),
        AgentCommand(
            name = "/permissions",
            description = "Manage folder trust settings",
            usage = "/permissions trust [<path>]",
            subCommands = listOf(
                AgentCommand("trust", "Trust a directory for file access"),
            ),
        ),
        AgentCommand(
            name = "/memory",
            description = "Manage Gemini memory",
            usage = "/memory [show|refresh|add]",
            subCommands = listOf(
                AgentCommand("show", "Show current memory contents"),
                AgentCommand("refresh", "Reload memory from disk"),
                AgentCommand("add", "Add a new memory entry"),
            ),
        ),
        AgentCommand(
            name = "/stats",
            description = "Show session token usage and costs",
            usage = "/stats",
        ),
        AgentCommand(
            name = "/quit",
            description = "Exit the Gemini CLI session",
            usage = "/quit",
        ),
    )

    /**
     * Resolves the Gemini API key from:
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
     * Resolves the absolute path to the `gemini` executable via `which gemini`.
     * Returns null if not found on PATH.
     */
    override fun resolveAgentPath(): String? = runCatching {
        val proc = ProcessBuilderExt("which", "gemini")
            .redirectErrorStream(true)
            .start()
        val path = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && path.isNotBlank()) path else null
    }.getOrNull()

    override val requiresApiKey = true

    override fun isConfigured(): Boolean {
        if (!super.isBinaryAvailable()) return false
        val hasKey = resolveApiKey()?.isNotBlank() == true
        if (!hasKey) log.debug("gemini CLI found but no GEMINI_API_KEY configured")
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
    ): List<String> {
        val promptArg = userInput.ifBlank { " " }
        return listOf(
            agentPath,
            "-p",
            promptArg,
            "--output-format",
            "stream-json",
            "--yolo",
            "--skip-trust",
        )
    }

    override fun configureProcess(
        builder: ProcessBuilderExt,
        requestedWorkDir: File?,
        effectiveWorkDir: File,
        systemPrompt: String,
        userInput: String,
    ) {
        if (requestedWorkDir != null) {
            ensureGeminiTrusted(effectiveWorkDir)
        }
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
        onStatus: (String) -> Unit,
        output: StringBuilder,
    ) {
        val event = GeminiStreamJsonEventParser.parse(line)
        if (event == null) {
            log.debug("gemini unparseable line: {}", line)
            return
        }
        log.debug("gemini event: type={}, line {}", event.type, line)
        when (event.type) {
            "message" -> {
                val isDelta = event.fields["delta"] as? Boolean ?: false
                if (!isDelta) return
                val content = event.fields["content"] as? String ?: return
                if (content.isNotBlank()) {
                    output.append(content)
                    onToken(content)
                }
            }

            "result" -> {
                val status = event.fields["status"] as? String ?: "done"
                @Suppress("UNCHECKED_CAST")
                val stats = event.fields["stats"] as? Map<String, Any>
                val totalTokens = stats?.get("total_tokens")
                val durationMs = stats?.get("duration_ms")
                val toolCalls = stats?.get("tool_calls")
                val summary = buildString {
                    append("result: $status")
                    if (totalTokens != null) append(" | tokens: $totalTokens")
                    if (toolCalls != null) append(" | tool calls: $toolCalls")
                    if (durationMs != null) {
                        val secs = (durationMs.toString().toDoubleOrNull() ?: 0.0) / 1000.0
                        append(" | duration: ${"%.1f".format(secs)}s")
                    }
                }
                onStatus(summary)
            }

            else -> onStatus(GeminiStreamJsonEventParser.render(event))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Ensures [workDir] (and its canonical path) is present in
     * `~/.gemini/trustedFolders.json` so Gemini CLI accepts it without
     * an interactive trust prompt.
     *
     * Uses the `gemini /permissions trust <path>` sub-command so the CLI manages
     * its own trust state rather than us editing the JSON file directly.
     */
    private fun ensureGeminiTrusted(workDir: File) {
        runCatching {
            val geminiPath = resolveAgentPath() ?: return
            val canonicalPath = workDir.canonicalPath
            log.debug("Trusting '{}' via gemini /permissions trust", canonicalPath)
            val proc = ProcessBuilderExt(geminiPath, "/permissions", "trust", canonicalPath)
                .apply {
                    val env = environment()
                    env["HOME"] = System.getProperty("user.home")
                    resolveApiKey()?.takeIf { it.isNotBlank() }?.let { env["GEMINI_API_KEY"] = it }
                }
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exit = proc.waitFor()
            if (exit == 0) {
                log.debug("gemini /permissions trust succeeded: {}", output)
            } else {
                log.warn("gemini /permissions trust exited {}: {}", exit, output)
            }
        }.onFailure { e ->
            log.warn("Failed to trust workDir via gemini /permissions trust: {}", e.message)
        }
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
         * Stderr lines containing these substrings are noise emitted by the Gemini CLI
         * regardless of the actual response — filtered out when reporting errors.
         */
        private val STDERR_NOISE_PATTERNS = listOf(
            "256-color support not detected",
            "YOLO mode is enabled",
            "Ripgrep is not available",
            "Falling back to GrepTool",
        )
    }
}
