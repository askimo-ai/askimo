/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.domain.WorkspaceTable
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.time.Instant

private fun ResultRow.toWorkspace(): Workspace = Workspace(
    id = this[WorkspaceTable.id],
    name = this[WorkspaceTable.name],
    path = this[WorkspaceTable.path],
    createdAt = this[WorkspaceTable.createdAt],
    lastUsedAt = this[WorkspaceTable.lastUsedAt],
    pinned = this[WorkspaceTable.pinned],
)

/**
 * Repository for persisting and querying [Workspace] entries — the folders that skills
 * are run against. Lets the UI list all known workspaces and switch between them,
 * instead of remembering only a single "last used" path.
 */
class WorkspaceRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    private val log = logger<WorkspaceRepository>()

    /** Returns all known workspaces — pinned first, then most-recently-used. */
    fun findAll(): List<Workspace> = transaction(database) {
        WorkspaceTable
            .selectAll()
            .orderBy(WorkspaceTable.pinned to SortOrder.DESC, WorkspaceTable.lastUsedAt to SortOrder.DESC)
            .map { it.toWorkspace() }
    }

    fun findById(id: String): Workspace? = transaction(database) {
        WorkspaceTable.selectAll().where { WorkspaceTable.id eq id }.map { it.toWorkspace() }.firstOrNull()
    }

    private fun canonicalPath(dir: File): String = dir.absoluteFile.normalize().path

    fun findByPath(dir: File): Workspace? = transaction(database) {
        WorkspaceTable.selectAll().where { WorkspaceTable.path eq canonicalPath(dir) }.map { it.toWorkspace() }.firstOrNull()
    }

    /**
     * Registers [dir] as a known workspace (creating it on first use) and bumps its
     * [Workspace.lastUsedAt] timestamp. Safe to call every time a workspace is opened/selected.
     */
    fun upsertByPath(dir: File, displayName: String? = null): Workspace {
        val path = canonicalPath(dir)
        val now = Instant.now()

        val existing = transaction(database) {
            WorkspaceTable.selectAll().where { WorkspaceTable.path eq path }.map { it.toWorkspace() }.firstOrNull()
        }
        if (existing != null) {
            transaction(database) {
                WorkspaceTable.update({ WorkspaceTable.id eq existing.id }) {
                    it[lastUsedAt] = now
                }
            }
            return existing.copy(lastUsedAt = now)
        }

        val workspace = Workspace(
            name = displayName?.trim()?.takeIf { it.isNotBlank() } ?: dir.name.ifBlank { path },
            path = path,
            createdAt = now,
            lastUsedAt = now,
        )
        transaction(database) {
            WorkspaceTable.insert {
                it[id] = workspace.id
                it[name] = workspace.name
                it[WorkspaceTable.path] = workspace.path
                it[createdAt] = workspace.createdAt
                it[lastUsedAt] = workspace.lastUsedAt
                it[pinned] = workspace.pinned
            }
        }
        log.debug("Registered new workspace '{}' at '{}'", workspace.name, workspace.path)
        return workspace
    }

    /** Bumps [Workspace.lastUsedAt] to now, without changing anything else. */
    fun touch(id: String) {
        transaction(database) {
            WorkspaceTable.update({ WorkspaceTable.id eq id }) {
                it[lastUsedAt] = Instant.now()
            }
        }
    }

    /** Renames a workspace's display name. Does not affect its filesystem path. */
    fun rename(id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        return transaction(database) {
            WorkspaceTable.update({ WorkspaceTable.id eq id }) {
                it[name] = trimmed
            }
        } > 0
    }

    /** Pins/unpins a workspace so it always sorts to the top of the list. */
    fun setPinned(id: String, pinned: Boolean) {
        transaction(database) {
            WorkspaceTable.update({ WorkspaceTable.id eq id }) {
                it[WorkspaceTable.pinned] = pinned
            }
        }
    }

    /** Removes the workspace reference only — does NOT delete the underlying folder on disk. */
    fun delete(id: String) {
        transaction(database) {
            WorkspaceTable.deleteWhere { WorkspaceTable.id eq id }
        }
        log.debug("Removed workspace reference '{}'", id)
    }
}
