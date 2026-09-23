/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table

/**
 * The composite primary key (containerId, resourceId, filePath) isolates each
 * knowledge-source coordinator's state — multiple LocalFoldersIndexingCoordinator
 * instances for the same container can't overwrite each other's entries.
 */
object IndexFileStateTable : Table("index_file_state") {
    val containerId = varchar("container_id", 36)
    val resourceId = text("resource_id") // KnowledgeSourceConfig.resourceIdentifier (folder/file/URL path)
    val filePath = text("file_path") // Absolute file path or URL
    val fileHash = varchar("file_hash", 64).index() // change-detection key, indexed for fast lookups
    val sourceType = varchar("source_type", 20) // 'folders', 'files', or 'urls'
    val indexedAt = sqliteInstant("indexed_at")

    override val primaryKey = PrimaryKey(containerId, resourceId, filePath)

    init {
        index(isUnique = false, containerId, resourceId, sourceType)
    }
}
