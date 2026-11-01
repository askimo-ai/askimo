/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Label
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
    BY_LABEL("BY_LABEL"),
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
 * Discriminates the kind of group header a bucket of sessions is shown under, uniting
 * [SessionGroupOption.BY_DATE] and [SessionGroupOption.BY_LABEL] under one return type for
 * [groupedByOption] (which yields `null` for [SessionGroupOption.NONE]).
 */
sealed interface SessionGroupKey {
    data class ByDate(val bucket: SessionDateBucket) : SessionGroupKey
    data class ByLabel(val label: Label) : SessionGroupKey

    /** Trailing bucket for sessions with no labels assigned, shown last under [BY_LABEL]. */
    data object Unlabeled : SessionGroupKey
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
 * - [SessionGroupOption.BY_LABEL] yields one bucket per label (sorted by name), using
 *   [labelsBySessionId] to look up each session's assigned labels. A session with multiple
 *   labels appears in each of its labels' buckets. Sessions with no labels are grouped into a
 *   trailing [SessionGroupKey.Unlabeled] bucket.
 *
 * @param labelsBySessionId sessionId -> assigned labels, only consulted for [SessionGroupOption.BY_LABEL].
 */
fun List<ChatSession>.groupedByOption(
    option: SessionGroupOption,
    today: LocalDate = LocalDate.now(),
    labelsBySessionId: Map<String, List<Label>> = emptyMap(),
): List<Pair<SessionGroupKey?, List<ChatSession>>> = when (option) {
    SessionGroupOption.NONE -> listOf(null to this)

    SessionGroupOption.BY_DATE -> {
        val byBucket = groupBy { it.dateBucket(today) }
        SessionDateBucket.entries.mapNotNull { bucket ->
            byBucket[bucket]?.let { SessionGroupKey.ByDate(bucket) to it }
        }
    }

    SessionGroupOption.BY_LABEL -> {
        val labeled = LinkedHashMap<Label, MutableList<ChatSession>>()
        val unlabeled = mutableListOf<ChatSession>()

        for (session in this) {
            val labels = labelsBySessionId[session.id].orEmpty()
            if (labels.isEmpty()) {
                unlabeled += session
            } else {
                for (label in labels) {
                    labeled.getOrPut(label) { mutableListOf() }.add(session)
                }
            }
        }

        val labelBuckets = labeled.entries
            .sortedBy { it.key.name.lowercase() }
            .map { (label, sessions) -> SessionGroupKey.ByLabel(label) as SessionGroupKey to sessions as List<ChatSession> }

        if (unlabeled.isEmpty()) labelBuckets else labelBuckets + (SessionGroupKey.Unlabeled to unlabeled)
    }
}
