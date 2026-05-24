/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Domain model for session memory persistence.
 * Stores the serialized state of TokenAwareSummarizingMemory for a chat session.
 *
 * @property sessionId Unique identifier for the chat session
 * @property memorySummary JSON serialized SessionConversationSummary (nullable)
 * @property memoryMessages JSON serialized List<ChatMessage> from LangChain4j
 * @property lastUpdated Timestamp of last memory update
 * @property createdAt Timestamp when memory was first created
 */
data class SessionMemory(
    val sessionId: String,
    val memorySummary: String?,
    val memoryMessages: String,
    val lastUpdated: Instant = Instant.now(),
    val createdAt: Instant = Instant.now(),
)

/**
 * Exposed table definition for session_memory.
 */
object SessionMemoryTable : Table("session_memory") {
    val sessionId = varchar("session_id", 255).references(
        ChatSessionsTable.id,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE,
    )
    val memorySummary = text("memory_summary").nullable()
    val memoryMessages = text("memory_messages")
    val lastUpdated = sqliteInstant("last_updated")
    val createdAt = sqliteInstant("created_at")

    override val primaryKey = PrimaryKey(sessionId)
}
