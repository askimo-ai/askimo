/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

/**
 * Describes a slash-command supported by an [ExternalAgent].
 *
 * Commands are passed directly in the user-input field (e.g. `/tools desc`)
 * and forwarded verbatim to the agent process via the `-p` / `--print` flag.
 *
 * @param name        Command name including the leading slash, e.g. `/tools`.
 * @param description Short description shown in the command picker.
 * @param usage       Optional usage hint, e.g. `/tools [desc|nodesc]`.
 * @param subCommands Nested sub-commands, if any.
 */
data class AgentCommand(
    val name: String,
    val description: String,
    val usage: String = "",
    val subCommands: List<AgentCommand> = emptyList(),
) {
    /** Flat list of this command and all its sub-commands for search/display. */
    fun flatten(): List<AgentCommand> = listOf(this) + subCommands.flatMap { it.flatten() }
}
