/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table

/**
 *
 * The composite primary key (projectId, resourceId, filePath) ensures each
 * knowledge-source coordinator has its own isolated state — multiple
 * LocalFoldersIndexingCoordinator instances for the same project cannot
 * overwrite each other's entries.
 */
object IndexFileStateTable : Table("index_file_state") {
    val projectId = varchar("project_id", 36)
    val resourceId = text("resource_id") // KnowledgeSourceConfig.resourceIdentifier (folder/file/URL path)
    val filePath = text("file_path") // Absolute file path or URL
    val fileHash = varchar("file_hash", 64).index() // change-detection key, indexed for fast lookups
    val sourceType = varchar("source_type", 20) // 'folders', 'files', or 'urls'
    val indexedAt = sqliteInstant("indexed_at")

    override val primaryKey = PrimaryKey(projectId, resourceId, filePath)

    init {
        index(isUnique = false, projectId, resourceId, sourceType)
    }
}
