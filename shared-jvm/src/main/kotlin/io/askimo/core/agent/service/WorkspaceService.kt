/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.service

import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.repository.WorkspaceRepository
import io.askimo.core.util.AskimoHome
import java.io.File

/**
 * Service for resolving and managing the "current" agent [Workspace] — the folder that
 * skills/agents run against.
 *
 * [Workspace.lastUsedAt] is the single source of truth for "current" — every explicit
 * selection bumps it via [WorkspaceRepository.upsertByPath], so no separate UI-preference
 * state is needed to remember which workspace was last active.
 */
class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
) {
    /**
     * Returns the workspace the user was last working in (by [Workspace.lastUsedAt]), or
     * registers/returns the default agent-workspace dir if none are known yet.
     */
    fun resolveCurrent(): Workspace {
        workspaceRepository.findMostRecentlyUsed()?.let { return it }

        val default = AskimoHome.skillsWorkspaceDir().toFile()
        return workspaceRepository.upsertByPath(default, displayName = "Default")
    }

    /**
     * Registers [dir] as a known workspace (creating it on first use, or bumping its
     * last-used timestamp otherwise) — this alone makes it the "current" workspace, since
     * [resolveCurrent] always resolves by [Workspace.lastUsedAt].
     * Call this whenever the user opens/switches to a workspace directory.
     */
    fun select(dir: File, displayName: String? = null): Workspace = workspaceRepository.upsertByPath(dir, displayName)

    /** Returns all known workspaces — pinned first, then most-recently-used (for a picker/list UI). */
    fun findAll(): List<Workspace> = workspaceRepository.findAll()

    /** Renames a workspace's display name. Does not affect its filesystem path. */
    fun rename(id: String, newName: String): Boolean = workspaceRepository.rename(id, newName)

    /** Pins/unpins a workspace so it always sorts to the top of the picker list. */
    fun setPinned(id: String, pinned: Boolean) = workspaceRepository.setPinned(id, pinned)

    /** Removes the workspace reference only — does NOT delete the underlying folder on disk. */
    fun delete(id: String) = workspaceRepository.delete(id)
}
