/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.toJavaInstant

/**
 * Grouping options for the sidebar's recent-sessions list.
 *
 * Like [SessionSortOption], the underlying list is small and already fully loaded in memory,
 * so grouping is applied client-side rather than via a DB query.
 */
enum class SessionGroupOption(val prefValue: String) {
    NONE("NONE"),
    BY_DATE("BY_DATE"),
    ;

    companion object {
        /** Maps a persisted preference value back to an option, defaulting to [NONE]. */
        fun fromPrefValue(value: String?): SessionGroupOption = entries.find { it.prefValue == value } ?: NONE
    }
}

/** Date buckets used when [SessionGroupOption.BY_DATE] grouping is active. */
enum class SessionDateBucket {
    TODAY,
    YESTERDAY,
    PREVIOUS_7_DAYS,
    OLDER,
}

/**
 * Classifies this session's [ChatSession.updatedAt] relative to [today], using the system
 * default time zone (same convention as `LocalizationManager.formatDayLabel`).
 */
fun ChatSession.dateBucket(today: LocalDate = LocalDate.now()): SessionDateBucket {
    val date = updatedAt.toJavaInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        date == today -> SessionDateBucket.TODAY
        date == today.minusDays(1) -> SessionDateBucket.YESTERDAY
        date.isAfter(today.minusDays(7)) -> SessionDateBucket.PREVIOUS_7_DAYS
        else -> SessionDateBucket.OLDER
    }
}

/**
 * Groups this list per [option], preserving the incoming (already sorted) order within each
 * bucket. Extracted as a pure, independently testable function for the same reason as
 * [sortedByOption].
 *
 * - [SessionGroupOption.NONE] yields a single `null`-keyed bucket (i.e. no grouping headers).
 * - [SessionGroupOption.BY_DATE] yields buckets in a fixed chronological order, omitting any
 *   bucket with no matching sessions.
 */
fun List<ChatSession>.groupedByOption(
    option: SessionGroupOption,
    today: LocalDate = LocalDate.now(),
): List<Pair<SessionDateBucket?, List<ChatSession>>> = when (option) {
    SessionGroupOption.NONE -> listOf(null to this)

    SessionGroupOption.BY_DATE -> {
        val byBucket = groupBy { it.dateBucket(today) }
        SessionDateBucket.entries.mapNotNull { bucket ->
            byBucket[bucket]?.let { bucket to it }
        }
    }
}
