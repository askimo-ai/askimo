/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A user-defined, reusable, colored label that can be assigned to any number of
 * [ChatSession]s (many-to-many), independent of the exclusive [ChatSession.projectId]
 * grouping. Lets users organize chats by cross-cutting categories (e.g. "billing",
 * "urgent") that don't fit a single-parent folder hierarchy.
 */
@OptIn(ExperimentalUuidApi::class)
data class Label(
    val id: String = Uuid.random().toString(),
    val name: String,
    /** Hex color string (e.g. "#FF5733") used to render the label's chip/dot in the UI. */
    val color: String,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
)

const val LABEL_NAME_MAX_LENGTH = 64

/**
 * Returns the label IDs that should result from toggling [labelId]'s assignment within this
 * list of currently-assigned labels: dropped if already present, appended otherwise.
 */
fun List<Label>.toggledLabelIds(labelId: String): List<String> {
    val isAssigned = any { it.id == labelId }
    return if (isAssigned) {
        filterNot { it.id == labelId }.map { it.id }
    } else {
        map { it.id } + labelId
    }
}
