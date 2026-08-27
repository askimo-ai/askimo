/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PageableTest {

    @Test
    fun `resolvePageParams returns null when totalItems is zero`() {
        assertNull(resolvePageParams(totalItems = 0, page = 1, pageSize = 10))
    }

    @Test
    fun `resolvePageParams computes totalPages, validPage and offset`() {
        val params = resolvePageParams(totalItems = 25, page = 2, pageSize = 10)

        assertEquals(2, params?.validPage)
        assertEquals(3, params?.totalPages)
        assertEquals(10L, params?.offset)
    }

    @Test
    fun `resolvePageParams clamps page below 1 up to 1`() {
        val params = resolvePageParams(totalItems = 25, page = 0, pageSize = 10)

        assertEquals(1, params?.validPage)
        assertEquals(0L, params?.offset)
    }

    @Test
    fun `resolvePageParams clamps page beyond totalPages down to totalPages`() {
        val params = resolvePageParams(totalItems = 25, page = 999, pageSize = 10)

        assertEquals(3, params?.validPage)
        assertEquals(3, params?.totalPages)
        assertEquals(20L, params?.offset)
    }

    @Test
    fun `resolvePageParams throws IllegalArgumentException when pageSize is zero`() {
        assertFailsWith<IllegalArgumentException> {
            resolvePageParams(totalItems = 10, page = 1, pageSize = 0)
        }
    }

    @Test
    fun `resolvePageParams throws IllegalArgumentException when pageSize is negative`() {
        assertFailsWith<IllegalArgumentException> {
            resolvePageParams(totalItems = 10, page = 1, pageSize = -5)
        }
    }

    @Test
    fun `resolvePageParams does not overflow when totalItems and pageSize are near Int MAX_VALUE`() {
        val params = resolvePageParams(
            totalItems = Int.MAX_VALUE,
            page = 1,
            pageSize = Int.MAX_VALUE,
        )

        assertEquals(1, params?.validPage)
        assertEquals(1, params?.totalPages)
        assertEquals(0L, params?.offset)
    }

    @Test
    fun `resolvePageParams does not overflow totalPages when totalItems is near Int MAX_VALUE with a small pageSize`() {
        val params = resolvePageParams(totalItems = Int.MAX_VALUE, page = 1, pageSize = 100)

        val expectedTotalPages = ((Int.MAX_VALUE.toLong() + 100 - 1) / 100).toInt()
        assertEquals(expectedTotalPages, params?.totalPages)
        assertEquals(1, params?.validPage)
        assertEquals(0L, params?.offset)
    }

    @Test
    fun `Pageable empty returns a Pageable with no items and page 1`() {
        val empty = Pageable.empty<String>(pageSize = 20)

        assertEquals(emptyList(), empty.items)
        assertEquals(1, empty.currentPage)
        assertEquals(0, empty.totalPages)
        assertEquals(0, empty.totalItems)
        assertEquals(20, empty.pageSize)
        assertEquals(true, empty.isEmpty)
    }
}
