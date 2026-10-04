/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.search

import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.context.MessageRole
import io.askimo.core.db.SearchSortBy
import io.askimo.core.logging.logger
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import java.time.Instant as JavaInstant

/**
 * Service for searching across chat sessions.
 * Provides functionality to search messages across all sessions with filtering and sorting.
 */
class SessionSearchService(
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: ChatMessageRepository,
) {
    private val log = logger<SessionSearchService>()

    /**
     * Search for messages across all sessions.
     *
     * @param query The search query string
     * @param dateFilter Optional date filter (ALL_TIME, TODAY, LAST_7_DAYS, etc.)
     * @param projectId Optional project ID to filter by project
     * @param sortBy Sort order (RELEVANCE, DATE_DESC, DATE_ASC)
     * @param limit Maximum number of results to return (default: 100)
     * @return List of search results
     */
    suspend fun searchSessions(
        query: String,
        dateFilter: DateFilter = DateFilter.ALL_TIME,
        projectId: String? = null,
        sortBy: SortBy = SortBy.RELEVANCE,
        limit: Int = 100,
    ): List<SearchResult> {
        if (query.isBlank()) {
            log.debug("Empty search query, returning empty results")
            return emptyList()
        }

        log.info("Searching sessions: query='$query', dateFilter=$dateFilter, projectId=$projectId, sortBy=$sortBy")

        // Get date range based on filter
        val dateRange = getDateRange(dateFilter)

        // Convert SortBy to SearchSortBy for repository
        val repositorySortBy = when (sortBy) {
            SortBy.RELEVANCE -> SearchSortBy.DATE_DESC

            // Fall back to date sorting for now
            SortBy.DATE_DESC -> SearchSortBy.DATE_DESC

            SortBy.DATE_ASC -> SearchSortBy.DATE_ASC
        }

        // Search messages in database with sorting done at database level
        val messages = messageRepository.searchMessages(
            query = query,
            startTime = dateRange.first,
            endTime = dateRange.second,
            projectId = projectId,
            sortBy = repositorySortBy,
            limit = limit,
        )

        log.debug("Found ${messages.size} messages matching query")

        // Group by session
        val sessionIds = messages.map { it.sessionId }.distinct()
        val sessions = sessionRepository.getSessionsByIds(sessionIds)

        log.debug("Found messages in ${sessionIds.size} distinct sessions")

        val results = messages.mapNotNull { message ->
            val session = sessions.find { it.id == message.sessionId } ?: return@mapNotNull null

            log.debug("Match: session='${session.title}', message='${message.content.take(100)}...'")

            SearchResult(
                sessionId = session.id,
                sessionTitle = session.title,
                projectId = session.projectId,
                messageId = message.id,
                messageContent = message.content,
                messageTimestamp = message.createdAt,
                isUserMessage = message.role == MessageRole.USER,
            )
        }

        log.debug("Returning ${results.size} search results")
        return results
    }

    /**
     * Get date range based on filter.
     * Returns (startTime, endTime) as Instant? pair.
     */
    private fun getDateRange(filter: DateFilter): Pair<Instant?, Instant?> {
        val now = JavaInstant.now()
        val zoneId = ZoneId.systemDefault()

        return when (filter) {
            DateFilter.ALL_TIME -> Pair(null, null)

            DateFilter.TODAY -> {
                val startOfDay = LocalDateTime.now(zoneId)
                    .truncatedTo(ChronoUnit.DAYS)
                    .atZone(zoneId)
                    .toInstant()
                Pair(startOfDay.toKotlinInstant(), now.toKotlinInstant())
            }

            DateFilter.LAST_7_DAYS -> Pair(now.minus(7, ChronoUnit.DAYS).toKotlinInstant(), now.toKotlinInstant())

            DateFilter.LAST_30_DAYS -> Pair(now.minus(30, ChronoUnit.DAYS).toKotlinInstant(), now.toKotlinInstant())

            DateFilter.LAST_3_MONTHS -> Pair(now.minus(90, ChronoUnit.DAYS).toKotlinInstant(), now.toKotlinInstant())

            DateFilter.LAST_YEAR -> Pair(now.minus(365, ChronoUnit.DAYS).toKotlinInstant(), now.toKotlinInstant())
        }
    }
}

/**
 * Date filter options for search.
 */
enum class DateFilter {
    ALL_TIME,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_3_MONTHS,
    LAST_YEAR,
}

/**
 * Sort options for search results.
 */
enum class SortBy {
    RELEVANCE,
    DATE_DESC,
    DATE_ASC,
}

/**
 * Search result containing message and session information.
 */
data class SearchResult(
    val sessionId: String,
    val sessionTitle: String,
    val projectId: String?,
    val messageId: String,
    val messageContent: String,
    val messageTimestamp: Instant,
    val isUserMessage: Boolean,
)
