/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Label

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

/**
 * Filters this list to sessions carrying at least one of [selectedLabelIds] ("any of"
 * semantics), using [labelsBySessionId] to look up each session's assigned labels.
 *
 * An empty [selectedLabelIds] is treated as "no filter" and returns the list unchanged,
 * composing with [filteredByQuery] the same way the sidebar's title search does.
 */
fun List<ChatSession>.filteredByLabels(
    selectedLabelIds: Set<String>,
    labelsBySessionId: Map<String, List<Label>>,
): List<ChatSession> {
    if (selectedLabelIds.isEmpty()) return this
    return filter { session ->
        labelsBySessionId[session.id].orEmpty().any { it.id in selectedLabelIds }
    }
}
