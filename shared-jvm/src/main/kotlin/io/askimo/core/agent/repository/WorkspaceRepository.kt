/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.domain.Workspace
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Workspaces
import io.askimo.core.logging.logger
import io.askimo.core.util.TimeUtil
import java.io.File
import kotlin.time.Clock

private fun Workspaces.toWorkspace(): Workspace = Workspace(
    id = id,
    name = name,
    path = path,
    createdAt = TimeUtil.parseInstant(created_at),
    lastUsedAt = TimeUtil.parseInstant(last_used_at),
    pinned = pinned == 1L,
)

class WorkspaceRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val log = logger<WorkspaceRepository>()
    private val queries get() = db.workspacesQueries

    /** Returns all known workspaces — pinned first, then most-recently-used. */
    fun findAll(): List<Workspace> = queries.selectAllWorkspaces().executeAsList().map { it.toWorkspace() }

    fun findById(id: String): Workspace? = queries.selectWorkspaceById(id).executeAsOneOrNull()?.toWorkspace()

    /**
     * Returns the workspace with the most recent [Workspace.lastUsedAt], ignoring [Workspace.pinned]
     * — used to resume the workspace the user was actually last working in on app start.
     * [findAll]'s pinned-first ordering is for the picker list only; resolving "current workspace"
     * must not jump to a pinned-but-unopened workspace ahead of the one actually last used.
     */
    fun findMostRecentlyUsed(): Workspace? = queries.selectMostRecentlyUsedWorkspace().executeAsOneOrNull()?.toWorkspace()

    private fun canonicalPath(dir: File): String = dir.absoluteFile.normalize().path

    fun findByPath(dir: File): Workspace? = queries.selectWorkspaceByPath(canonicalPath(dir)).executeAsOneOrNull()?.toWorkspace()

    /**
     * Registers [dir] as a known workspace (creating it on first use) and bumps its
     * [Workspace.lastUsedAt] timestamp. Safe to call every time a workspace is opened/selected.
     */
    fun upsertByPath(dir: File, displayName: String? = null): Workspace {
        val path = canonicalPath(dir)
        val now = Clock.System.now()

        val existing = queries.selectWorkspaceByPath(path).executeAsOneOrNull()?.toWorkspace()
        if (existing != null) {
            queries.updateWorkspaceLastUsedAt(lastUsedAt = now.toString(), id = existing.id)
            return existing.copy(lastUsedAt = now)
        }

        val workspace = Workspace(
            name = displayName?.trim()?.takeIf { it.isNotBlank() } ?: dir.name.ifBlank { path },
            path = path,
            createdAt = now,
            lastUsedAt = now,
        )
        queries.insertWorkspace(
            id = workspace.id,
            name = workspace.name,
            path = workspace.path,
            createdAt = workspace.createdAt.toString(),
            lastUsedAt = workspace.lastUsedAt.toString(),
            pinned = if (workspace.pinned) 1L else 0L,
        )
        log.debug("Registered new workspace '{}' at '{}'", workspace.name, workspace.path)
        return workspace
    }

    /** Bumps [Workspace.lastUsedAt] to now, without changing anything else. */
    fun touch(id: String) {
        queries.updateWorkspaceLastUsedAt(lastUsedAt = Clock.System.now().toString(), id = id)
    }

    /** Renames a workspace's display name. Does not affect its filesystem path. */
    fun rename(id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        return queries.updateWorkspaceName(name = trimmed, id = id).value > 0
    }

    /** Pins/unpins a workspace so it always sorts to the top of the list. */
    fun setPinned(id: String, pinned: Boolean) {
        queries.updateWorkspacePinned(pinned = if (pinned) 1L else 0L, id = id)
    }

    /** Removes the workspace reference only — does NOT delete the underlying folder on disk. */
    fun delete(id: String) {
        queries.deleteWorkspaceById(id)
        log.debug("Removed workspace reference '{}'", id)
    }
}
