/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.LABEL_NAME_MAX_LENGTH
import io.askimo.core.chat.domain.Label
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Labels
import kotlin.time.Clock

/** Maps a generated [Labels] row to the shared [Label] domain object. */
private fun Labels.toLabel(): Label = Label(
    id = id,
    name = name,
    color = color,
    createdAt = created_at,
    updatedAt = updated_at,
)

private fun validateLabelName(name: String) {
    require(name.isNotBlank()) { "Label name cannot be blank" }
    require(name.length <= LABEL_NAME_MAX_LENGTH) {
        "Label name cannot exceed $LABEL_NAME_MAX_LENGTH characters"
    }
}

/**
 * Persistence for user-defined [Label]s and their many-to-many assignment to
 * [io.askimo.core.chat.domain.ChatSession]s, mirroring [ChatDirectiveRepository]'s
 * structure (CRUD + push/pull sync helpers).
 */
class LabelRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val queries get() = db.labelsQueries

    /** Creates a new label. */
    fun create(label: Label): Label {
        validateLabelName(label.name)
        queries.insertLabel(
            id = label.id,
            name = label.name,
            color = label.color,
            createdAt = label.createdAt,
            updatedAt = label.updatedAt,
        )
        return label
    }

    /** Renames/recolors an existing label. @return true if updated, false if it doesn't exist. */
    fun update(label: Label): Boolean {
        validateLabelName(label.name)
        return queries.updateLabel(
            name = label.name,
            color = label.color,
            updatedAt = Clock.System.now(),
            id = label.id,
        ).value > 0
    }

    /** Deletes a label; cascades to remove all of its session assignments. */
    fun delete(id: String): Boolean = queries.deleteLabel(id).value > 0

    /** Gets a label by id. */
    fun get(id: String): Label? = queries.selectLabelById(id).executeAsOneOrNull()?.toLabel()

    /** Lists all labels, ordered by name. */
    fun list(): List<Label> = queries.selectAllLabelsOrderedByName().executeAsList().map { it.toLabel() }

    /** Lists the labels assigned to [sessionId], ordered by name. */
    fun labelsForSession(sessionId: String): List<Label> = queries.selectLabelsForSession(sessionId).executeAsList().map { it.toLabel() }

    /**
     * Bulk-loads labels for multiple sessions in a single query.
     * @return a map of sessionId -> its assigned labels (sessions with no labels are omitted).
     */
    fun labelsForSessions(sessionIds: List<String>): Map<String, List<Label>> {
        if (sessionIds.isEmpty()) return emptyMap()
        return queries.selectLabelsForSessions(sessionIds).executeAsList()
            .groupBy({ it.session_id }, { Label(id = it.id, name = it.name, color = it.color, createdAt = it.created_at, updatedAt = it.updated_at) })
    }

    /** Session ids currently carrying [labelId] (used for group-by/filter-by-label). */
    fun sessionIdsForLabel(labelId: String): List<String> = queries.selectSessionIdsForLabel(labelId).executeAsList()

    /** Assigns [labelId] to [sessionId]; no-op if already assigned. */
    fun assign(sessionId: String, labelId: String) {
        queries.assignLabel(sessionId, labelId)
    }

    /** Removes [labelId] from [sessionId]; no-op if not assigned. */
    fun unassign(sessionId: String, labelId: String) {
        queries.unassignLabel(sessionId, labelId)
    }

    /**
     * Replaces the full set of labels assigned to [sessionId] with [labelIds] in a single
     * transaction (clear then re-assign) — convenient for a "Labels" picker menu that lets
     * the user toggle multiple labels at once.
     */
    fun setLabelsForSession(sessionId: String, labelIds: List<String>) {
        db.transaction {
            queries.clearLabelsForSession(sessionId)
            labelIds.forEach { queries.assignLabel(sessionId, it) }
        }
    }

    /**
     * Returns labels whose [syncedAt] is NULL or older than [updatedAt], meaning they have
     * local changes that have not yet been pushed to the server.
     */
    fun getUnsyncedLabels(limit: Int = 50): List<Label> = queries.selectAllLabelsOrderedByUpdatedAt().executeAsList()
        .mapNotNull { row ->
            if (row.synced_at == null || row.updated_at > row.synced_at) row.toLabel() else null
        }
        .take(limit)

    /** Stamps [syncedAt] with the current timestamp to record a successful push. */
    fun markSynced(labelId: String): Boolean = queries.markLabelSynced(Clock.System.now(), labelId).value > 0

    /**
     * Merges labels received from the server into the local database. Inserts rows that
     * don't exist locally; overwrites rows where the server version is strictly newer than
     * the locally stored [updatedAt].
     */
    fun upsertFromServer(labels: List<Label>) {
        if (labels.isEmpty()) return

        db.transaction {
            val now = Clock.System.now()
            val ids = labels.map { it.id }

            val existingById = queries.selectExistingLabelsByIds(ids).executeAsList()
                .associate { it.id to it.updated_at }

            for (label in labels) {
                validateLabelName(label.name)
                val storedUpdatedAt = existingById[label.id]

                if (storedUpdatedAt == null) {
                    queries.insertLabelFromServer(
                        id = label.id,
                        name = label.name,
                        color = label.color,
                        createdAt = label.createdAt,
                        updatedAt = label.updatedAt,
                        syncedAt = now,
                    )
                } else if (label.updatedAt > storedUpdatedAt) {
                    queries.updateLabelFromServer(
                        name = label.name,
                        color = label.color,
                        updatedAt = label.updatedAt,
                        syncedAt = now,
                        id = label.id,
                    )
                }
            }
        }
    }

    /** Permanently removes a label from the local database (server signaled a hard delete). */
    fun hardDelete(labelId: String): Boolean = queries.deleteLabel(labelId).value > 0
}
