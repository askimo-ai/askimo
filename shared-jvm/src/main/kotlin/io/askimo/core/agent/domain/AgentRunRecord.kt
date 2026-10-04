/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.domain

import io.askimo.core.chat.dto.TurnTimelineEntry
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Persisted record of one execution run of a skill via an external agent.
 *
 * @param id          Unique record identifier (UUID).
 * @param workspaceId The [Workspace.id] this run was executed against — every run belongs
 *                    to exactly one workspace, mirroring how external agent CLIs scope
 *                    session/job history to a project/workspace.
 * @param conversationId Groups every turn of one continuous agent conversation together.
 *                    A brand-new conversation gets a fresh UUID; every follow-up turn
 *                    (resumed via the agent's own session, see [agentSessionId]) reuses
 *                    the same id, so the full multi-turn thread can be reconstructed
 *                    from history instead of only the single turn that was clicked.
 * @param title       Human-readable conversation title — never blank. Set to a deterministic
 *                    truncation of the first turn's [userInput] (see
 *                    `io.askimo.core.chat.TitleGenerator.fallbackTitle`) the instant a new
 *                    conversation starts, then optionally replaced with a short AI-generated
 *                    title (best-effort, async). Every turn of the same [conversationId]
 *                    shares this same value — kept in sync via a bulk update rather than
 *                    looked up from the first turn.
 * @param userInput   The context/prompt entered by the user before executing.
 * @param response    The full AI-generated response text; empty if the run failed.
 * @param error       Error message if the run failed; null on success.
 * @param isCancelled True if this turn was stopped mid-flight via [io.askimo.core.agent.ExternalAgent.cancel]
 *                    rather than failing or completing normally. [error] is typically null in
 *                    this case — cancellation is a deliberate user action, not a failure.
 *                    Defaults to `false` for backward compatibility with rows recorded before
 *                    this column existed.
 * @param agentId     Id of the [io.askimo.core.agent.ExternalAgent] that ran this turn (e.g.
 *                    "claude-code") — persisted so a re-opened conversation can restore (and
 *                    lock) the exact agent it was conducted with, instead of falling back to
 *                    whatever agent happens to be currently selected. Null for older rows
 *                    recorded before this column existed.
 * @param agentSessionId Optional external agent session identifier (if the runtime exposes one).
 * @param activityLog Ordered list of agent status/tool events emitted during this turn — plain
 *                    tool names only, kept for backward compatibility. See [contentBlocks] for
 *                    the richer, order-preserving version.
 * @param contentBlocks Ordered content blocks for this turn — tool calls and response-text
 *                    chunks, in the exact order they occurred, so history can reconstruct
 *                    interleaving (e.g. text → tool → text) instead of bucketing tools above
 *                    text. Deliberately excludes [TurnTimelineEntry.Thinking]/[TurnTimelineEntry.Status] —
 *                    reasoning/lifecycle text stays session-only, never persisted.
 * @param inputTokens  Best-effort input token count reported by the agent, if any.
 * @param outputTokens Best-effort output token count reported by the agent, if any.
 * @param totalTokens  Best-effort total token count reported by the agent, if any.
 * @param durationMs   Best-effort run duration (ms) reported by the agent itself, if any.
 * @param createdAt   When this run was recorded.
 */
data class AgentRunRecord(
    val id: String = UUID.randomUUID().toString(),

    val workspaceId: String,
    val conversationId: String,
    val title: String,
    val userInput: String,
    val response: String,
    val error: String?,
    val isCancelled: Boolean = false,
    val agentId: String? = null,
    val agentSessionId: String? = null,
    val activityLog: List<String>,
    val contentBlocks: List<TurnTimelineEntry> = emptyList(),
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
    val createdAt: Instant = Clock.System.now(),
)
