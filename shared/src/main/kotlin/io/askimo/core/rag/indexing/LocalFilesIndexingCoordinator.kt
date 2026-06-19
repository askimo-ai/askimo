/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.chat.domain.LocalFilesKnowledgeSourceConfig
import io.askimo.core.context.AppContext
import io.askimo.core.logging.logger
import io.askimo.core.rag.filter.FilterChain
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStatus
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isRegularFile

/**
 * Coordinates the indexing process for specific local files.
 * Simplified version of LocalFoldersIndexingCoordinator that only indexes
 * individual files without directory traversal.
 */
class LocalFilesIndexingCoordinator(
    projectId: String,
    projectName: String,
    override val knowledgeSourceConfig: LocalFilesKnowledgeSourceConfig,
    embeddingStore: EmbeddingStore<TextSegment>,
    embeddingModel: EmbeddingModel,
    appContext: AppContext,
) : BaseLocalIndexingCoordinator<LocalFilesKnowledgeSourceConfig>(
    projectId = projectId,
    projectName = projectName,
    embeddingStore = embeddingStore,
    embeddingModel = embeddingModel,
    appContext = appContext,
    stateManagerScope = "files",
    resourceId = knowledgeSourceConfig.resourceIdentifier,
) {
    private val log = logger<LocalFilesIndexingCoordinator>()

    private val filePaths = listOf(Paths.get(knowledgeSourceConfig.resourceIdentifier))

    // Use LOCAL_FILES filter chain which only checks supported extensions
    private val filterChain: FilterChain = FilterChain.LOCAL_FILES

    /**
     * Check if a file should be excluded from indexing using the filter chain.
     */
    private fun shouldExcludeFile(path: Path): Boolean = filterChain.shouldExcludePath(path)

    /**
     * Index files with progress tracking.
     * Uses incremental indexing - only indexes new or modified files.
     * Detects and removes deleted files from the index.
     */
    private suspend fun indexFilesWithProgress(
        paths: List<Path>,
    ): Boolean {
        updateProgress { IndexProgress(status = IndexStatus.INDEXING) }

        processedFilesCounter.set(0)
        totalFilesCounter.set(0)

        try {
            val previousState = stateManager.loadPersistedState()
            val previousHashes: Map<String, String> = previousState?.fileHashes ?: emptyMap()

            log.info("Starting indexing for project $projectId with ${paths.size} files")

            val fileHashes = ConcurrentHashMap<String, String>()
            val skippedNames = mutableListOf<String>()

            val validFiles = paths.filter { path ->
                when {
                    !path.isRegularFile() -> {
                        log.warn("Skipping non-file path: $path")
                        false
                    }

                    shouldExcludeFile(path) -> {
                        log.debug("Skipping excluded file: {}", path)
                        false
                    }

                    else -> true
                }
            }

            totalFilesCounter.set(validFiles.size)
            updateProgress { copy(totalFiles = validFiles.size) }

            log.info("Found ${validFiles.size} indexable files")

            for (filePath in validFiles) {
                indexFile(filePath, fileHashes, previousHashes, skippedNames)
            }

            val success = finalizeIndexing(previousHashes, fileHashes)

            // Persist skipped file names into progress after finalization
            if (skippedNames.isNotEmpty()) {
                updateProgress { copy(skippedFileNames = skippedNames) }
            }

            return success
        } catch (e: Exception) {
            log.error("Indexing failed for project $projectId", e)
            updateProgress { copy(status = IndexStatus.FAILED, error = e.message ?: "Unknown error") }
            return false
        }
    }

    /**
     * Index a single file.
     * Returns true if file was processed successfully (indexed or skipped), false on error.
     */
    private suspend fun indexFile(
        filePath: Path,
        fileHashes: ConcurrentHashMap<String, String>,
        previousHashes: Map<String, String>,
        skippedNames: MutableList<String>,
    ): Boolean {
        val startTime = System.currentTimeMillis()

        try {
            val hash = stateManager.calculateFileHash(filePath)
            val absolutePath = filePath.toAbsolutePath().toString()

            fileHashes[absolutePath] = hash

            if (previousHashes[absolutePath] == hash) {
                log.debug("Skipping unchanged file: {}", filePath.fileName)
                updateProgressAtomic(filePath)
                return true
            }

            val segments = resourceContentProcessor.createSegmentsForFile(filePath)

            if (segments == null) {
                log.debug("Skipping file with no extractable content: {}", filePath.fileName)
                skippedNames.add(filePath.fileName.toString())
                updateProgressAtomic(filePath)
                return true
            }

            if (segments.isEmpty()) {
                log.debug("No valid chunks created for file: {}", filePath.fileName)
                updateProgressAtomic(filePath)
                return true
            }

            log.debug("Start indexing {} ({} chunks)", filePath.fileName, segments.size)
            for (segment in segments) {
                if (!hybridIndexer.addSegmentToBatch(segment, filePath)) {
                    return false
                }
            }

            val elapsedTime = System.currentTimeMillis() - startTime
            log.debug("Indexed {} ({} chunks) in {}ms", filePath.fileName, segments.size, elapsedTime)

            updateProgressAtomic(filePath)
            return true
        } catch (e: Exception) {
            val elapsedTime = System.currentTimeMillis() - startTime
            log.error("Failed to index file {} after {}ms: {}", filePath.fileName, elapsedTime, e.message, e)
            return false
        }
    }

    /**
     * Start indexing using the file paths provided at construction.
     */
    override suspend fun startIndexing(): Boolean = indexFilesWithProgress(filePaths)

    /**
     * File-level indexing doesn't support watching (no directory to watch).
     */
    override fun startWatching(scope: CoroutineScope) {
        log.debug("File watching not supported for file-level indexing (project $projectId)")
    }

    /**
     * File-level indexing doesn't support watching.
     */
    override fun stopWatching() {
        // No-op
    }

    /**
     * Close coordinator and cleanup resources.
     */
    override fun close() {
        log.debug("Closed LocalFilesIndexingCoordinator for project $projectId")
    }
}
