/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.bookmarks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BookmarkCountsStore {
    private val mutableCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    val counts: StateFlow<Map<String, Int>> = mutableCounts.asStateFlow()

    fun setCounts(counts: Map<String, Int>) {
        mutableCounts.value = counts.toMap()
    }

    fun applyBookmarkChange(sessionId: String, isBookmarked: Boolean) {
        mutableCounts.update { current ->
            val nextCount = (current[sessionId] ?: 0) + if (isBookmarked) 1 else -1
            if (nextCount > 0) {
                current + (sessionId to nextCount)
            } else {
                current - sessionId
            }
        }
    }
}
