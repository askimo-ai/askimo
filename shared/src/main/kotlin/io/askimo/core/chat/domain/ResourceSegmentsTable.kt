/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table

object ResourceSegmentsTable : Table("file_segments") {
    val projectId = varchar("project_id", 256) // Project ID - controlled identifier
    val resourceId = varchar("file_path", 2048) // Resource identifier (file path, URL, etc.) - column name kept as 'file_path' for backward compatibility
    val segmentId = varchar("segment_id", 256) // Deterministic segment ID: projectId:fileHash:chunkIndex (max ~56 chars)
    val chunkIndex = integer("chunk_index") // Index of this chunk within the resource (for ordering)
    val createdAt = sqliteInstant("created_at") // When this segment was created

    override val primaryKey = PrimaryKey(projectId, resourceId, segmentId)

    init {
        index(isUnique = false, projectId, resourceId)
    }
}
