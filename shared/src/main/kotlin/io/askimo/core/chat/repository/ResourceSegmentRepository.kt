/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ResourceSegmentsTable
import io.askimo.core.db.DatabaseManager
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Path
import java.time.Instant

/**
 * Repository for managing resource-to-segment mappings in the embedding store.
 * Tracks which segment IDs belong to which resources (files, URLs, etc.) so they can be removed when resources are deleted.
 *
 * Note: "resourceId" is a string identifier that can represent:
 * - File paths (for local files) - converted from Path.toString()
 * - URLs (for web pages)
 * - Document IDs (for SEC filings, etc.)
 *
 * Follows the DatabaseManager pattern - table creation is handled by DatabaseManager.
 */
class ResourceSegmentRepository(
    private val databaseManager: DatabaseManager,
) {
    private val database: Database by lazy { Database.connect(databaseManager.dataSource) }

    /**
     * Save multiple segment mappings in a batch.
     * @param containerId ID of the owning container — either a Project id or a Resource Collection id.
     * @param resourceId String identifier for the resource (file path, URL, etc.).
     *                   Backslashes are normalized to forward slashes for cross-platform consistency.
     */
    fun saveSegmentMappings(
        containerId: String,
        resourceId: String,
        segmentIds: List<Pair<String, Int>>,
    ) {
        if (segmentIds.isEmpty()) return

        val normalizedId = resourceId.replace('\\', '/')
        transaction(database) {
            ResourceSegmentsTable.batchInsert(
                data = segmentIds,
                ignore = true,
            ) { (segmentId, chunkIndex) ->
                this[ResourceSegmentsTable.containerId] = containerId
                this[ResourceSegmentsTable.resourceId] = normalizedId
                this[ResourceSegmentsTable.segmentId] = segmentId
                this[ResourceSegmentsTable.chunkIndex] = chunkIndex
                this[ResourceSegmentsTable.createdAt] = Instant.now()
            }
        }
    }

    /**
     * Save multiple segment mappings in a batch (backward compatible Path version)
     * Converts Path to String automatically for backward compatibility.
     * Paths are normalized to forward slashes for cross-platform consistency.
     */
    fun saveSegmentMappings(
        containerId: String,
        filePath: Path,
        segmentIds: List<Pair<String, Int>>,
    ) {
        saveSegmentMappings(containerId, filePath.toNormalizedString(), segmentIds)
    }

    /**
     * Get all segment IDs for a specific resource.
     * @param resourceId String identifier for the resource; backslashes are normalized.
     */
    fun getSegmentIdsForResource(
        containerId: String,
        resourceId: String,
    ): List<String> = transaction(database) {
        val normalizedId = resourceId.replace('\\', '/')
        ResourceSegmentsTable
            .selectAll()
            .where {
                (ResourceSegmentsTable.containerId eq containerId) and
                    (ResourceSegmentsTable.resourceId eq normalizedId)
            }
            .orderBy(ResourceSegmentsTable.chunkIndex)
            .map { it[ResourceSegmentsTable.segmentId] }
    }

    /**
     * Get all segment IDs for a specific file (backward compatible Path version).
     * Paths are normalized to forward slashes for cross-platform consistency.
     */
    fun getSegmentIdsForFile(
        containerId: String,
        filePath: Path,
    ): List<String> = getSegmentIdsForResource(containerId, filePath.toNormalizedString())

    /**
     * Remove all segment mappings for a specific resource.
     * @param resourceId String identifier for the resource; backslashes are normalized.
     */
    fun removeSegmentMappingsForResource(
        containerId: String,
        resourceId: String,
    ): Int = transaction(database) {
        val normalizedId = resourceId.replace('\\', '/')
        ResourceSegmentsTable.deleteWhere {
            (ResourceSegmentsTable.containerId eq containerId) and
                (ResourceSegmentsTable.resourceId eq normalizedId)
        }
    }

    /**
     * Remove all segment mappings for a specific file (backward compatible Path version).
     * Paths are normalized to forward slashes for cross-platform consistency.
     */
    fun removeSegmentMappingsForFile(
        containerId: String,
        filePath: Path,
    ): Int = removeSegmentMappingsForResource(containerId, filePath.toNormalizedString())

    /**
     * Remove ALL segment mappings for an entire container (project or resource collection).
     * Used when the index is fully cleared (e.g. re-index or embedding model change).
     */
    fun removeAllSegmentMappingsForProject(containerId: String): Int = transaction(database) {
        ResourceSegmentsTable.deleteWhere {
            ResourceSegmentsTable.containerId eq containerId
        }
    }

    /**
     * Get all segment IDs whose resource path starts with [dirPrefix].
     * Used to clean up all segments under a deleted directory.
     * [dirPrefix] is normalized to forward slashes for cross-platform consistency.
     */
    fun getSegmentIdsForDirectory(containerId: String, dirPrefix: String): List<String> {
        val normalized = dirPrefix.replace('\\', '/')
        val prefix = if (normalized.endsWith("/")) normalized else "$normalized/"
        return transaction(database) {
            ResourceSegmentsTable
                .selectAll()
                .where {
                    (ResourceSegmentsTable.containerId eq containerId) and
                        (ResourceSegmentsTable.resourceId like "$prefix%")
                }
                .map { it[ResourceSegmentsTable.segmentId] }
        }
    }

    /**
     * Remove all segment mappings whose resource path starts with [dirPrefix].
     * Returns the number of rows deleted.
     * [dirPrefix] is normalized to forward slashes for cross-platform consistency.
     */
    fun removeSegmentMappingsForDirectory(containerId: String, dirPrefix: String): Int {
        val normalized = dirPrefix.replace('\\', '/')
        val prefix = if (normalized.endsWith("/")) normalized else "$normalized/"
        return transaction(database) {
            ResourceSegmentsTable.deleteWhere {
                (ResourceSegmentsTable.containerId eq containerId) and
                    (ResourceSegmentsTable.resourceId like "$prefix%")
            }
        }
    }
}

/** Normalize a [Path] to a forward-slash string for cross-platform DB storage. */
private fun Path.toNormalizedString() = toString().replace('\\', '/')
