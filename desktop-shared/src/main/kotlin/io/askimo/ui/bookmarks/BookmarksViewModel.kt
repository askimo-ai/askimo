/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.bookmarks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.service.BookmarkGroup
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.logging.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the global Bookmarks view.
 *
 * Loads all bookmarked messages across every session and groups them
 * by conversation. UI observes [groups], [isLoading], and [errorMessage].
 */
class BookmarksViewModel(
    private val chatSessionService: ChatSessionService,
    private val scope: CoroutineScope,
    private val bookmarkCountsStore: BookmarkCountsStore,
) {
    private val log = logger<BookmarksViewModel>()

    var groups by mutableStateOf<List<BookmarkGroup>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val result = withContext(Dispatchers.IO) {
                    chatSessionService.getAllBookmarkGroups()
                }
                groups = result
            } catch (e: Exception) {
                log.error("Failed to load bookmarks", e)
                errorMessage = e.message ?: "Failed to load bookmarks"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Remove a single bookmark optimistically from the in-memory list,
     * then persist the change via [chatSessionService].
     */
    fun removeBookmark(messageId: String) {
        val sessionId = groups.firstOrNull { group ->
            group.messages.any { it.id == messageId }
        }?.session?.id

        // Optimistic update
        groups = groups.mapNotNull { group ->
            val updated = group.messages.filter { it.id != messageId }
            if (updated.isEmpty()) null else group.copy(messages = updated)
        }

        scope.launch {
            try {
                val isBookmarked = withContext(Dispatchers.IO) {
                    chatSessionService.toggleBookmark(messageId)
                }
                if (sessionId != null) {
                    bookmarkCountsStore.applyBookmarkChange(sessionId, isBookmarked)
                }
            } catch (e: Exception) {
                log.error("Failed to remove bookmark for message {}", messageId, e)
                load()
            }
        }
    }
}
