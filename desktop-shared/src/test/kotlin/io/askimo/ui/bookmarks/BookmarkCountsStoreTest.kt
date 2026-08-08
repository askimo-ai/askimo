/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.bookmarks

import kotlin.test.Test
import kotlin.test.assertEquals

class BookmarkCountsStoreTest {
    @Test
    fun `setCounts publishes a snapshot`() {
        val source = mutableMapOf("session-a" to 2)
        val store = BookmarkCountsStore()

        store.setCounts(source)
        source["session-a"] = 3

        assertEquals(mapOf("session-a" to 2), store.counts.value)
    }

    @Test
    fun `bookmark creates a count for a session`() {
        val store = BookmarkCountsStore()

        store.applyBookmarkChange("session-a", isBookmarked = true)

        assertEquals(mapOf("session-a" to 1), store.counts.value)
    }

    @Test
    fun `bookmark increments the session without changing other counts`() {
        val store = BookmarkCountsStore()
        store.setCounts(mapOf("session-a" to 2, "session-b" to 4))

        store.applyBookmarkChange("session-a", isBookmarked = true)

        assertEquals(mapOf("session-a" to 3, "session-b" to 4), store.counts.value)
    }

    @Test
    fun `unbookmark decrements the session count`() {
        val store = BookmarkCountsStore()
        store.setCounts(mapOf("session-a" to 2))

        store.applyBookmarkChange("session-a", isBookmarked = false)

        assertEquals(mapOf("session-a" to 1), store.counts.value)
    }

    @Test
    fun `unbookmark removes a session after its final bookmark`() {
        val store = BookmarkCountsStore()
        store.setCounts(mapOf("session-a" to 1, "session-b" to 2))

        store.applyBookmarkChange("session-a", isBookmarked = false)

        assertEquals(mapOf("session-b" to 2), store.counts.value)
    }

    @Test
    fun `unbookmark leaves counts unchanged for a missing session`() {
        val store = BookmarkCountsStore()
        store.setCounts(mapOf("session-a" to 2))

        store.applyBookmarkChange("session-b", isBookmarked = false)

        assertEquals(mapOf("session-a" to 2), store.counts.value)
    }
}
