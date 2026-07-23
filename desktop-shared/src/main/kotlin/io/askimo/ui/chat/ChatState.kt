/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.dto.ChatMessageDTO

/**
 * State for the chat view.
 * Contains all the observable state values from ChatViewModel.
 */
data class ChatState(
    // Message-related state
    val messages: List<ChatMessageDTO>,
    val hasMoreMessages: Boolean,
    val isLoadingPrevious: Boolean,
    // Incremented each time previous messages are prepended. The UI uses this to
    // distinguish a prepend (keep viewport) from a normal append (scroll to bottom).
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
    val activeDirectiveIds: Set<String>,

    // Session state
    val sessionTitle: String,
    val project: Project?,
)
