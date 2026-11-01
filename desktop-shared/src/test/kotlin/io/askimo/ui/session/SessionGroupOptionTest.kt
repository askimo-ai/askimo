/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Label
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Coverage for the sidebar sessions group-by feature: [SessionGroupOption.fromPrefValue],
 * [ChatSession.dateBucket], and the [List.groupedByOption] extension used when rendering the
 * sidebar's sessions list.
 */
class SessionGroupOptionTest {

    private val today = LocalDate.of(2024, 1, 15)
    private val zone = ZoneId.systemDefault()

    private fun instantAt(date: LocalDate) = Instant.fromEpochMilliseconds(
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
    )

    private fun session(id: String, updatedAt: LocalDate) = ChatSession(
        id = id,
        title = "session-$id",
        updatedAt = instantAt(updatedAt),
    )

    @Test
    fun `dateBucket classifies today as TODAY`() {
        val session = session("1", today)
        assertEquals(SessionDateBucket.TODAY, session.dateBucket(today))
    }

    @Test
    fun `dateBucket classifies yesterday as YESTERDAY`() {
        val session = session("1", today.minusDays(1))
        assertEquals(SessionDateBucket.YESTERDAY, session.dateBucket(today))
    }

    @Test
    fun `dateBucket classifies 6 days ago as PREVIOUS_7_DAYS`() {
        val session = session("1", today.minusDays(6))
        assertEquals(SessionDateBucket.PREVIOUS_7_DAYS, session.dateBucket(today))
    }

    @Test
    fun `dateBucket classifies exactly 7 days ago as OLDER`() {
        val session = session("1", today.minusDays(7))
        assertEquals(SessionDateBucket.OLDER, session.dateBucket(today))
    }

    @Test
    fun `dateBucket classifies 8 or more days ago as OLDER`() {
        val session = session("1", today.minusDays(30))
        assertEquals(SessionDateBucket.OLDER, session.dateBucket(today))
    }

    @Test
    fun `fromPrefValue round-trips every option's prefValue`() {
        SessionGroupOption.entries.forEach { option ->
            assertEquals(option, SessionGroupOption.fromPrefValue(option.prefValue))
        }
    }

    @Test
    fun `fromPrefValue falls back to NONE for unknown, null, or blank values`() {
        assertEquals(SessionGroupOption.NONE, SessionGroupOption.fromPrefValue("not-a-real-option"))
        assertEquals(SessionGroupOption.NONE, SessionGroupOption.fromPrefValue(null))
        assertEquals(SessionGroupOption.NONE, SessionGroupOption.fromPrefValue(""))
    }

    @Test
    fun `groupedByOption NONE returns a single ungrouped bucket preserving order`() {
        val sessions = listOf(
            session("1", today),
            session("2", today.minusDays(1)),
            session("3", today.minusDays(30)),
        )
        val groups = sessions.groupedByOption(SessionGroupOption.NONE, today)
        assertEquals(1, groups.size)
        val (bucket, bucketSessions) = groups.single()
        assertEquals(null, bucket)
        assertEquals(listOf("1", "2", "3"), bucketSessions.map { it.id })
    }

    @Test
    fun `groupedByOption BY_DATE groups in chronological bucket order, omitting empty buckets`() {
        val sessions = listOf(
            session("recent-a", today),
            session("old-a", today.minusDays(30)),
            session("recent-b", today),
            session("week-a", today.minusDays(3)),
        )
        val groups = sessions.groupedByOption(SessionGroupOption.BY_DATE, today)

        // No sessions fall into YESTERDAY, so that bucket must be omitted entirely.
        assertEquals(
            listOf(SessionDateBucket.TODAY, SessionDateBucket.PREVIOUS_7_DAYS, SessionDateBucket.OLDER),
            groups.map { (it.first as SessionGroupKey.ByDate).bucket },
        )
        assertEquals(listOf("recent-a", "recent-b"), groups[0].second.map { it.id })
        assertEquals(listOf("week-a"), groups[1].second.map { it.id })
        assertEquals(listOf("old-a"), groups[2].second.map { it.id })
    }

    private fun label(id: String, name: String, color: String = "#FF0000") = Label(id = id, name = name, color = color)

    @Test
    fun `groupedByOption BY_LABEL groups sessions under each assigned label, sorted by name`() {
        val sessions = listOf(session("1", today), session("2", today), session("3", today))
        val billing = label("l-billing", "Billing")
        val urgent = label("l-urgent", "Urgent")
        val labelsBySessionId = mapOf(
            "1" to listOf(billing, urgent),
            "2" to listOf(billing),
            // session 3 has no labels -> falls into the trailing Unlabeled bucket
        )

        val groups = sessions.groupedByOption(SessionGroupOption.BY_LABEL, today, labelsBySessionId)

        assertEquals(3, groups.size)
        assertEquals(SessionGroupKey.ByLabel(billing), groups[0].first)
        assertEquals(listOf("1", "2"), groups[0].second.map { it.id })
        assertEquals(SessionGroupKey.ByLabel(urgent), groups[1].first)
        assertEquals(listOf("1"), groups[1].second.map { it.id })
        assertEquals(SessionGroupKey.Unlabeled, groups[2].first)
        assertEquals(listOf("3"), groups[2].second.map { it.id })
    }

    @Test
    fun `groupedByOption BY_LABEL omits the Unlabeled bucket when every session has a label`() {
        val sessions = listOf(session("1", today))
        val billing = label("l-billing", "Billing")
        val groups = sessions.groupedByOption(SessionGroupOption.BY_LABEL, today, mapOf("1" to listOf(billing)))

        assertEquals(1, groups.size)
        assertEquals(SessionGroupKey.ByLabel(billing), groups.single().first)
    }
}
