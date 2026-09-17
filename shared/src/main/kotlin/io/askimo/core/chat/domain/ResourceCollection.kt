/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
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
    val isStarred: Boolean = false, // Pin to sidebar
    val isSystemCollection: Boolean = false, // Org-wide vs personal
)

const val COLLECTION_NAME_MAX_LENGTH = 256

/**
 * Exposed table definition for resource_collections.
 */
object ResourceCollectionsTable : Table("resource_collections") {
    val id = varchar("id", 36)
    val name = varchar("name", COLLECTION_NAME_MAX_LENGTH)
    val description = text("description").nullable()

    // JSON-serialized list of KnowledgeSourceConfig
    val knowledgeSourcesConfig = text("knowledge_sources_config").default("{}")

    val createdAt = sqliteInstant("created_at")
    val updatedAt = sqliteInstant("updated_at")

    val isStarred = integer("is_starred").default(0)
    val isSystemCollection = integer("is_system_collection").default(0)

    // Sync state (similar to projects)
    val syncedAt = varchar("synced_at", 32).nullable()

    override val primaryKey = PrimaryKey(id)
}
