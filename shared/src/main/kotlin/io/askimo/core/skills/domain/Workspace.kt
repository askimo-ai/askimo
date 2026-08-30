/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant
import java.util.UUID

/**
 * A persisted, user-visible workspace — a folder on disk that skills are run against.
 *
 * @param id          Unique identifier (UUID).
 * @param name        User-facing display name (defaults to the folder name, renamable).
 * @param path        Canonical absolute filesystem path — unique per workspace.
 * @param createdAt   When this workspace was first registered.
 * @param lastUsedAt  When this workspace was last selected/opened; drives recency sorting.
 * @param pinned      Whether the user pinned this workspace to the top of the list.
 */
data class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val createdAt: Instant = Instant.now(),
    val lastUsedAt: Instant = Instant.now(),
    val pinned: Boolean = false,
)

/**
 * Exposed table definition for `workspaces`.
 */
object WorkspaceTable : Table("workspaces") {
    val id = varchar("id", 36)
    val name = text("name")
    val path = text("path")
    val createdAt = sqliteInstant("created_at")
    val lastUsedAt = sqliteInstant("last_used_at")
    val pinned = bool("pinned").default(false)

    override val primaryKey = PrimaryKey(id)
}
