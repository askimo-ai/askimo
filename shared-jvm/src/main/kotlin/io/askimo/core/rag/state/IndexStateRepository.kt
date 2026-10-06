/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import kotlin.collections.iterator
import kotlin.time.Clock

/**
 * All operations are scoped to (containerId, resourceId) so that multiple
 * coordinators for the same container (project or resource collection) never interfere with
 * each other.
 */
class IndexStateRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val log = logger<IndexStateRepository>()
    private val queries get() = db.indexFileStateQueries

    companion object {
        /** SQLite bind-parameter limit is 999. We use 100 to stay well clear. */
        private const val IN_LIST_CHUNK_SIZE = 100
    }

    /**
     * Get all file hashes for a specific coordinator (container + resource + source type).
     */
    fun getHashesForSourceType(
        containerId: String,
        sourceType: String,
        resourceId: String,
    ): Map<String, String> = queries.selectHashesForSourceType(containerId, sourceType, resourceId).executeAsList()
        .associate { it.file_path to it.file_hash }

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
            queries.selectHashesForFiles(containerId, resourceId, chunk).executeAsList()
                .forEach { result[it.file_path] = it.file_hash }
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
    ): Set<String> = queries.selectPathsForResource(containerId, resourceId).executeAsList().toHashSet()

    /**
     * Get all indexed paths for a container, optionally limited by source types.
     * Used by UI cache hydration to avoid per-resource queries.
     */
    fun getPathsForProject(
        containerId: String,
        sourceTypes: Set<String> = emptySet(),
    ): Set<String> = if (sourceTypes.isEmpty()) {
        queries.selectPathsForContainer(containerId).executeAsList().toHashSet()
    } else {
        queries.selectPathsForContainerBySourceTypes(containerId, sourceTypes.toList()).executeAsList().toHashSet()
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
        db.transaction {
            for (chunk in fileHashes.keys.toList().chunked(IN_LIST_CHUNK_SIZE)) {
                queries.deleteByPathsChunk(containerId, resourceId, chunk)
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
    ) {
        db.transaction {
            queries.deleteBySourceTypeAndResource(containerId, sourceType, resourceId)
            batchInsertFileHashes(containerId, resourceId, sourceType, fileHashes)
        }
        log.trace("Saved ${fileHashes.size} file states for container $containerId, resource $resourceId")
    }

    /**
     * Inserts [fileHashes] rows into `index_file_state`.
     * Must be called inside an existing transaction.
     */
    private fun batchInsertFileHashes(
        containerId: String,
        resourceId: String,
        sourceType: String,
        fileHashes: Map<String, String>,
    ) {
        if (fileHashes.isEmpty()) return
        val now = Clock.System.now()
        for ((filePath, hash) in fileHashes) {
            queries.insertFileState(
                containerId = containerId,
                resourceId = resourceId,
                filePath = filePath,
                fileHash = hash,
                sourceType = sourceType,
                indexedAt = now,
            )
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
        db.transaction {
            for (chunk in filePaths.toList().chunked(IN_LIST_CHUNK_SIZE)) {
                queries.deleteByPathsChunk(containerId, resourceId, chunk)
            }
        }
    }

    /**
     * Delete state for a single coordinator (one knowledge source).
     * Used when removing a specific knowledge source from a project.
     */
    fun clearResourceState(containerId: String, resourceId: String) {
        val deleted = queries.deleteByResource(containerId, resourceId).value
        log.info("Cleared $deleted file states for container $containerId, resource $resourceId")
    }

    /**
     * Delete all file-hash state for an entire container, across every resource/source type.
     * Must be called whenever the on-disk/vector index for a container is wiped without going
     * through each coordinator's `IndexStateManager.clearStates` (e.g. app-restart re-index or
     * embedding-model-mismatch rebuild with no live coordinators) — otherwise a freshly created
     * coordinator loads these stale hashes, treats every unchanged-on-disk file as already
     * indexed, skips re-embedding it, and reports the now-empty index as READY.
     */
    fun clearAllStatesForContainer(containerId: String) {
        val deleted = queries.deleteByContainer(containerId).value
        log.info("Cleared $deleted file states for container $containerId")
    }
}
