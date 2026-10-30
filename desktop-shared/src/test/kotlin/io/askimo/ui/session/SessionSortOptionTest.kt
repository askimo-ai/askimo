/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Coverage for the sidebar sessions sort feature: [SessionSortOption.comparator],
 * [SessionSortOption.fromPrefValue], and the [List.sortedByOption] extension used by
 * [SessionsViewModel.sortedRecentSessions].
 */
class SessionSortOptionTest {

    private fun session(
        id: String,
        title: String,
        createdAt: String,
        updatedAt: String,
    ) = ChatSession(
        id = id,
        title = title,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )

    private val sessions = listOf(
        session(id = "1", title = "banana trip", createdAt = "2024-01-01T00:00:00Z", updatedAt = "2024-01-03T00:00:00Z"),
        session(id = "2", title = "Apple notes", createdAt = "2024-01-02T00:00:00Z", updatedAt = "2024-01-01T00:00:00Z"),
        session(id = "3", title = "cherry pie", createdAt = "2024-01-03T00:00:00Z", updatedAt = "2024-01-02T00:00:00Z"),
    )

    @Test
    fun `LAST_UPDATED orders sessions by updatedAt, newest first`() {
        val ids = sessions.sortedByOption(SessionSortOption.LAST_UPDATED).map { it.id }
        assertEquals(listOf("1", "3", "2"), ids)
    }

    @Test
    fun `CREATED orders sessions by createdAt, newest first`() {
        val ids = sessions.sortedByOption(SessionSortOption.CREATED).map { it.id }
        assertEquals(listOf("3", "2", "1"), ids)
    }

    @Test
    fun `TITLE_ASC orders sessions alphabetically, case-insensitively`() {
        // "Apple" (capitalized) must sort before "banana" and "cherry" despite the differing case.
        val ids = sessions.sortedByOption(SessionSortOption.TITLE_ASC).map { it.id }
        assertEquals(listOf("2", "1", "3"), ids)
    }

    @Test
    fun `TITLE_DESC orders sessions reverse-alphabetically, case-insensitively`() {
        val ids = sessions.sortedByOption(SessionSortOption.TITLE_DESC).map { it.id }
        assertEquals(listOf("3", "1", "2"), ids)
    }

    @Test
    fun `fromPrefValue round-trips every option's prefValue`() {
        SessionSortOption.entries.forEach { option ->
            assertEquals(option, SessionSortOption.fromPrefValue(option.prefValue))
        }
    }

    @Test
    fun `fromPrefValue falls back to LAST_UPDATED for unknown, null, or blank values`() {
        assertEquals(SessionSortOption.LAST_UPDATED, SessionSortOption.fromPrefValue("not-a-real-option"))
        assertEquals(SessionSortOption.LAST_UPDATED, SessionSortOption.fromPrefValue(null))
        assertEquals(SessionSortOption.LAST_UPDATED, SessionSortOption.fromPrefValue(""))
    }

    @Test
    fun `sortedByOption does not mutate the original list`() {
        val original = sessions.map { it.id }
        sessions.sortedByOption(SessionSortOption.TITLE_ASC)
        assertEquals(original, sessions.map { it.id })
        assertNull(sessions.find { it.id == "missing" })
    }
}
