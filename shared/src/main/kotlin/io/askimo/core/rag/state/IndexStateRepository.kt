/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/**
 * Repository for managing file index state in the database.
 * All operations are scoped to (containerId, resourceId) so that multiple
 * coordinators for the same container (project or resource collection) never interfere with each other.
 */
class IndexStateRepository(
    private val databaseManager: DatabaseManager,
) {
    private val log = logger<IndexStateRepository>()

    private val database: Database by lazy {
        Database.connect(databaseManager.dataSource)
    }

    companion object {
        /** SQLite bind-parameter limit is 999. We use 500 to stay well clear. */
        private const val IN_LIST_CHUNK_SIZE = 100
    }

    /**
     * Get all file hashes for a specific coordinator (container + resource + source type).
     */
    fun getHashesForSourceType(
        containerId: String,
        sourceType: String,
        resourceId: String,
    ): Map<String, String> = transaction(database) {
        IndexFileStateTable.selectAll()
            .where {
                (IndexFileStateTable.containerId eq containerId) and
                    (IndexFileStateTable.sourceType eq sourceType) and
                    (IndexFileStateTable.resourceId eq resourceId)
            }
            .associate { row ->
                row[IndexFileStateTable.filePath] to row[IndexFileStateTable.fileHash]
            }
    }

    /**
     * Get hashes for a specific subset of file paths.
     * Used by chunked indexing to avoid loading the entire container's hashes at once.
     */
    fun getHashesForFiles(
        containerId: String,
        resourceId: String,
        filePaths: List<String>,
    ): Map<String, String> {
        if (filePaths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (chunk in filePaths.chunked(IN_LIST_CHUNK_SIZE)) {
            transaction(database) {
                IndexFileStateTable.selectAll()
                    .where {
                        (IndexFileStateTable.containerId eq containerId) and
                            (IndexFileStateTable.resourceId eq resourceId) and
                            (IndexFileStateTable.filePath inList chunk)
                    }
                    .forEach { result[it[IndexFileStateTable.filePath]] = it[IndexFileStateTable.fileHash] }
            }
        }
        return result
    }

    /**
     * Get all stored file paths for a coordinator (paths only, not hashes).
     * Used for deleted-file detection: compare against the current filesystem scan.
     */
    fun getPathsForResource(
        containerId: String,
        resourceId: String,
    ): Set<String> = transaction(database) {
        IndexFileStateTable.selectAll()
            .where {
                (IndexFileStateTable.containerId eq containerId) and
                    (IndexFileStateTable.resourceId eq resourceId)
            }
            .mapTo(HashSet<String>()) { it[IndexFileStateTable.filePath] }
    }

    /**
     * Get all indexed paths for a container, optionally limited by source types.
     * Used by UI cache hydration to avoid per-resource queries.
     */
    fun getPathsForProject(
        containerId: String,
        sourceTypes: Set<String> = emptySet(),
    ): Set<String> = transaction(database) {
        if (sourceTypes.isEmpty()) {
            IndexFileStateTable.selectAll()
                .where { IndexFileStateTable.containerId eq containerId }
                .mapTo(HashSet<String>()) { it[IndexFileStateTable.filePath] }
        } else {
            IndexFileStateTable.selectAll()
                .where {
                    (IndexFileStateTable.containerId eq containerId) and
                        (IndexFileStateTable.sourceType inList sourceTypes.toList())
                }
                .mapTo(HashSet<String>()) { it[IndexFileStateTable.filePath] }
        }
    }

    /**
     * Upsert hashes for a batch of changed files.
     * Only touches the files in [fileHashes] — all other entries remain untouched.
     * Used by chunked indexing to persist state incrementally per chunk.
     */
    fun upsertFileHashesBatch(
        containerId: String,
        resourceId: String,
        sourceType: String,
        fileHashes: Map<String, String>,
    ) {
        if (fileHashes.isEmpty()) return
        transaction(database) {
            for (chunk in fileHashes.keys.toList().chunked(IN_LIST_CHUNK_SIZE)) {
                IndexFileStateTable.deleteWhere {
                    (IndexFileStateTable.containerId eq containerId) and
                        (IndexFileStateTable.resourceId eq resourceId) and
                        (IndexFileStateTable.filePath inList chunk)
                }
            }
            batchInsertFileHashes(containerId, resourceId, sourceType, fileHashes)
        }
    }

    /**
     * Replace all file states for this coordinator (used by small coordinators: files, urls).
     * Deletes stale entries first so removed files don't linger across indexing runs.
     */
    fun batchSaveFileStates(
        containerId: String,
        fileHashes: Map<String, String>,
        sourceType: String,
        resourceId: String,
    ) = transaction(database) {
        IndexFileStateTable.deleteWhere {
            (IndexFileStateTable.containerId eq containerId) and
                (IndexFileStateTable.sourceType eq sourceType) and
                (IndexFileStateTable.resourceId eq resourceId)
        }
        batchInsertFileHashes(containerId, resourceId, sourceType, fileHashes)
        log.trace("Saved ${fileHashes.size} file states for container $containerId, resource $resourceId")
    }

    /**
     * Inserts [fileHashes] rows into [IndexFileStateTable].
     * Must be called inside an existing transaction.
     */
    private fun batchInsertFileHashes(
        containerId: String,
        resourceId: String,
        sourceType: String,
        fileHashes: Map<String, String>,
    ) {
        if (fileHashes.isEmpty()) return
        val now = Instant.now()
        IndexFileStateTable.batchInsert(fileHashes.entries) { (filePath, hash) ->
            this[IndexFileStateTable.containerId] = containerId
            this[IndexFileStateTable.resourceId] = resourceId
            this[IndexFileStateTable.filePath] = filePath
            this[IndexFileStateTable.fileHash] = hash
            this[IndexFileStateTable.sourceType] = sourceType
            this[IndexFileStateTable.indexedAt] = now
        }
    }

    /**
     * Remove state entries for specific deleted file paths.
     */
    fun removeFilePaths(
        containerId: String,
        resourceId: String,
        filePaths: Set<String>,
    ) {
        if (filePaths.isEmpty()) return
        transaction(database) {
            for (chunk in filePaths.toList().chunked(IN_LIST_CHUNK_SIZE)) {
                IndexFileStateTable.deleteWhere {
                    (IndexFileStateTable.containerId eq containerId) and
                        (IndexFileStateTable.resourceId eq resourceId) and
                        (IndexFileStateTable.filePath inList chunk)
                }
            }
        }
    }

    /**
     * Delete state for a single coordinator (one knowledge source).
     * Used when removing a specific knowledge source from a project.
     */
    fun clearResourceState(containerId: String, resourceId: String) = transaction(database) {
        val deleted = IndexFileStateTable.deleteWhere {
            (IndexFileStateTable.containerId eq containerId) and
                (IndexFileStateTable.resourceId eq resourceId)
        }
        log.info("Cleared $deleted file states for container $containerId, resource $resourceId")
    }

    /**
     * Delete all file-hash state for an entire container, across every resource/source type.
     * Must be called whenever the on-disk/vector index for a container is wiped without going
     * through each coordinator's [IndexStateManager.clearStates] (e.g. app-restart re-index or
     * embedding-model-mismatch rebuild with no live coordinators) — otherwise a freshly created
     * coordinator loads these stale hashes, treats every unchanged-on-disk file as already
     * indexed, skips re-embedding it, and reports the now-empty index as READY.
     */
    fun clearAllStatesForContainer(containerId: String) = transaction(database) {
        val deleted = IndexFileStateTable.deleteWhere {
            IndexFileStateTable.containerId eq containerId
        }
        log.info("Cleared $deleted file states for container $containerId")
    }
}
