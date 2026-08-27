/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.watching

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.user.FileRemovedFromIndexEvent
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.logging.logger
import io.askimo.core.rag.filter.FilterChain
import io.askimo.core.rag.indexing.HybridIndexer
import io.askimo.core.rag.indexing.ResourceContentProcessor
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/**
 * Handles file change events from the file watcher
 */
class FileChangeHandler(
    private val projectId: String,
    private val projectName: String,
    private val embeddingStore: EmbeddingStore<TextSegment>,
    private val embeddingModel: EmbeddingModel,
    private val appContext: AppContext,
) {
    private val log = logger<FileChangeHandler>()
    private val resourceContentProcessor = ResourceContentProcessor(appContext)
    private val batchIndexer = HybridIndexer(embeddingStore, embeddingModel, projectId)

    private val filterChain: FilterChain = FilterChain.DEFAULT

    /**
     * Check if a path should be excluded from indexing
     */
    private fun shouldExcludePath(path: Path): Boolean = filterChain.shouldExcludePath(path)

    /**
     * Handle a file change event
     */
    suspend fun handleFileChange(filePath: Path, kind: WatchEvent.Kind<*>) {
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            log.debug("Path deleted: {}, removing from index...", filePath.fileName)
            batchIndexer.removeDirectoryFromIndex(filePath)
            batchIndexer.removeFileFromIndex(filePath)
            EventBus.emit(
                FileRemovedFromIndexEvent(
                    projectId = projectId,
                    projectName = projectName,
                    fileName = filePath.fileName.toString(),
                ),
            )
            return
        }

        // New directory created — index all files inside it
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && filePath.isDirectory()) {
            log.debug("Directory created: {}, indexing all files inside...", filePath.fileName)
            EventBus.emit(IndexingStartedEvent(projectId = projectId, projectName = projectName))
            try {
                var indexedCount = 0
                filePath.walk()
                    .filter { it.isRegularFile() && !shouldExcludePath(it) }
                    .forEach { file ->
                        if (reindexFile(file)) indexedCount++
                    }
                batchIndexer.flushRemainingSegments()
                EventBus.emit(
                    IndexingCompletedEvent(
                        projectId = projectId,
                        projectName = projectName,
                        filesIndexed = indexedCount,
                    ),
                )
            } catch (e: Exception) {
                log.error("Failed to index new directory {}", filePath.fileName, e)
                EventBus.emit(
                    IndexingFailedEvent(
                        projectId = projectId,
                        projectName = projectName,
                        errorMessage = e.message ?: "Failed to index new directory ${filePath.fileName}",
                    ),
                )
            }
            return
        }

        // For CREATE/MODIFY events on files, check if file exists and is not excluded
        if (!filePath.isRegularFile() || shouldExcludePath(filePath)) {
            return
        }

        when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            -> {
                log.debug("File changed: {}, re-indexing...", filePath.fileName)
                EventBus.emit(IndexingStartedEvent(projectId = projectId, projectName = projectName))
                val success = reindexFile(filePath)
                if (success) {
                    EventBus.emit(
                        IndexingCompletedEvent(
                            projectId = projectId,
                            projectName = projectName,
                            filesIndexed = 1,
                        ),
                    )
                } else {
                    EventBus.emit(
                        IndexingFailedEvent(
                            projectId = projectId,
                            projectName = projectName,
                            errorMessage = "Failed to re-index '${filePath.fileName}'",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Re-index a file. Returns `true` if the file was (re-)indexed successfully or
     * intentionally skipped (e.g. blank/no extractable content), `false` if an error occurred.
     */
    private suspend fun reindexFile(filePath: Path): Boolean {
        if (!filePath.exists()) {
            log.warn("Cannot re-index non-existent file: ${filePath.fileName}")
            return false
        }

        try {
            batchIndexer.removeFileFromIndex(filePath)

            val text = resourceContentProcessor.extractTextFromFile(filePath) ?: return true

            if (text.isBlank()) {
                log.debug("Skipping re-index of file with blank content: {}", filePath.fileName)
                return true
            }

            // Check if this is a text file where line numbers are meaningful
            val isTextFile = resourceContentProcessor.isTextFile(filePath)

            if (isTextFile) {
                // For text files, use line-aware chunking
                val chunksWithLineNumbers = resourceContentProcessor.chunkTextWithLineNumbers(text)

                if (chunksWithLineNumbers.isEmpty()) {
                    log.debug("No valid chunks created for file: {}", filePath.fileName)
                    return true
                }

                for ((idx, chunkData) in chunksWithLineNumbers.withIndex()) {
                    val segment = resourceContentProcessor.createTextSegmentWithMetadata(
                        chunk = chunkData.text,
                        filePath = filePath,
                        chunkIndex = idx,
                        totalChunks = chunksWithLineNumbers.size,
                        startLine = chunkData.startLine,
                        endLine = chunkData.endLine,
                    )

                    batchIndexer.addSegmentToBatch(segment, filePath)
                }

                batchIndexer.flushRemainingSegments()

                log.debug("Re-indexed {} ({} chunks, lines tracked)", filePath.fileName, chunksWithLineNumbers.size)
            } else {
                val chunks = resourceContentProcessor.chunkText(text)

                if (chunks.isEmpty()) {
                    log.debug("No valid chunks created for file: {}", filePath.fileName)
                    return true
                }

                for ((idx, chunk) in chunks.withIndex()) {
                    val segment = resourceContentProcessor.createTextSegmentWithMetadata(
                        chunk = chunk,
                        filePath = filePath,
                        chunkIndex = idx,
                        totalChunks = chunks.size,
                    )

                    batchIndexer.addSegmentToBatch(segment, filePath)
                }

                batchIndexer.flushRemainingSegments()

                log.debug("Re-indexed {} ({} chunks)", filePath.fileName, chunks.size)
            }
            return true
        } catch (e: Exception) {
            log.error("Failed to re-index file {}", filePath.fileName, e)
            return false
        }
    }
}
