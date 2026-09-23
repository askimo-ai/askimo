/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import io.askimo.core.rag.state.IndexStatus
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * A collection of indexed knowledge resources (files, URLs, documents).
 * Collections are reusable across sessions and can be used on-demand via @mention
 * or persistently via chip selection in any chat.
 *
 * Collections are independent of projects — they exist to provide RAG context
 * to any session that requests them.
 */
data class ResourceCollection(
    val id: String,
    val name: String, // "HR Handbook", "Tech Docs", etc
    val description: String? = null,
    val knowledgeSources: List<KnowledgeSourceConfig> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val isSystemCollection: Boolean = false, // Org-wide vs personal
    /** Last known indexing status, persisted so list/detail views show it without a live event. */
    val indexStatus: IndexStatus = IndexStatus.NOT_STARTED,
    /** Timestamp of the last successful index completion, or null if never indexed. */
    val lastIndexedAt: Instant? = null,
    /** Error from the last failed indexing attempt, or null if it succeeded (or never ran). */
    val indexError: String? = null,
)

const val COLLECTION_NAME_MAX_LENGTH = 256

/**
 * Exposed table definition for resource_collections.
 */
object ResourceCollectionsTable : Table("resource_collections") {
    val id = varchar("id", 36)
    val name = varchar("name", COLLECTION_NAME_MAX_LENGTH)
    val description = text("description").nullable()

    // JSON-serialized KnowledgeSourceConfig list
    val knowledgeSourcesConfig = text("knowledge_sources_config").default("{}")

    val createdAt = sqliteInstant("created_at")
    val updatedAt = sqliteInstant("updated_at")

    val isSystemCollection = integer("is_system_collection").default(0)

    // Sync state (mirrors projects)
    val syncedAt = varchar("synced_at", 32).nullable()

    // Persisted indexing status (mirrors IndexStatus name)
    val indexStatus = varchar("index_status", 32).default(IndexStatus.NOT_STARTED.name)
    val lastIndexedAt = sqliteInstant("last_indexed_at").nullable()
    val indexError = text("index_error").nullable()

    override val primaryKey = PrimaryKey(id)
}
