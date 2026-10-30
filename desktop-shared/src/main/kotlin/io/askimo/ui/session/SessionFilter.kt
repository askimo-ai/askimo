/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession

/**
 * Filters this list to sessions whose title contains [query] (case-insensitive).
 *
 * A blank (empty or whitespace-only) query is treated as "no filter" and returns the list
 * unchanged. Extracted as a pure, independently testable function for the same reasons as
 * [sortedByOption] - the underlying sidebar list is already capped and fully in memory.
 */
fun List<ChatSession>.filteredByQuery(query: String): List<ChatSession> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { it.title.contains(trimmed, ignoreCase = true) }
}
