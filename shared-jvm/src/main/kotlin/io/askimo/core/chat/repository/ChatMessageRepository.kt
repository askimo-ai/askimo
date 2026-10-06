/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.FileAttachment
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.truncatedForStorage
import io.askimo.core.context.MessageRole
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.PaginationDirection
import io.askimo.core.db.SearchSortBy
import io.askimo.core.db.sqldelight.Chat_messages
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

private val chatContentJson = Json { ignoreUnknownKeys = true }
private val log = logger<ChatMessageRepository>()

private fun decodeChatContentBlocks(raw: String?): List<TurnTimelineEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { chatContentJson.decodeFromString<List<TurnTimelineEntry>>(raw) }
        .onFailure { e -> log.warn("Failed to decode content_json: {}", e.message) }
        .getOrDefault(emptyList())
}

private fun encodeChatContentBlocks(blocks: List<TurnTimelineEntry>): String? = if (blocks.isEmpty()) null else chatContentJson.encodeToString(blocks.truncatedForStorage())

/**
 * Maps a generated [Chat_messages] row to the shared [ChatMessage] domain object. Attachments
 * are not populated here — callers must merge them in via [FileAttachment]
 * lookups (see [ChatMessageRepository.loadAttachmentsForMessageIds]).
 */
private fun Chat_messages.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    sessionId = session_id,
    role = MessageRole.entries.find { it.value == role } ?: MessageRole.USER,
    content = content,
    createdAt = created_at,
    isOutdated = is_outdated == 1L,
    editParentId = edit_parent_id,
    isEdited = is_edited == 1L,
    isFailed = is_failed == 1L,
    inputTokens = input_tokens?.toInt(),
    outputTokens = output_tokens?.toInt(),
    totalTokens = total_tokens?.toInt(),
    durationMs = duration_ms,
    isBookmarked = is_bookmarked == 1L,
    contentBlocks = decodeChatContentBlocks(content_json),
)

class ChatMessageRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
    private val attachmentRepository: ChatMessageAttachmentRepository = ChatMessageAttachmentRepository(databaseManager),
) : AbstractRepository(databaseManager) {

    private val queries get() = db.chatMessagesQueries

    /**
     * Inserts [message] into the local database.
     *
     * @param syncedAt When non-null, the `syncedAt` column is set during the INSERT so the
     *   message is immediately invisible to [getUnsyncedMessages]. Use this for messages that
     *   are already persisted server-side (e.g. via the Askimo Team proxy call) to avoid a
     *   separate UPDATE round-trip and a redundant sync push.
     */
    fun addMessage(message: ChatMessage, syncedAt: Instant? = null): ChatMessage {
        val messageWithInjectedFields = message.copy(
            id = message.id.ifBlank { UUID.randomUUID().toString() },
        )

        db.transaction {
            queries.insertMessage(
                id = messageWithInjectedFields.id,
                sessionId = messageWithInjectedFields.sessionId,
                role = messageWithInjectedFields.role.value,
                content = messageWithInjectedFields.content,
                createdAt = messageWithInjectedFields.createdAt,
                isOutdated = if (messageWithInjectedFields.isOutdated) 1L else 0L,
                editParentId = messageWithInjectedFields.editParentId,
                isEdited = if (messageWithInjectedFields.isEdited) 1L else 0L,
                isFailed = if (messageWithInjectedFields.isFailed) 1L else 0L,
                inputTokens = messageWithInjectedFields.inputTokens?.toLong(),
                outputTokens = messageWithInjectedFields.outputTokens?.toLong(),
                totalTokens = messageWithInjectedFields.totalTokens?.toLong(),
                durationMs = messageWithInjectedFields.durationMs,
                contentJson = encodeChatContentBlocks(messageWithInjectedFields.contentBlocks),
                syncedAt = syncedAt,
            )

            // Save attachments if any (with reference counting for shared storage)
            if (messageWithInjectedFields.attachments.isNotEmpty()) {
                attachmentRepository.addAttachments(
                    messageWithInjectedFields.id,
                    messageWithInjectedFields.sessionId,
                    messageWithInjectedFields.attachments,
                )
            }
        }

        EventBus.post(PushDataToServerEvent(reason = "message written"))
        return messageWithInjectedFields
    }

    /**
     * Bulk-insert a list of messages in a single transaction.
     * Intended for operations like session forking where all messages must be
     * written atomically.
     *
     * @param messages The messages to insert. Empty IDs are replaced with new UUIDs.
     * @return The inserted messages with their generated IDs.
     */
    fun addMessages(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val messagesWithIds = messages.map { message ->
            message.copy(id = message.id.ifBlank { UUID.randomUUID().toString() })
        }

        db.transaction {
            messagesWithIds.forEach { msg ->
                queries.insertMessage(
                    id = msg.id,
                    sessionId = msg.sessionId,
                    role = msg.role.value,
                    content = msg.content,
                    createdAt = msg.createdAt,
                    isOutdated = if (msg.isOutdated) 1L else 0L,
                    editParentId = msg.editParentId,
                    isEdited = if (msg.isEdited) 1L else 0L,
                    isFailed = if (msg.isFailed) 1L else 0L,
                    inputTokens = msg.inputTokens?.toLong(),
                    outputTokens = msg.outputTokens?.toLong(),
                    totalTokens = msg.totalTokens?.toLong(),
                    durationMs = msg.durationMs,
                    contentJson = encodeChatContentBlocks(msg.contentBlocks),
                    syncedAt = null,
                )

                if (msg.attachments.isNotEmpty()) {
                    attachmentRepository.addAttachments(
                        msg.id,
                        msg.sessionId,
                        msg.attachments.map { attachment ->
                            attachment.copy(
                                id = attachment.id.ifEmpty { UUID.randomUUID().toString() },
                            )
                        },
                    )
                }
            }
        }

        return messagesWithIds
    }

    fun getMessages(sessionId: String): List<ChatMessage> {
        val messages = queries.selectBySessionOrderedAsc(sessionId).executeAsList().map { it.toChatMessage() }

        val messageIds = messages.map { it.id }
        val attachmentsMap = loadAttachmentsForMessageIds(messageIds)

        return messages.map { message ->
            message.copy(attachments = attachmentsMap[message.id] ?: emptyList())
        }
    }

    /**
     * Counts messages with [role] across all sessions — e.g. total user prompts ever sent,
     */
    fun countByRole(role: MessageRole): Int = queries.countByRole(role.value).executeAsOne().toInt()

    /**
     * Get messages with cursor-based pagination
     * @param sessionId The session ID
     * @param limit Number of messages to retrieve (default: 20)
     * @param cursor The timestamp cursor for pagination. If null, starts from the beginning (oldest messages)
     * @param direction Direction of pagination: FORWARD (newer messages) or BACKWARD (older messages)
     * @return A pair of messages list and the next cursor (null if no more messages)
     */
    fun getMessagesPaginated(
        sessionId: String,
        limit: Int = 20,
        cursor: Instant? = null,
        direction: PaginationDirection = PaginationDirection.FORWARD,
    ): Pair<List<ChatMessage>, Instant?> {
        val fetchLimit = (limit + 1).toLong()

        val messages = when {
            cursor == null && direction == PaginationDirection.FORWARD ->
                queries.selectBySessionAscFromStart(sessionId, fetchLimit).executeAsList()

            cursor == null && direction == PaginationDirection.BACKWARD ->
                queries.selectBySessionDescFromEnd(sessionId, fetchLimit).executeAsList()

            direction == PaginationDirection.FORWARD ->
                queries.selectBySessionAfterCursorAsc(sessionId, cursor!!, fetchLimit).executeAsList()

            else ->
                queries.selectBySessionBeforeCursorDesc(sessionId, cursor!!, fetchLimit).executeAsList()
        }.map { it.toChatMessage() }

        // Check if there are more messages
        val hasMore = messages.size > limit
        val resultMessages = if (hasMore) messages.take(limit) else messages

        // Reverse if we fetched in backward direction to maintain chronological order
        val orderedMessages = if (direction == PaginationDirection.BACKWARD) resultMessages.reversed() else resultMessages

        // Load attachments using helper
        val messageIds = orderedMessages.map { it.id }
        val attachmentsMap = loadAttachmentsForMessageIds(messageIds)

        val messagesWithAttachments = orderedMessages.map { message ->
            message.copy(attachments = attachmentsMap[message.id] ?: emptyList())
        }

        // Calculate next cursor
        val nextCursor = if (hasMore && messagesWithAttachments.isNotEmpty()) {
            if (direction == PaginationDirection.FORWARD) {
                messagesWithAttachments.last().createdAt
            } else {
                messagesWithAttachments.first().createdAt
            }
        } else {
            null
        }

        return Pair(messagesWithAttachments, nextCursor)
    }

    /**
     * Search for messages across all sessions.
     *
     * @param query Search query string (case-insensitive)
     * @param startTime Optional start time filter (inclusive)
     * @param endTime Optional end time filter (inclusive)
     * @param projectId Optional project ID to filter by
     * @param sortBy Sort order for results (default: DATE_DESC)
     * @param limit Maximum number of results
     * @return List of messages matching the search criteria
     */
    fun searchMessages(
        query: String,
        startTime: Instant? = null,
        endTime: Instant? = null,
        projectId: String? = null,
        sortBy: SearchSortBy = SearchSortBy.DATE_DESC,
        limit: Int = 100,
    ): List<ChatMessage> {
        // Escape special SQL LIKE characters
        val escapedQuery = query.lowercase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val pattern = "%$escapedQuery%"

        val results = when (sortBy) {
            SearchSortBy.DATE_ASC -> queries.searchGlobalAsc(
                pattern = pattern,
                startTime = startTime,
                endTime = endTime,
                projectId = projectId,
                limit = limit.toLong(),
            )

            // RELEVANCE falls back to DATE_DESC for now.
            SearchSortBy.DATE_DESC, SearchSortBy.RELEVANCE -> queries.searchGlobalDesc(
                pattern = pattern,
                startTime = startTime,
                endTime = endTime,
                projectId = projectId,
                limit = limit.toLong(),
            )
        }

        return results.executeAsList().map { it.toChatMessage() }
    }

    /**
     * Search messages in a session by content.
     *
     * @param sessionId The session ID to search in
     * @param searchQuery The search query (case-insensitive)
     * @param limit Maximum number of results to return
     * @return List of messages matching the search query, ordered by creation time (oldest first)
     */
    fun searchMessages(
        sessionId: String,
        searchQuery: String,
        limit: Int = 100,
    ): List<ChatMessage> {
        if (searchQuery.isBlank()) return emptyList()

        val pattern = "%${searchQuery.lowercase()}%"
        val messages = queries.searchBySessionOrdered(sessionId, pattern, limit.toLong())
            .executeAsList().map { it.toChatMessage() }

        val messageIds = messages.map { it.id }
        val attachmentsMap = loadAttachmentsForMessageIds(messageIds)

        return messages.map { message ->
            message.copy(attachments = attachmentsMap[message.id] ?: emptyList())
        }
    }

    /**
     * Mark a single message as outdated.
     * This is used when editing a message to mark the original message as outdated.
     *
     * @param messageId The message ID to mark as outdated
     * @return Number of messages marked (should be 1)
     */
    fun markMessageAsOutdated(messageId: String): Int = queries.markOutdated(messageId).value.toInt()

    /**
     * Mark messages as outdated starting from a specific message (exclusive).
     * This is used when editing a message to mark all subsequent messages as outdated.
     *
     * @param sessionId The session ID
     * @param fromMessageId The message ID from which to start marking as outdated (this message itself is not marked)
     * @return Number of messages marked as outdated
     */
    fun markMessagesAsOutdatedAfter(sessionId: String, fromMessageId: String): Int {
        val fromTimestamp = queries.selectCreatedAtById(fromMessageId).executeAsOneOrNull() ?: return 0
        return queries.markOutdatedFrom(sessionId, fromTimestamp).value.toInt()
    }

    /**
     * Get the most recent active (non-outdated) messages for a session, limited to a specified count.
     * Messages are sorted by creation time descending and limited in the database query for efficiency.
     *
     * @param sessionId The session ID
     * @param limit Maximum number of messages to return (default 50)
     * @return List of recent active messages, ordered by creation time (oldest first)
     */
    fun getRecentActiveMessages(sessionId: String, limit: Int = 50): List<ChatMessage> {
        val messages = queries.selectRecentActive(sessionId, limit.toLong())
            .executeAsList().map { it.toChatMessage() }
            .reversed()

        val messageIds = messages.map { it.id }
        val attachmentsMap = loadAttachmentsForMessageIds(messageIds)

        return messages.map { message ->
            message.copy(attachments = attachmentsMap[message.id] ?: emptyList())
        }
    }

    /**
     * Update the content of a message and mark it as edited.
     * This is used when a user edits an AI response message.
     *
     * Clears any persisted `content_json` (tool-call/text timeline) — once the text is
     * user-edited, the recorded interleaving no longer matches the displayed content, so
     * the message falls back to plain-text rendering instead of showing a stale tool-call
     * timeline alongside the new text.
     *
     * @param messageId The ID of the message to update
     * @param newContent The new content for the message
     * @return Number of messages updated (should be 1)
     */
    fun updateMessageContent(messageId: String, newContent: String): Int = queries.updateContent(newContent, messageId).value.toInt()

    /**
     * Delete all messages for a session.
     * Attachments are cleaned up via reference counting - physical files only deleted if ref count reaches 0.
     */
    fun deleteMessagesBySession(sessionId: String): Int = db.transactionWithResult {
        // Get all message IDs for this session first
        val messageIds = queries.selectIdsBySession(sessionId).executeAsList()

        // Clean up attachments (decrements ref count, deletes file if needed)
        // Use internal method to avoid nested transactions
        messageIds.forEach { messageId ->
            attachmentRepository.deleteAttachmentsByMessageIdInternal(messageId)
        }

        // Then delete messages
        queries.deleteBySession(sessionId).value.toInt()
    }

    /**
     * Permanently delete individual messages by their IDs.
     * Attachments are cleaned up via reference counting - physical files only deleted if ref count reaches 0.
     *
     * @param messageIds IDs of the messages to delete.
     * @return Number of rows deleted.
     */
    fun bulkDelete(messageIds: List<String>): Int {
        if (messageIds.isEmpty()) return 0
        return db.transactionWithResult {
            // Clean up attachments first (decrements ref count, deletes file if needed)
            // Use internal method to avoid nested transactions
            messageIds.forEach { messageId ->
                attachmentRepository.deleteAttachmentsByMessageIdInternal(messageId)
            }

            // Then delete messages
            queries.deleteByIds(messageIds).value.toInt()
        }
    }

    /**
     * Helper method to load attachments for messages using LEFT JOIN.
     * This performs a single database query to efficiently load all attachments via reference table.
     *
     * @param messageIds List of message IDs to load attachments for
     * @return Map of message ID to list of attachments
     */
    private fun loadAttachmentsForMessageIds(messageIds: List<String>): Map<String, List<FileAttachment>> {
        if (messageIds.isEmpty()) return emptyMap()

        val attachmentsMap = mutableMapOf<String, MutableList<FileAttachment>>()

        db.attachmentReferencesQueries.selectAttachmentsForMessageIds(messageIds).executeAsList().forEach { row ->
            if (row.id != null) {
                val attachment = FileAttachment(
                    id = row.id,
                    fileName = row.file_name!!,
                    mimeType = row.mime_type!!,
                    size = row.size!!,
                    createdAt = row.created_at!!,
                    storagePath = row.storage_path,
                    content = null,
                )
                attachmentsMap.getOrPut(row.message_id) { mutableListOf() }.add(attachment)
            }
        }

        return attachmentsMap.mapValues { it.value.toList() }
    }

    /**
     * @param messages Messages received from the server pull response.
     */
    fun bulkUpsert(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        db.transaction {
            // Pre-filter: only upsert messages whose session already exists locally.
            // Messages referencing an unknown session would violate the FK constraint
            // (sessionId → chat_sessions.id CASCADE). They will be retried on the
            // next sync once the session row is present.
            val requestedSessionIds = messages.map { it.sessionId }.toSet()
            val knownSessionIds = db.chatSessionsQueries.selectByIds(requestedSessionIds.toList())
                .executeAsList().map { it.id }.toSet()

            val skipped = requestedSessionIds - knownSessionIds
            if (skipped.isNotEmpty()) {
                log.warn(
                    "bulkUpsert: deferring {} message(s) — sessions not yet local: {}",
                    messages.count { it.sessionId in skipped },
                    skipped,
                )
            }

            val safeMessages = messages.filter { it.sessionId in knownSessionIds }
            if (safeMessages.isEmpty()) return@transaction

            for (message in safeMessages) {
                val existing = queries.selectMessageById(message.id).executeAsOneOrNull()
                val isOutdated = if (message.isOutdated) 1L else 0L
                val isEdited = if (message.isEdited) 1L else 0L
                val isFailed = if (message.isFailed) 1L else 0L
                val contentJson = encodeChatContentBlocks(message.contentBlocks)
                val syncedAt = message.createdAt

                if (existing == null) {
                    queries.insertMessage(
                        id = message.id,
                        sessionId = message.sessionId,
                        role = message.role.value,
                        content = message.content,
                        createdAt = message.createdAt,
                        isOutdated = isOutdated,
                        editParentId = message.editParentId,
                        isEdited = isEdited,
                        isFailed = isFailed,
                        inputTokens = message.inputTokens?.toLong(),
                        outputTokens = message.outputTokens?.toLong(),
                        totalTokens = message.totalTokens?.toLong(),
                        durationMs = message.durationMs,
                        contentJson = contentJson,
                        syncedAt = syncedAt,
                    )
                } else {
                    queries.updateMessageFromServer(
                        sessionId = message.sessionId,
                        role = message.role.value,
                        content = message.content,
                        createdAt = message.createdAt,
                        isOutdated = isOutdated,
                        editParentId = message.editParentId,
                        isEdited = isEdited,
                        isFailed = isFailed,
                        inputTokens = message.inputTokens?.toLong(),
                        outputTokens = message.outputTokens?.toLong(),
                        totalTokens = message.totalTokens?.toLong(),
                        durationMs = message.durationMs,
                        contentJson = contentJson,
                        syncedAt = syncedAt,
                        id = message.id,
                    )
                }
            }
        }
    }

    /**
     *
     * @param messageId The message to mark as synced.
     */
    fun markSynced(messageId: String): Boolean = queries.markSyncedMessage(Clock.System.now(), messageId).value > 0

    /**
     * @param limit Maximum rows to return in one batch.
     */
    fun getUnsyncedMessages(limit: Int = 500): List<ChatMessage> = queries.selectUnsynced(limit.toLong()).executeAsList().map { it.toChatMessage() }

    /**
     *
     * @param sessionId Session to query.
     * @param limit     Maximum rows to return in one batch.
     */
    fun getUnsyncedMessages(sessionId: String, limit: Int = 100): List<ChatMessage> = queries.selectUnsyncedBySession(sessionId, limit.toLong()).executeAsList().map { it.toChatMessage() }

    /**
     * Toggle the bookmark state of a message.
     * @return true if the message is now bookmarked, false if it is now un-bookmarked.
     */
    fun toggleBookmark(messageId: String): Boolean = db.transactionWithResult {
        val current = queries.selectBookmarkFlag(messageId).executeAsOneOrNull()?.is_bookmarked ?: 0L

        val next = if (current == 1L) 0L else 1L
        queries.updateBookmark(next, messageId)
        next == 1L
    }

    /**
     * Return all bookmarked messages for a single session, ordered by creation time.
     */
    fun getBookmarkedMessages(sessionId: String): List<ChatMessage> = queries.selectBookmarkedBySession(sessionId).executeAsList().map { it.toChatMessage() }

    /**
     * Return all bookmarked messages across every session, ordered newest-first.
     * Used by the global Bookmarks view.
     */
    fun getAllBookmarkedMessages(): List<ChatMessage> = queries.selectAllBookmarked().executeAsList().map { it.toChatMessage() }

    /**
     * Return all bookmarked messages joined with their parent sessions in a **single** query,
     * ordered by session.updated_at DESC then message.created_at ASC.
     *
     * This avoids the two-round-trip pattern of fetching messages first and then fetching
     * sessions by ID. The caller can group the flat list in-memory while retaining the
     * DB-provided ordering.
     */
    fun getAllBookmarkedWithSessions(): List<Pair<ChatMessage, ChatSession>> = queries.selectAllBookmarkedWithSessions().executeAsList().map { row ->
        val message = ChatMessage(
            id = row.msg_id,
            sessionId = row.msg_session_id,
            role = MessageRole.entries.find { it.value == row.msg_role } ?: MessageRole.USER,
            content = row.msg_content,
            createdAt = row.msg_created_at,
            isOutdated = row.msg_is_outdated == 1L,
            editParentId = row.msg_edit_parent_id,
            isEdited = row.msg_is_edited == 1L,
            isFailed = row.msg_is_failed == 1L,
            inputTokens = row.msg_input_tokens?.toInt(),
            outputTokens = row.msg_output_tokens?.toInt(),
            totalTokens = row.msg_total_tokens?.toInt(),
            durationMs = row.msg_duration_ms,
            isBookmarked = row.msg_is_bookmarked == 1L,
            contentBlocks = decodeChatContentBlocks(row.msg_content_json),
        )
        val session = ChatSession(
            id = row.session_id,
            title = row.session_title,
            createdAt = row.session_created_at,
            updatedAt = row.session_updated_at,
            projectId = row.session_project_id,
            directiveId = row.session_directive_id,
            isStarred = row.session_is_starred == 1L,
        )
        message to session
    }

    /**
     * Return a map of sessionId → bookmark count for every session that has at least one
     * bookmarked message. Uses a single query + in-memory grouping.
     * Used by the sidebar to render the 🔖 N badge efficiently.
     */
    fun getBookmarkCountsBySession(): Map<String, Int> = queries.selectBookmarkCountsBySession().executeAsList()
        .associate { it.session_id to it.cnt.toInt() }
}
