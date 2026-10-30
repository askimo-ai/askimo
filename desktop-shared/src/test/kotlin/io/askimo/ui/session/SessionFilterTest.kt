/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.domain.ChatSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Coverage for the sidebar inline search filter: the [List.filteredByQuery] extension. */
class SessionFilterTest {

    private fun session(
        id: String,
        title: String,
    ) = ChatSession(
        id = id,
        title = title,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private val sessions = listOf(
        session(id = "1", title = "Banana trip planning"),
        session(id = "2", title = "Apple notes"),
        session(id = "3", title = "Cherry pie recipe"),
    )

    @Test
    fun `blank query returns the list unchanged`() {
        assertEquals(sessions, sessions.filteredByQuery(""))
        assertEquals(sessions, sessions.filteredByQuery("   "))
    }

    @Test
    fun `filters case-insensitively on title substring`() {
        val ids = sessions.filteredByQuery("apple").map { it.id }
        assertEquals(listOf("2"), ids)
    }

    @Test
    fun `matches a substring in the middle of the title`() {
        val ids = sessions.filteredByQuery("trip").map { it.id }
        assertEquals(listOf("1"), ids)
    }

    @Test
    fun `no match returns an empty list`() {
        assertEquals(emptyList(), sessions.filteredByQuery("zzz-no-match"))
    }

    @Test
    fun `query is trimmed before matching`() {
        val ids = sessions.filteredByQuery("  cherry  ").map { it.id }
        assertEquals(listOf("3"), ids)
    }

    @Test
    fun `does not mutate the original list`() {
        val original = sessions.map { it.id }
        sessions.filteredByQuery("apple")
        assertEquals(original, sessions.map { it.id })
    }
}
