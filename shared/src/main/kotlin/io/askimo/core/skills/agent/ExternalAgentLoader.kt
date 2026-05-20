/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

import io.askimo.core.logging.logger

/**
 * Discovers which external agents are installed on the current machine.
 *
 * Agents are registered in a fixed list. Only agents whose [ExternalAgent.isAvailable]
 * returns `true` are returned from [available].
 *
 * To add a new agent in the future, register it in [ALL] and implement [ExternalAgent].
 */
object ExternalAgentLoader {

    private val log = logger<ExternalAgentLoader>()

    /** All known agent implementations, in preferred display order. */
    private val ALL: List<ExternalAgent> = listOf(
        ClaudeAgent(),
        GeminiAgent(),
        CodexAgent(),
    )

    /**
     * Returns the display names of all known agents in preferred display order.
     * Use this to build UI labels like "Claude Code, Gemini CLI, or Codex".
     */
    fun displayNames(): List<String> = ALL.map { it.name }

    /**
     * Returns all known agents regardless of whether they are installed.
     * Use this to show the full list to users so they know what is supported.
     */
    fun all(): List<ExternalAgent> = ALL

    /**
     * Returns the subset of [ALL] agents that are currently installed.
     * Availability is checked lazily on each call (no caching) so the list
     * reflects the current `PATH` state without requiring a restart.
     */
    fun available(): List<ExternalAgent> = ALL.filter { agent ->
        agent.isAvailable().also { available ->
            if (available) {
                log.debug("External agent '{}' is available", agent.id)
            } else {
                log.debug("External agent '{}' not found on PATH", agent.id)
            }
        }
    }

    /**
     * Returns a specific agent by [id], or `null` if it is not registered or not available.
     */
    fun find(id: String): ExternalAgent? = available().firstOrNull { it.id == id }
}
