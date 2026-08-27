/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

/**
 * Generic container for paginated results.
 *
 * @param T The type of items in the page
 * @property items The items in the current page
 * @property currentPage The current page number (1-based)
 * @property totalPages The total number of pages
 * @property totalItems The total number of items across all pages
 * @property pageSize The number of items per page
 */
data class Pageable<T>(
    val items: List<T>,
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val pageSize: Int,
) {
    val hasNextPage: Boolean get() = currentPage < totalPages
    val hasPreviousPage: Boolean get() = currentPage > 1
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        /**
         * Builds an empty [Pageable], used when a query matches zero rows.
         */
        fun <T> empty(pageSize: Int): Pageable<T> = Pageable(
            items = emptyList(),
            currentPage = 1,
            totalPages = 0,
            totalItems = 0,
            pageSize = pageSize,
        )
    }
}

/**
 * Holds the resolved pagination parameters for a page query.
 *
 * @property validPage The requested page, clamped to the valid range [1, totalPages]
 * @property totalPages The total number of pages given [totalItems] and pageSize
 * @property offset The row offset (0-based) to use for the current page's query
 */
data class PageParams(
    val validPage: Int,
    val totalPages: Int,
    val offset: Long,
)

/**
 * Computes pagination parameters (total pages, clamped current page, and row offset)
 * from a total item count. Returns null when [totalItems] is zero, signaling the
 * caller should short-circuit with [Pageable.empty].
 *
 * Reusable across any repository that needs offset-based pagination over an
 * Exposed query — avoids duplicating the total-pages/clamped-page/offset math.
 *
 * @sample
 * ```kotlin
 * val totalItems = baseQuery.count().toInt()
 * val pageParams = resolvePageParams(totalItems, page, pageSize)
 *     ?: return@transaction Pageable.empty(pageSize)
 *
 * val items = baseQuery
 *     .limit(pageSize)
 *     .offset(pageParams.offset)
 *     .map { it.toEntity() }
 *
 * Pageable(
 *     items = items,
 *     currentPage = pageParams.validPage,
 *     totalPages = pageParams.totalPages,
 *     totalItems = totalItems,
 *     pageSize = pageSize,
 * )
 * ```
 */
fun resolvePageParams(totalItems: Int, page: Int, pageSize: Int): PageParams? {
    if (totalItems == 0) return null
    val totalPages = (totalItems + pageSize - 1) / pageSize
    val validPage = page.coerceIn(1, totalPages)
    val offset = ((validPage - 1) * pageSize).toLong()
    return PageParams(validPage, totalPages, offset)
}
