/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.user.IndexingInProgressEvent
import io.askimo.core.logging.logger
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStateManager
import io.askimo.core.rag.state.IndexStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class for local indexing coordinators.
 * Holds shared infrastructure and encapsulates the common finalization logic
 * (delete detection, flush, state persistence, progress update, and completion log).
 */
abstract class BaseLocalIndexingCoordinator<T : KnowledgeSourceConfig>(
    protected val projectId: String,
    protected val projectName: String,
    protected val embeddingStore: EmbeddingStore<TextSegment>,
    protected val embeddingModel: EmbeddingModel,
    protected val appContext: AppContext,
    stateManagerScope: String,
    resourceId: String, // KnowledgeSourceConfig.resourceIdentifier — isolates state per coordinator
) : IndexingCoordinator<T> {

    private val log = logger<BaseLocalIndexingCoordinator<*>>()

    protected val resourceContentProcessor = ResourceContentProcessor(appContext)
    protected val stateManager = IndexStateManager(projectId, stateManagerScope, resourceId)
    protected val hybridIndexer = HybridIndexer(embeddingStore, embeddingModel, projectId)

    private val _progress = MutableStateFlow(IndexProgress())
    override val progress: StateFlow<IndexProgress> = _progress

    protected fun updateProgress(update: IndexProgress.() -> IndexProgress) {
        _progress.value = _progress.value.update()
    }

    override fun markQueued() {
        updateProgress { copy(status = IndexStatus.QUEUED) }
    }

    protected val processedFilesCounter = AtomicInteger(0)
    protected val totalFilesCounter = AtomicInteger(0)

    /** Chunks processed so far in the file currently being embedded. Reset at the start of each file. */
    @Volatile protected var currentFileChunksProcessed: Int = 0

    /** Total chunks in the file currently being embedded. Reset at the start of each file. */
    @Volatile protected var currentFileChunksTotal: Int = 0

    /** Set to [System.currentTimeMillis] each time processing starts on a new file. */
    @Volatile protected var currentFileStartMs: Long = 0L

    /**
     * Update progress atomically and emit an [IndexingInProgressEvent] when appropriate.
     * Called once per file after all its chunks have been embedded.
     */
    protected suspend fun updateProgressAtomic(currentFile: Path? = null) {
        val processedFiles = processedFilesCounter.incrementAndGet()
        val totalFiles = totalFilesCounter.get()
        val currentFilePath = currentFile?.toAbsolutePath()?.toString()
        val elapsedMs = System.currentTimeMillis() - currentFileStartMs
        // Zero out per-file chunk counters — no stale chunk fraction leaks between files
        currentFileChunksProcessed = 0
        currentFileChunksTotal = 0
        updateProgress {
            copy(
                processedFiles = processedFiles,
                currentFile = currentFilePath,
                currentFileProcessedChunks = 0,
                currentFileTotalChunks = 0,
                currentFileElapsedMs = elapsedMs,
            )
        }

        if (processedFiles % 10 == 0 || processedFiles == totalFiles) {
            EventBus.emit(
                IndexingInProgressEvent(
                    projectId = projectId,
                    projectName = projectName,
                    filesIndexed = processedFiles,
                    totalFiles = totalFiles,
                    resourceId = stateManager.resourceId,
                    currentFile = currentFilePath,
                    chunksIndexed = 0,
                    totalChunks = 0,
                    currentFileElapsedMs = elapsedMs,
                ),
            )
        }
    }

    /**
     * Called after splitting a file into chunks but before embedding.
     * Registers the chunk count for this file and emits an in-progress event
     * so the UI progress bar starts moving immediately for large files.
     */
    protected suspend fun onFileChunksReady(filePath: Path, chunkCount: Int) {
        currentFileStartMs = System.currentTimeMillis()
        // Reset to this file only — do NOT accumulate across files
        currentFileChunksTotal = chunkCount
        currentFileChunksProcessed = 0
        val currentFilePath = filePath.toAbsolutePath().toString()
        updateProgress {
            copy(
                currentFile = currentFilePath,
                currentFileTotalChunks = chunkCount,
                currentFileProcessedChunks = 0,
                currentFileElapsedMs = 0L,
            )
        }
        EventBus.emit(
            IndexingInProgressEvent(
                projectId = projectId,
                projectName = projectName,
                filesIndexed = processedFilesCounter.get(),
                totalFiles = totalFilesCounter.get(),
                resourceId = stateManager.resourceId,
                currentFile = currentFilePath,
                chunksIndexed = 0,
                totalChunks = chunkCount,
                currentFileElapsedMs = 0L,
            ),
        )
    }

    /**
     * Shared finalization step after all files have been processed:
     * - Detects and removes deleted files
     * - Flushes remaining segments
     * - Persists state
     * - Updates progress to READY
     * - Logs a completion summary
     *
     * @return true on success, false if flushing failed
     */
    protected suspend fun finalizeIndexing(
        previousHashes: Map<String, String>,
        fileHashes: ConcurrentHashMap<String, String>,
    ): Boolean {
        val deletedFiles = detectDeletedFiles(previousHashes, fileHashes)
        if (deletedFiles.isNotEmpty()) {
            log.info("Detected ${deletedFiles.size} deleted files, removing from index...")
            removeDeletedFilesFromIndex(deletedFiles)
        }

        val skippedFiles = previousHashes.keys.intersect(fileHashes.keys).count { key ->
            previousHashes[key] == fileHashes[key]
        }

        if (!hybridIndexer.flushRemainingSegments()) {
            updateProgress { copy(status = IndexStatus.FAILED, error = "Failed to flush remaining segments") }
            return false
        }

        val processedFiles = fileHashes.size
        stateManager.saveState(processedFiles, fileHashes.toMap())

        updateProgress { copy(status = IndexStatus.READY, processedFiles = processedFiles) }

        log.info(
            "Completed indexing for project $projectName: " +
                "${processedFiles - skippedFiles} files indexed, " +
                "$skippedFiles files skipped (unchanged), " +
                "${deletedFiles.size} files removed",
        )
        return true
    }

    /**
     * Detect files that were previously indexed but are now deleted.
     * Returns list of absolute file paths that no longer exist.
     */
    private fun detectDeletedFiles(
        previousHashes: Map<String, String>,
        currentHashes: Map<String, String>,
    ): List<String> {
        val deletedFiles = previousHashes.keys - currentHashes.keys

        if (deletedFiles.isNotEmpty()) {
            log.debug("Detected ${deletedFiles.size} deleted files:")
            deletedFiles.take(5).forEach { log.debug("  - $it") }
            if (deletedFiles.size > 5) {
                log.debug("  ... and ${deletedFiles.size - 5} more")
            }
        }

        return deletedFiles.toList()
    }

    /**
     * Remove deleted files from the hybrid index (vector store + keyword index).
     */
    private fun removeDeletedFilesFromIndex(deletedFiles: List<String>) {
        try {
            for (absoluteFilePath in deletedFiles) {
                hybridIndexer.removeFileFromIndex(Path.of(absoluteFilePath))
                log.debug("Removed deleted file from index: $absoluteFilePath")
            }
        } catch (e: Exception) {
            log.error("Failed to remove deleted files from index", e)
            throw e
        }
    }

    /**
     * Clears all indexed data for this knowledge source:
     * - Removes all segments from the embedding store and Lucene keyword index
     *   (via [HybridIndexer.removeDirectoryFromIndex] using the resource path as prefix)
     * - Clears the DB hash-state records
     */
    override fun clearAll() {
        try {
            hybridIndexer.removeDirectoryFromIndex(Path.of(stateManager.resourceId))
        } catch (e: Exception) {
            log.error("Failed to clear hybrid index for resource ${stateManager.resourceId} in project $projectId", e)
        }
        stateManager.clearStates()
        log.info("Cleared all index states for project $projectId (resource: ${stateManager.resourceId})")
    }
}
