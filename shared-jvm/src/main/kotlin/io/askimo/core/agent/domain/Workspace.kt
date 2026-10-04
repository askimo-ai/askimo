/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.domain

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

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
    val createdAt: Instant = Clock.System.now(),
    val lastUsedAt: Instant = Clock.System.now(),
    val pinned: Boolean = false,
)
