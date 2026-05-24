/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Represents a project that groups chat sessions and provides RAG context
 * through indexed knowledge sources (files, web pages, etc.).
 *
 * Projects enable knowledge base organization:
 * - Each project has its own Lucene index for RAG
 * - Sessions belong to projects to share project-level context
 * - Knowledge sources define where content comes from (local files, web, SEC, etc.)
 */
data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val knowledgeSources: List<KnowledgeSourceConfig>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isStarred: Boolean = false,
)

/**
 * Exposed table definition for projects.
 * Co-located with domain class for easier maintenance and foreign key references.
 */
object ProjectsTable : Table("projects") {
    val id = varchar("id", 36)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val knowledgeSourcesConfig = text("indexed_paths")
    val createdAt = sqliteInstant("created_at")
    val updatedAt = sqliteInstant("updated_at")
    val syncedAt = varchar("synced_at", 32).nullable()
    val isStarred = integer("is_starred").default(0)

    override val primaryKey = PrimaryKey(id)
}
