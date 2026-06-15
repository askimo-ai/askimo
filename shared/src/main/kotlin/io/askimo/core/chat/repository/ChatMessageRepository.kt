/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatMessageAttachmentsTable
import io.askimo.core.chat.domain.ChatMessagesTable
import io.askimo.core.chat.domain.ChatSessionsTable
import io.askimo.core.chat.domain.FileAttachment
import io.askimo.core.context.MessageRole
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

enum class PaginationDirection {
    FORWARD,
    BACKWARD,
}

/**
 * Sort options for search results.
 */
enum class SearchSortBy {
    DATE_DESC, // Newest first (default)
    DATE_ASC, // Oldest first
    RELEVANCE, // For future use if relevance scoring is added
}

/**
 * Extension function to map an Exposed ResultRow to a ChatMessage object.
 */
private fun ResultRow.toChatMessage(): ChatMessage = ChatMessage(
    id = this[ChatMessagesTable.id],
    sessionId = this[ChatMessagesTable.sessionId],
    role = MessageRole.entries.find { it.value == this[ChatMessagesTable.role] } ?: MessageRole.USER,
    content = this[ChatMessagesTable.content],
    createdAt = this[ChatMessagesTable.createdAt],
    isOutdated = this[ChatMessagesTable.isOutdated] == 1,
    editParentId = this[ChatMessagesTable.editParentId],
    isEdited = this[ChatMessagesTable.isEdited] == 1,
    isFailed = this[ChatMessagesTable.isFailed] == 1,
    inputTokens = this[ChatMessagesTable.inputTokens],
    outputTokens = this[ChatMessagesTable.outputTokens],
    totalTokens = this[ChatMessagesTable.totalTokens],
    durationMs = this[ChatMessagesTable.durationMs],
)

/**
 * Extension function to map an Exposed ResultRow to a FileAttachment object (from JOIN).
 */
private fun ResultRow.toFileAttachment(): FileAttachment = FileAttachment(
    id = this[ChatMessageAttachmentsTable.id],
    messageId = this[ChatMessageAttachmentsTable.messageId],
    sessionId = this[ChatMessageAttachmentsTable.sessionId],
    fileName = this[ChatMessageAttachmentsTable.fileName],
    mimeType = this[ChatMessageAttachmentsTable.mimeType],
    size = this[ChatMessageAttachmentsTable.size],
    createdAt = this[ChatMessageAttachmentsTable.createdAt],
    content = null, // Content is not stored in DB
)

class ChatMessageRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
    private val attachmentRepository: ChatMessageAttachmentRepository = ChatMessageAttachmentRepository(databaseManager),
) : AbstractSQLiteRepository(databaseManager) {

    private val log = logger<ChatMessageRepository>()

    fun addMessage(message: ChatMessage): ChatMessage {
        val messageWithInjectedFields = message.copy(
            id = message.id.ifBlank { UUID.randomUUID().toString() },
        )

        transaction(database) {
            ChatMessagesTable.insert {
                it[id] = messageWithInjectedFields.id
                it[ChatMessagesTable.sessionId] = messageWithInjectedFields.sessionId
                it[ChatMessagesTable.role] = messageWithInjectedFields.role.value
                it[ChatMessagesTable.content] = messageWithInjectedFields.content
                it[createdAt] = messageWithInjectedFields.createdAt
                it[ChatMessagesTable.isOutdated] = if (messageWithInjectedFields.isOutdated) 1 else 0
                it[ChatMessagesTable.editParentId] = messageWithInjectedFields.editParentId
                it[ChatMessagesTable.isEdited] = if (messageWithInjectedFields.isEdited) 1 else 0
                it[ChatMessagesTable.isFailed] = if (messageWithInjectedFields.isFailed) 1 else 0
                it[ChatMessagesTable.inputTokens] = messageWithInjectedFields.inputTokens
                it[ChatMessagesTable.outputTokens] = messageWithInjectedFields.outputTokens
                it[ChatMessagesTable.totalTokens] = messageWithInjectedFields.totalTokens
                it[ChatMessagesTable.durationMs] = messageWithInjectedFields.durationMs
            }

            // Save attachments if any
            if (messageWithInjectedFields.attachments.isNotEmpty()) {
                val attachmentsWithMessageId = messageWithInjectedFields.attachments.map { attachment ->
                    attachment.copy(
                        messageId = messageWithInjectedFields.id,
                        sessionId = messageWithInjectedFields.sessionId,
                    )
                }
                attachmentRepository.addAttachments(attachmentsWithMessageId)
            }
        }

        EventBus.post(PushDataToServerEvent(reason = "message written"))
        return messageWithInjectedFields
    }

    fun getMessages(sessionId: String): List<ChatMessage> = transaction(database) {
        val messages = ChatMessagesTable
            .selectAll()
            .where { ChatMessagesTable.sessionId eq sessionId }
            .orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
            .map { it.toChatMessage() }

        val messageIds = messages.map { it.id }
        val attachmentsMap = loadAttachmentsForMessageIds(messageIds)

        messages.map { message ->
            message.copy(attachments = attachmentsMap[message.id] ?: emptyList())
        }
    }

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
    ): Pair<List<ChatMessage>, Instant?> = transaction(database) {
        val query = ChatMessagesTable.selectAll()
            .where { ChatMessagesTable.sessionId eq sessionId }

        // Apply cursor filtering and ordering based on direction
        val orderedQuery = when {
            cursor == null && direction == PaginationDirection.FORWARD -> {
                // Start from the beginning (oldest messages)
                query.orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
            }

            cursor == null && direction == PaginationDirection.BACKWARD -> {
                // Start from the end (newest messages)
                query.orderBy(ChatMessagesTable.createdAt, SortOrder.DESC)
            }

            direction == PaginationDirection.FORWARD -> {
                // Get messages after the cursor (newer messages)
                query
                    .andWhere { ChatMessagesTable.createdAt greater cursor!! }
                    .orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
            }

            else -> {
                // Get messages before the cursor (older messages)
                query
                    .andWhere { ChatMessagesTable.createdAt less cursor!! }
                    .orderBy(ChatMessagesTable.createdAt, SortOrder.DESC)
            }
        }

        val messages = orderedQuery
            .limit(limit + 1)
            .map { it.toChatMessage() }

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

        Pair(messagesWithAttachments, nextCursor)
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
    ): List<ChatMessage> = transaction(database) {
        // Escape special SQL LIKE characters
        val escapedQuery = query.lowercase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

        var selectQuery = ChatMessagesTable
            .selectAll()
            .where { ChatMessagesTable.content.lowerCase() like "%$escapedQuery%" }

        // Apply time filters if provided
        if (startTime != null) {
            val startDateTime = startTime
            selectQuery = selectQuery.andWhere { ChatMessagesTable.createdAt greaterEq startDateTime }
        }
        if (endTime != null) {
            val endDateTime = endTime
            selectQuery = selectQuery.andWhere { ChatMessagesTable.createdAt lessEq endDateTime }
        }

        // Apply project filter if provided
        if (projectId != null) {
            val sessionIds = ChatSessionsTable
                .selectAll()
                .where { ChatSessionsTable.projectId eq projectId }
                .map { it[ChatSessionsTable.id] }

            if (sessionIds.isEmpty()) {
                return@transaction emptyList()
            }

            selectQuery = selectQuery.andWhere { ChatMessagesTable.sessionId inList sessionIds }
        }

        // Apply sorting at database level - Exposed supports this natively!
        selectQuery = when (sortBy) {
            SearchSortBy.DATE_DESC -> selectQuery.orderBy(ChatMessagesTable.createdAt, SortOrder.DESC)

            SearchSortBy.DATE_ASC -> selectQuery.orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)

            SearchSortBy.RELEVANCE -> {
                // For now, fall back to DATE_DESC
                // In future, could add SQL CASE WHEN for relevance scoring
                selectQuery.orderBy(ChatMessagesTable.createdAt, SortOrder.DESC)
            }
        }

        // Limit results and return
        selectQuery
            .limit(limit)
            .map { it.toChatMessage() }
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

        return transaction(database) {
            val messages = ChatMessagesTable
                .selectAll()
                .where {
                    (ChatMessagesTable.sessionId eq sessionId) and
                        ChatMessagesTable.content.lowerCase().like("%${searchQuery.lowercase()}%")
                }
                .orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
                .limit(limit)
                .map { it.toChatMessage() }

            val messageIds = messages.map { it.id }
            val attachmentsMap = loadAttachmentsForMessageIds(messageIds)

            messages.map { message ->
                message.copy(attachments = attachmentsMap[message.id] ?: emptyList())
            }
        }
    }

    /**
     * Mark a single message as outdated.
     * This is used when editing a message to mark the original message as outdated.
     *
     * @param messageId The message ID to mark as outdated
     * @return Number of messages marked (should be 1)
     */
    fun markMessageAsOutdated(messageId: String): Int = transaction(database) {
        ChatMessagesTable.update({ ChatMessagesTable.id eq messageId }) {
            it[isOutdated] = 1
        }
    }

    /**
     * Mark messages as outdated starting from a specific message (exclusive).
     * This is used when editing a message to mark all subsequent messages as outdated.
     *
     * @param sessionId The session ID
     * @param fromMessageId The message ID from which to start marking as outdated (this message itself is not marked)
     * @return Number of messages marked as outdated
     */
    fun markMessagesAsOutdatedAfter(sessionId: String, fromMessageId: String): Int = transaction(database) {
        val fromTimestamp: Instant? = ChatMessagesTable
            .selectAll()
            .where { ChatMessagesTable.id eq fromMessageId }
            .singleOrNull()
            ?.get(ChatMessagesTable.createdAt)

        if (fromTimestamp == null) return@transaction 0

        ChatMessagesTable.update({
            (ChatMessagesTable.sessionId eq sessionId) and
                (ChatMessagesTable.createdAt greaterEq fromTimestamp)
        }) {
            it[isOutdated] = 1
        }
    }

    /**
     * Get the most recent active (non-outdated) messages for a session, limited to a specified count.
     * Messages are sorted by creation time descending and limited in the database query for efficiency.
     *
     * @param sessionId The session ID
     * @param limit Maximum number of messages to return (default 50)
     * @return List of recent active messages, ordered by creation time (oldest first)
     */
    fun getRecentActiveMessages(sessionId: String, limit: Int = 50): List<ChatMessage> = transaction(database) {
        val messages = ChatMessagesTable
            .selectAll()
            .where {
                (ChatMessagesTable.sessionId eq sessionId) and
                    (ChatMessagesTable.isOutdated eq 0)
            }
            .orderBy(ChatMessagesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toChatMessage() }
            .reversed()

        val messageIds = messages.map { it.id }
        val attachmentsMap = loadAttachmentsForMessageIds(messageIds)

        messages.map { message ->
            message.copy(attachments = attachmentsMap[message.id] ?: emptyList())
        }
    }

    /**
     * Update the content of a message and mark it as edited.
     * This is used when a user edits an AI response message.
     *
     * @param messageId The ID of the message to update
     * @param newContent The new content for the message
     * @return Number of messages updated (should be 1)
     */
    fun updateMessageContent(messageId: String, newContent: String): Int = transaction(database) {
        ChatMessagesTable.update({ ChatMessagesTable.id eq messageId }) {
            it[content] = newContent
            it[isEdited] = 1
        }
    }

    /**
     * Delete all messages for a session.
     * Attachments are automatically deleted via CASCADE foreign key constraint.
     */
    fun deleteMessagesBySession(sessionId: String): Int = transaction(database) {
        ChatMessagesTable.deleteWhere { ChatMessagesTable.sessionId eq sessionId }
    }

    /**
     * Permanently delete individual messages by their IDs.
     *
     * @param messageIds IDs of the messages to delete.
     * @return Number of rows deleted.
     */
    fun bulkDelete(messageIds: List<String>): Int {
        if (messageIds.isEmpty()) return 0
        return transaction(database) {
            ChatMessagesTable.deleteWhere { ChatMessagesTable.id inList messageIds }
        }
    }

    /**
     * Helper method to load attachments for messages using LEFT JOIN.
     * This performs a single database query to efficiently load all attachments.
     *
     * @param messageIds List of message IDs to load attachments for
     * @return Map of message ID to list of attachments
     */
    private fun loadAttachmentsForMessageIds(messageIds: List<String>): Map<String, List<FileAttachment>> {
        if (messageIds.isEmpty()) return emptyMap()

        val messagesMap = mutableMapOf<String, ChatMessage>()
        val attachmentsMap = mutableMapOf<String, MutableList<FileAttachment>>()

        (ChatMessagesTable leftJoin ChatMessageAttachmentsTable)
            .selectAll()
            .where { ChatMessagesTable.id inList messageIds }
            .forEach { row ->
                val messageId = row[ChatMessagesTable.id]

                if (!messagesMap.containsKey(messageId)) {
                    messagesMap[messageId] = row.toChatMessage()
                }

                row.getOrNull(ChatMessageAttachmentsTable.id)?.let {
                    attachmentsMap.getOrPut(messageId) { mutableListOf() }
                        .add(row.toFileAttachment())
                }
            }

        return attachmentsMap.mapValues { it.value.toList() }
    }

    /**
     * @param messages Messages received from the server pull response.
     */
    fun bulkUpsert(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        transaction(database) {
            // Pre-filter: only upsert messages whose session already exists locally.
            // Messages referencing an unknown session would violate the FK constraint
            // (sessionId → chat_sessions.id CASCADE). They will be retried on the
            // next sync once the session row is present.
            val requestedSessionIds = messages.map { it.sessionId }.toSet()
            val knownSessionIds = ChatSessionsTable
                .selectAll()
                .where { ChatSessionsTable.id inList requestedSessionIds }
                .map { it[ChatSessionsTable.id] }
                .toSet()

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
                ChatMessagesTable.upsert {
                    it[id] = message.id
                    it[sessionId] = message.sessionId
                    it[role] = message.role.value
                    it[content] = message.content
                    it[createdAt] = message.createdAt
                    it[isOutdated] = if (message.isOutdated) 1 else 0
                    it[editParentId] = message.editParentId
                    it[isEdited] = if (message.isEdited) 1 else 0
                    it[isFailed] = if (message.isFailed) 1 else 0
                    it[inputTokens] = message.inputTokens
                    it[outputTokens] = message.outputTokens
                    it[totalTokens] = message.totalTokens
                    it[durationMs] = message.durationMs
                    it[syncedAt] = message.createdAt.toString()
                }
            }
        }
    }

    /**
     *
     * @param messageId The message to mark as synced.
     */
    fun markSynced(messageId: String): Boolean = transaction(database) {
        ChatMessagesTable.update({ ChatMessagesTable.id eq messageId }) {
            it[syncedAt] = Instant.now().toString()
        } > 0
    }

    /**
     * @param limit Maximum rows to return in one batch.
     */
    fun getUnsyncedMessages(limit: Int = 500): List<ChatMessage> = transaction(database) {
        ChatMessagesTable
            .selectAll()
            .where {
                (ChatMessagesTable.isOutdated eq 0) and
                    (ChatMessagesTable.syncedAt.isNull())
            }
            .orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
            .limit(limit)
            .map { it.toChatMessage() }
    }

    /**
     *
     * @param sessionId Session to query.
     * @param limit     Maximum rows to return in one batch.
     */
    fun getUnsyncedMessages(sessionId: String, limit: Int = 100): List<ChatMessage> = transaction(database) {
        ChatMessagesTable
            .selectAll()
            .where {
                (ChatMessagesTable.sessionId eq sessionId) and
                    (ChatMessagesTable.isOutdated eq 0) and
                    (ChatMessagesTable.syncedAt.isNull())
            }
            .orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
            .limit(limit)
            .map { it.toChatMessage() }
    }
}
