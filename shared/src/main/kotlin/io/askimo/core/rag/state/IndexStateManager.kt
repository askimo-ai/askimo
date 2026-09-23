/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages the persisted state of an index for a single knowledge-source coordinator.
 * Scoped to (containerId, sourceType, resourceId) so multiple coordinators on the same
 * container (project or resource collection) never share or overwrite each other's state.
 */
class IndexStateManager(
    private val containerId: String,
    private val sourceType: String, // 'folders', 'files', or 'urls'
    val resourceId: String, // KnowledgeSourceConfig.resourceIdentifier
) {
    private val log = logger<IndexStateManager>()
    private val repository = IndexStateRepository(DatabaseManager.getInstance())

    /**
     * Load persisted state from database
     */
    fun loadPersistedState(): IndexPersistedState? {
        return try {
            val fileHashes = repository.getHashesForSourceType(containerId, sourceType, resourceId)

            if (fileHashes.isEmpty()) {
                log.debug("No persisted state found for container $containerId, resource $resourceId")
                return null
            }

            IndexPersistedState(
                totalFilesIndexed = fileHashes.size,
                lastIndexedTimestamp = System.currentTimeMillis(), // Not stored in DB anymore
                fileHashes = fileHashes,
            )
        } catch (e: Exception) {
            log.error("Failed to load persisted state for container $containerId, resource $resourceId", e)
            null
        }
    }

    /**
     * Save state to database using batch insert for performance
     */
    fun saveState(
        totalFilesIndexed: Int,
        fileHashes: Map<String, String>,
    ) {
        try {
            repository.batchSaveFileStates(containerId, fileHashes, sourceType, resourceId)
            log.debug("Saved index state for container $containerId, resource $resourceId: $totalFilesIndexed files")
        } catch (e: Exception) {
            log.error("Failed to save index state for container $containerId, resource $resourceId", e)
        }
    }

    // ── Chunked / incremental API (used by LocalFoldersIndexingCoordinator) ──

    /** Batch-query previous hashes for a subset of file paths (one DB round-trip per chunk). */
    fun getHashesForFiles(filePaths: List<String>): Map<String, String> = repository.getHashesForFiles(containerId, resourceId, filePaths)

    /** Return all stored paths for this coordinator — used for deleted-file detection. */
    fun getStoredPaths(): Set<String> = repository.getPathsForResource(containerId, resourceId)

    /** Persist hashes for a batch of changed files without touching unchanged entries. */
    fun saveFileHashesBatch(fileHashes: Map<String, String>) = repository.upsertFileHashesBatch(containerId, resourceId, sourceType, fileHashes)

    /** Remove state entries for files that were deleted from disk. */
    fun removeFilePaths(filePaths: Set<String>) = repository.removeFilePaths(containerId, resourceId, filePaths)

    /**
     * Compute a lightweight change-detection key from file metadata.
     * Uses lastModified + size — zero I/O, zero allocation, sufficient for
     * incremental indexing (same approach as git index, Make, Gradle).
     */
    fun calculateFileHash(filePath: Path): String = try {
        val lastModified = Files.getLastModifiedTime(filePath).toMillis()
        val size = Files.size(filePath)
        "$lastModified-$size"
    } catch (e: Exception) {
        log.warn("Failed to read metadata for ${filePath.fileName}: ${e.message}", e)
        ""
    }

    /**
     * Clear all index states for this coordinator's resource.
     */
    fun clearStates() {
        try {
            repository.clearResourceState(containerId, resourceId)
            log.info("Cleared index states for container $containerId, resource $resourceId")
        } catch (e: Exception) {
            log.error("Failed to clear index states for container $containerId, resource $resourceId", e)
        }
    }

    companion object {
        private val repository by lazy { IndexStateRepository(DatabaseManager.getInstance()) }
        private val localSourceTypes = setOf("folders", "files")

        /**
         * Returns all indexed local file paths for the given container (project or resource collection).
         */
        fun getIndexedLocalPathsForContainer(containerId: String): Set<String> = repository.getPathsForProject(containerId, localSourceTypes)
            .mapTo(HashSet()) { IndexPathNormalizer.normalize(it) }

        /**
         * Canonical path key used by both persistence readers and UI lookups.
         */
        fun normalizePathKey(path: String): String = IndexPathNormalizer.normalize(path)
    }
}
