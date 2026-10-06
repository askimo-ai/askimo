/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.TitleGenerator
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.SESSION_TITLE_MAX_LENGTH
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.db.resolvePageParams
import io.askimo.core.db.sqldelight.Chat_sessions
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import io.askimo.core.util.JsonUtils.json
import java.util.UUID
import kotlin.time.Clock

/**
 * Maps a generated [Chat_sessions] row to the shared [ChatSession] domain object.
 */
private fun Chat_sessions.toChatSession(): ChatSession = ChatSession(
    id = id,
    title = title,
    createdAt = created_at,
    updatedAt = updated_at,
    projectId = project_id,
    directiveId = directive_id,
    isStarred = is_starred == 1L,
    isUserRenamed = is_user_renamed == 1L,
    activeResourceCollectionIds = decodeResourceCollectionIds(active_resource_collection_ids),
)

/**
 * Decode the JSON array stored in `active_resource_collection_ids` into a list of collection
 * IDs. Falls back to an empty list for blank/malformed values so a corrupt cell never breaks
 * session loading.
 */
private fun decodeResourceCollectionIds(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        json.decodeFromString<List<String>>(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

/** Encode a list of collection IDs to JSON for storage in `active_resource_collection_ids`. */
private fun encodeResourceCollectionIds(ids: List<String>): String = json.encodeToString(ids)

class ChatSessionRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val log = logger<ChatSessionRepository>()
    private val queries get() = db.chatSessionsQueries

    fun createSession(session: ChatSession): ChatSession {
        val trimmedTitle = generateTitle(session.title)
        val sessionWithInjectedFields = session.copy(
            id = session.id.ifBlank { UUID.randomUUID().toString() },
            title = trimmedTitle,
        )

        queries.insertSession(
            id = sessionWithInjectedFields.id,
            title = sessionWithInjectedFields.title,
            created_at = sessionWithInjectedFields.createdAt,
            updated_at = sessionWithInjectedFields.updatedAt,
            directive_id = sessionWithInjectedFields.directiveId,
            is_starred = if (sessionWithInjectedFields.isStarred) 1L else 0L,
            project_id = sessionWithInjectedFields.projectId,
            active_resource_collection_ids = encodeResourceCollectionIds(sessionWithInjectedFields.activeResourceCollectionIds),
        )

        EventBus.post(PushDataToServerEvent(reason = "session created"))
        return sessionWithInjectedFields
    }

    /** Returns the total number of sessions using a SQL COUNT(*) query. */
    fun countAll(): Int = queries.countAll().executeAsOne().toInt()

    /**
     * Get sessions with a limited number. Sessions are ordered by starred status then
     * updated time (both descending).
     */
    fun getSessions(limit: Int): List<ChatSession> = queries.selectAllOrderedLimited(limit.toLong()).executeAsList().map { it.toChatSession() }

    /**
     * Get sessions with pagination and optional filtering.
     * Sessions are ordered by starred status, then updated time.
     *
     * @param projectFilter null = all sessions; true = only sessions WITH a project;
     *   false = only sessions WITHOUT a project.
     * @param sortOrderDesc true = newest first (default), false = oldest first.
     */
    fun getSessionsPaged(
        page: Int = 1,
        pageSize: Int = 10,
        projectFilter: Boolean? = null,
        sortOrderDesc: Boolean = true,
    ): Pageable<ChatSession> {
        val filterParam = projectFilter?.let { if (it) 1L else 0L }

        val totalItems = queries.countFiltered(filterParam).executeAsOne().toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize) ?: return Pageable.empty(pageSize)

        val items = if (sortOrderDesc) {
            queries.selectFilteredPagedDesc(filterParam, pageSize.toLong(), pageParams.offset)
        } else {
            queries.selectFilteredPagedAsc(filterParam, pageSize.toLong(), pageParams.offset)
        }.executeAsList().map { it.toChatSession() }

        return Pageable(
            items = items,
            currentPage = pageParams.validPage,
            totalPages = pageParams.totalPages,
            totalItems = totalItems,
            pageSize = pageSize,
        )
    }

    /** Get all sessions associated with a specific project, ordered by updated time (newest first). */
    fun getSessionsByProjectId(projectId: String): List<ChatSession> = queries.selectByProjectId(projectId).executeAsList().map { it.toChatSession() }

    fun getSession(sessionId: String): ChatSession? = queries.selectById(sessionId).executeAsOneOrNull()?.toChatSession()

    /** Update the updatedAt timestamp of a session — typically called when a message is added. */
    fun touchSession(sessionId: String): Boolean {
        val updated = queries.touchSession(Clock.System.now(), sessionId).value > 0
        if (updated) EventBus.post(PushDataToServerEvent(reason = "session touched"))
        return updated
    }

    private fun generateTitle(firstMessage: String): String = TitleGenerator.fallbackTitle(firstMessage)

    fun generateAndUpdateTitle(sessionId: String, firstMessage: String): String {
        val title = generateTitle(firstMessage)
        queries.updateTitle(title, Clock.System.now(), sessionId)
        EventBus.post(PushDataToServerEvent(reason = "session title generated"))
        return title
    }

    /** Update the directive for a chat session (null clears it). */
    fun updateSessionDirective(sessionId: String, directiveId: String?): Boolean {
        val updated = queries.updateDirective(directiveId, Clock.System.now(), sessionId).value > 0
        if (updated) EventBus.post(PushDataToServerEvent(reason = "session directive changed"))
        return updated
    }

    /**
     * Update the persistent set of active Resource Collections for a chat session
     * (chip state — see [ChatSession.activeResourceCollectionIds]).
     */
    fun updateSessionActiveResourceCollections(sessionId: String, collectionIds: List<String>): Boolean {
        val updated = queries.updateActiveResourceCollectionIds(
            encodeResourceCollectionIds(collectionIds),
            Clock.System.now(),
            sessionId,
        ).value > 0
        if (updated) EventBus.post(PushDataToServerEvent(reason = "session active resource collections changed"))
        return updated
    }

    /**
     * Delete a chat session.
     * Note: Related data (messages, summaries) should be deleted by the service layer
     * before calling this method to respect the repository pattern.
     */
    fun deleteSession(sessionId: String): Boolean {
        log.debug("Deleting session $sessionId")
        val deleted = queries.deleteById(sessionId).value > 0
        log.debug("Deleted session $sessionId")
        return deleted
    }

    /** Delete all sessions (useful for testing). @return Number of deleted records. */
    fun deleteAll(): Int = queries.deleteAllSessions().value.toInt()

    /** Update the starred status of a session. */
    fun updateSessionStarred(sessionId: String, isStarred: Boolean): Boolean {
        val updated = queries.updateStarred(if (isStarred) 1L else 0L, Clock.System.now(), sessionId).value > 0
        if (updated) EventBus.post(PushDataToServerEvent(reason = "session starred"))
        return updated
    }

    /** Update the title of a session. */
    fun updateSessionTitle(sessionId: String, title: String): Boolean {
        val trimmedTitle = title.trim().take(SESSION_TITLE_MAX_LENGTH)
        if (trimmedTitle.isEmpty()) return false

        val updated = queries.updateTitle(trimmedTitle, Clock.System.now(), sessionId).value > 0
        if (updated) EventBus.post(PushDataToServerEvent(reason = "session title updated"))
        return updated
    }

    /**
     * Mark a session as user-renamed, suppressing future auto title-refresh from summarization.
     */
    fun markAsUserRenamed(sessionId: String): Boolean = queries.markUserRenamed(sessionId).value > 0

    /** Get all starred sessions, ordered by updated time (newest first). */
    fun getStarredSessions(): List<ChatSession> = queries.selectStarred().executeAsList().map { it.toChatSession() }

    /**
     * Get sessions not belonging to any project (general chat sessions) with a limit.
     * Sessions are ordered by updated time.
     */
    fun getSessionsWithoutProject(limit: Int, sortOrderDesc: Boolean = true): List<ChatSession> = if (sortOrderDesc) {
        queries.selectWithoutProjectOrderedDesc(limit.toLong())
    } else {
        queries.selectWithoutProjectOrderedAsc(limit.toLong())
    }.executeAsList().map { it.toChatSession() }

    /**
     * Search sessions by title (case-insensitive LIKE) that have no project, with pagination.
     */
    fun searchSessionsWithoutProject(
        titleQuery: String,
        page: Int = 1,
        pageSize: Int = 10,
        sortOrderDesc: Boolean = true,
    ): Pageable<ChatSession> {
        val pattern = "%${titleQuery.trim()}%"

        val totalItems = queries.searchWithoutProjectCount(pattern).executeAsOne().toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize) ?: return Pageable.empty(pageSize)

        val items = if (sortOrderDesc) {
            queries.searchWithoutProjectPagedDesc(pattern, pageSize.toLong(), pageParams.offset)
        } else {
            queries.searchWithoutProjectPagedAsc(pattern, pageSize.toLong(), pageParams.offset)
        }.executeAsList().map { it.toChatSession() }

        return Pageable(
            items = items,
            currentPage = pageParams.validPage,
            totalPages = pageParams.totalPages,
            totalItems = totalItems,
            pageSize = pageSize,
        )
    }

    /** Count sessions not belonging to any project. */
    fun countSessionsWithoutProject(): Int = queries.countWithoutProject().executeAsOne().toInt()

    /** Update the project of a session. */
    fun updateSessionProject(sessionId: String, projectId: String?): Boolean {
        val updated = queries.updateProject(projectId, Clock.System.now(), sessionId).value > 0
        if (updated) EventBus.post(PushDataToServerEvent(reason = "session project changed"))
        return updated
    }

    /** Get multiple sessions by their IDs. */
    fun getSessionsByIds(sessionIds: List<String>): List<ChatSession> {
        if (sessionIds.isEmpty()) return emptyList()
        return queries.selectByIds(sessionIds).executeAsList().map { it.toChatSession() }
    }

    /**
     * Upsert a batch of sessions received from the server during a pull.
     */
    fun upsertFromServer(sessions: List<ChatSession>) {
        if (sessions.isEmpty()) return

        db.transaction {
            val now = Clock.System.now()
            val ids = sessions.map { it.id }

            val existingById = queries.selectExistingByIds(ids).executeAsList()
                .associate { it.id to it.updated_at }

            for (session in sessions) {
                val storedUpdatedAt = existingById[session.id]

                try {
                    if (storedUpdatedAt == null) {
                        // Brand-new row — insert and mark as synced
                        queries.insertFromServer(
                            id = session.id,
                            title = session.title.take(SESSION_TITLE_MAX_LENGTH),
                            created_at = session.createdAt,
                            updated_at = session.updatedAt,
                            project_id = session.projectId,
                            directive_id = session.directiveId,
                            is_starred = if (session.isStarred) 1L else 0L,
                            active_resource_collection_ids = encodeResourceCollectionIds(session.activeResourceCollectionIds),
                            synced_at = now,
                        )
                        log.debug("upsertFromServer: inserted session {}", session.id)
                    } else if (session.updatedAt > storedUpdatedAt) {
                        // Server version is newer — overwrite
                        queries.updateFromServer(
                            title = session.title.take(SESSION_TITLE_MAX_LENGTH),
                            updatedAt = session.updatedAt,
                            projectId = session.projectId,
                            directiveId = session.directiveId,
                            isStarred = if (session.isStarred) 1L else 0L,
                            activeIds = encodeResourceCollectionIds(session.activeResourceCollectionIds),
                            syncedAt = now,
                            id = session.id,
                        )
                        log.debug("upsertFromServer: updated session {} (server newer)", session.id)
                    } else {
                        log.debug("upsertFromServer: skipped session {} (local is same age or newer)", session.id)
                    }
                } catch (e: Exception) {
                    log.error(
                        "upsertFromServer: failed to upsert session id={}, title='{}', projectId={}, " +
                            "directiveId={}, createdAt={}, updatedAt={}, storedUpdatedAt={}",
                        session.id,
                        session.title,
                        session.projectId,
                        session.directiveId,
                        session.createdAt,
                        session.updatedAt,
                        storedUpdatedAt,
                        e,
                    )
                    throw e
                }
            }
        }
    }

    /**
     * Mark a session as successfully synced to the server by setting `synced_at`
     * to the current timestamp.
     */
    fun markSynced(sessionId: String): Boolean = queries.markSynced(Clock.System.now(), sessionId).value > 0

    /**
     * @param limit Maximum rows to return in one batch.
     */
    fun getUnsyncedSessions(limit: Int = 50): List<ChatSession> = queries.selectAllOrderedByUpdatedAtAsc().executeAsList()
        .mapNotNull { row ->
            val updatedAt = row.updated_at
            val syncedAt = row.synced_at
            if (syncedAt == null || updatedAt > syncedAt) row.toChatSession() else null
        }
        .take(limit)
}
