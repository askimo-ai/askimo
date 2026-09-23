/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.ToolApprovalRequest
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.TurnTimelineGroup
import io.askimo.core.memory.MemoryPressureLevel

/**
 * State for the chat view.
 * Contains all the observable state values from ChatViewModel.
 */
data class ChatState(
    // Message-related state
    val messages: List<ChatMessageDTO>,
    val hasMoreMessages: Boolean,
    val isLoadingPrevious: Boolean,
    // Incremented on each prepend, letting the UI tell a prepend (keep viewport) apart
    // from a normal append (scroll to bottom).
    val prependGeneration: Int = 0,

    // Loading/Thinking state
    val isLoading: Boolean,
    val isThinking: Boolean,
    val thinkingElapsedSeconds: Int,
    val spinnerFrame: Char,
    val errorMessage: String?,

    // Search state
    val isSearchMode: Boolean,
    val searchQuery: String,
    val searchResults: List<ChatMessageDTO>,
    val currentSearchResultIndex: Int,
    val isSearching: Boolean,

    // Directive state
    val selectedDirective: String?,

    // Resource Collections chip selection (see ChatSession.activeResourceCollectionIds).
    // At most one is active; feeds the RAG retriever like a project's knowledge sources
    // when the session has no project (see ChatSessionService.createRetrieverForContainer).
    val activeResourceCollectionIds: List<String> = emptyList(),

    // Session state
    val sessionTitle: String,
    val project: Project?,

    // Chronological tool-call/text/thinking timeline for the current turn (this session
    // only); replaces the old activeToolCalls/activeThinkingContent pair. See
    // SessionManager.StreamingThread.timeline.
    val activeTimeline: List<TurnTimelineEntry> = emptyList(),

    // Pending tool approval; non-null while the AI awaits user consent to run a tool.
    // Cleared once approved or denied.
    val pendingToolApproval: ToolApprovalRequest? = null,

    // Per-message timelines (incl. thinking) for turns completed earlier in this session,
    // keyed by message id. Falls back to ChatMessageDTO.contentBlocks (tool calls + text
    // only) once a turn drops out of this map (e.g. after an app restart).
    val completedTimelines: Map<String, List<TurnTimelineGroup>> = emptyMap(),

    // IDs of messages pinned by the user in this session
    val bookmarkedMessageIds: Set<String> = emptySet(),

    // Set by jumpToMessage so ChatView can scroll to the target after messages reload;
    // cleared by ChatView via actions.clearPendingScroll().
    val pendingScrollToMessageId: String? = null,

    // Memory pressure, reactively updated from TokenAwareSummarizingMemory.pressureLevel.
    // Drives the memory chip colour and the contextual banner above the input field.
    val memoryPressureLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL,

    // Token utilisation (0..1) against the effective budget; drives the chip arc.
    val memoryUtilization: Float = 0f,

    // Raw token counts for the memory chip tooltip.
    val memoryUsedTokens: Int = 0,
    val memoryBudgetTokens: Int = 0,

    // True while a forceCompact() cycle is running (user pressed "Compress").
    val isCompressing: Boolean = false,

    // False until the model's context size is verified at runtime (from the persistent
    // cache or a context-reduction cycle). When false, the chip shows "?" instead of a
    // potentially misleading percentage.
    val isContextSizeLearned: Boolean = false,
)
