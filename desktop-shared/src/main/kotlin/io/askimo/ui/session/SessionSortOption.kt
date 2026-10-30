/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession

/**
 * Sort options for the sidebar's recent-sessions list.
 *
 * The underlying list is small (capped at [SessionsViewModel.MAX_SIDEBAR_SESSIONS] and already
 * fully loaded in memory), so sorting is applied client-side rather than via a DB query.
 */
enum class SessionSortOption(val prefValue: String) {
    LAST_UPDATED("LAST_UPDATED"),
    CREATED("CREATED"),
    TITLE_ASC("TITLE_ASC"),
    TITLE_DESC("TITLE_DESC"),
    ;

    companion object {
        /** Maps a persisted preference value back to an option, defaulting to [LAST_UPDATED]. */
        fun fromPrefValue(value: String?): SessionSortOption = entries.find { it.prefValue == value } ?: LAST_UPDATED
    }
}

/** Comparator implementing this sort option's ordering over a list of [ChatSession]s. */
fun SessionSortOption.comparator(): Comparator<ChatSession> = when (this) {
    SessionSortOption.LAST_UPDATED -> compareByDescending { it.updatedAt }
    SessionSortOption.CREATED -> compareByDescending { it.createdAt }
    SessionSortOption.TITLE_ASC -> compareBy { it.title.lowercase() }
    SessionSortOption.TITLE_DESC -> compareByDescending { it.title.lowercase() }
}

/**
 * Reorders this list per [option]. Extracted as a pure, independently testable function since
 * [SessionsViewModel] itself is too heavy to construct in a plain unit test (it needs a live
 * database + app context).
 */
fun List<ChatSession>.sortedByOption(option: SessionSortOption): List<ChatSession> = sortedWith(option.comparator())
