/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import java.io.File

/**
 * Represents an external CLI agent capable of running a skill non-interactively.
 *
 * Implementations compose the OS command, pipe the combined prompt (system + user)
 * via stdin, and stream stdout back to the caller line by line.
 *
 * The skill document is agent-agnostic — the agent is chosen at run time.
 */
interface ExternalAgent {

    /** Stable identifier, e.g. `"claude"` or `"antigravity"`. */
    val id: String

    /** Human-readable display name shown in the UI. */
    val name: String

    /** URL to the installation guide for this agent, shown when the agent is not installed. */
    val installUrl: String

    /**
     * Slash-commands supported by this agent, shown in the UI command picker
     * when the user types `/` in the context input field.
     * Defaults to an empty list — agents that support no special commands omit this.
     */
    val commands: List<AgentCommand> get() = emptyList()

    /**
     * Whether Askimo can collect an API key from the user and inject it into the agent process.
     * `true`  → show inline API key input when [isConfigured] returns false (e.g. Antigravity CLI).
     * `false` → show [configurationHint] as a read-only banner (e.g. Claude Code uses OAuth login).
     */
    val requiresApiKey: Boolean get() = false

    /**
     * Human-readable hint shown below the agent picker when [isBinaryAvailable] is `true`
     * but [isConfigured] returns `false`.
     * Only relevant when [requiresApiKey] is `false`; for key-based agents the UI renders
     * an inline key input instead.
     */
    val configurationHint: String get() = ""

    /**
     * Optional external runtime session identifier captured during the most recent execution.
     * Agents that do not expose sessions return `null`.
     */
    val lastExecutionSessionId: String? get() = null

    /**
     * Effective workspace directory used by the most recent execution.
     * Agents that do not expose it return `null`.
     */
    val lastExecutionWorkspaceDir: String? get() = null

    /**
     * Best-effort token usage / duration metadata captured from the most recent execution's
     * own stream-json output (e.g. Claude's `result` event, Antigravity's `usage` map).
     * All fields inside are nullable and `null` overall for agents that don't expose
     * structured usage (e.g. Codex today) — the UI hides the metadata row in that case.
     */
    val lastExecutionUsage: AgentUsage? get() = null

    /**
     * Returns `true` if the agent binary is installed and reachable on `PATH`.
     * Cheap check only — no API call.
     */
    fun isBinaryAvailable(): Boolean

    /**
     * Returns `true` if the agent is fully configured and ready to run
     * (binary installed + any required credentials present).
     * Defaults to [isBinaryAvailable] — override when credentials are also required.
     */
    fun isConfigured(): Boolean = isBinaryAvailable()

    /**
     * Returns `true` if the agent is both installed and configured.
     * Convenience combining [isBinaryAvailable] and [isConfigured].
     */
    fun isAvailable(): Boolean = isBinaryAvailable() && isConfigured()

    /**
     * Optionally materializes [skill] into this agent's own native skill/context discovery
     * location under [workDir] (e.g. Claude Code's `.claude/skills/<name>/`), so the agent's
     * built-in mechanism — not just the system prompt injected via [run] — is also aware of it.
     *
     * This lets Askimo's agent-agnostic skill library "plug into" each agent's native
     * conventions at run time, scoped to the workspace the skill is being run against,
     * instead of the agent's own default (usually home-directory) location.
     *
     * Default: no-op — agents with no native skill-folder convention rely solely on the
     * system-prompt injection already performed in [run].
     *
     * @return An [AutoCloseable] cleanup handle. Callers should invoke [AutoCloseable.close]
     *         after the run completes to remove any run-scoped artifacts this call created.
     *         Implementations that found an already-existing native skill with the same name
     *         (rather than creating one) must return a no-op handle so nothing the user owns
     *         is ever deleted.
     */
    fun materializeSkill(skill: SkillDefinition, workDir: File): AutoCloseable = AutoCloseable {}

    /**
     * Saves an API key for this agent via [io.askimo.core.security.SecureKeyManager]
     * and optionally syncs it to the matching chat provider settings in AppContext.
     * Only called when [requiresApiKey] is `true`.
     * Default implementation is a no-op — override in agents that need key injection.
     */
    fun saveApiKey(key: String) {}

    /**
     * Runs the skill non-interactively.
     *
     * The agent receives the combined prompt via **stdin**:
     * ```
     * <systemPrompt>
     *
     * ---
     *
     * <userInput>
     * ```
     * Stdout is read line by line and delivered through [onToken].
     * The full accumulated output is returned when the process exits.
     *
     * Conversation memory is **not** managed by Askimo. Each CLI agent (Claude Code,
     * Antigravity, Cursor, Codex, ...) owns its own conversation/context store internally,
     * addressed by an opaque session id. To continue a prior conversation instead of
     * starting a fresh one, pass the id previously captured via [lastExecutionSessionId]
     * as [resumeSessionId] — the implementation translates it into that agent's native
     * resume mechanism (e.g. `claude --resume <id>`). Askimo never reconstructs or
     * replays prior turns itself.
     *
     * @param systemPrompt    The skill's raw markdown body (its system prompt).
     * @param userInput       Optional context supplied by the user at run time.
     * @param workDir         Working directory for the agent process. File writes land here.
     *                        Defaults to `null` — agents use their own CWD.
     * @param resumeSessionId Optional native session id (from a prior [lastExecutionSessionId])
     *                        to resume instead of starting a new conversation. `null` starts fresh.
     *                        Agents that do not support resuming ignore this.
     * @param onToken         Called for each content token as it arrives (pure response text).
     * @param onStatus        Called with a short human-readable status string when the agent
     *                        performs a tool call (e.g. "Using tool: readFile"). Defaults to no-op.
     * @return The complete stdout output, or a [Result.failure] on error.
     */
    fun run(
        systemPrompt: String,
        userInput: String,
        workDir: File? = null,
        resumeSessionId: String? = null,
        onToken: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
    ): Result<String>

    /**
     * Wraps [run] with automatic timing and analytics tracking.
     * All new agents automatically get tracking without any extra code.
     */
    fun runTracked(
        systemPrompt: String,
        userInput: String,
        workDir: File? = null,
        resumeSessionId: String? = null,
        onToken: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
    ): Result<String> {
        val startMs = System.currentTimeMillis()
        val result = run(systemPrompt, userInput, workDir, resumeSessionId, onToken, onStatus, onThinking)
        val durationMs = System.currentTimeMillis() - startMs
        Analytics.track(
            AnalyticsEvent.SKILL_AGENT_RUN,
            mapOf(
                "agent" to id,
                "has_user_input" to (userInput.isNotBlank()).toString(),
                "success" to result.isSuccess.toString(),
                "duration_bucket" to when {
                    durationMs < 5_000 -> "<5s"
                    durationMs < 30_000 -> "5-30s"
                    durationMs < 120_000 -> "30-120s"
                    else -> ">120s"
                },
            ),
        )
        return result
    }
}
