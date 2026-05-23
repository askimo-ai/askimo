/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import io.askimo.core.chat.util.UrlContentExtractor
import io.askimo.core.context.AppContext
import io.askimo.core.logging.logger
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStateManager
import io.askimo.core.rag.state.IndexStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates the indexing process for URL-based knowledge sources.
 * Fetches web content via HTTP and indexes it for retrieval.
 */
class UrlIndexingCoordinator(
    private val projectId: String,
    private val projectName: String,
    override val knowledgeSourceConfig: UrlKnowledgeSourceConfig,
    private val embeddingStore: EmbeddingStore<TextSegment>,
    private val embeddingModel: EmbeddingModel,
    private val appContext: AppContext,
) : IndexingCoordinator<UrlKnowledgeSourceConfig> {
    private val log = logger<UrlIndexingCoordinator>()

    private val urls = listOf(knowledgeSourceConfig.resourceIdentifier)

    private val resourceContentProcessor = ResourceContentProcessor(appContext)
    private val stateManager = IndexStateManager(projectId, "urls", knowledgeSourceConfig.resourceIdentifier)
    private val hybridIndexer = HybridIndexer(embeddingStore, embeddingModel, projectId)

    private val _progress = MutableStateFlow(IndexProgress())
    override val progress: StateFlow<IndexProgress> = _progress

    override fun markQueued() {
        _progress.value = _progress.value.copy(status = IndexStatus.QUEUED)
    }

    private val processedUrlsCounter = AtomicInteger(0)
    private val totalUrlsCounter = AtomicInteger(0)

    /**
     * Index URLs with progress tracking.
     * Fetches content from each URL and indexes it.
     */
    override suspend fun startIndexing(): Boolean {
        _progress.value = IndexProgress(status = IndexStatus.INDEXING)

        // Reset counters
        processedUrlsCounter.set(0)
        totalUrlsCounter.set(urls.size)

        try {
            val previousState = stateManager.loadPersistedState()
            val previousHashes: Map<String, String> = previousState?.fileHashes ?: emptyMap()

            log.info("Starting URL indexing for project $projectId with ${urls.size} URLs")

            val urlHashes = ConcurrentHashMap<String, String>()

            _progress.value = _progress.value.copy(totalFiles = urls.size)

            // Process URLs sequentially
            for (url in urls) {
                try {
                    indexUrl(url, urlHashes, previousHashes)
                } catch (e: Exception) {
                    log.error("Failed to index URL: $url", e)
                }
            }

            // Detect and remove deleted URLs
            val deletedUrls = detectDeletedUrls(previousHashes, urlHashes)
            if (deletedUrls.isNotEmpty()) {
                log.info("Detected ${deletedUrls.size} removed URLs, removing from index...")
                removeDeletedUrlsFromIndex(deletedUrls)
            }

            val skippedUrls = previousHashes.keys.intersect(urlHashes.keys).count { key ->
                previousHashes[key] == urlHashes[key]
            }

            // Flush any remaining segments
            if (!hybridIndexer.flushRemainingSegments()) {
                _progress.value = _progress.value.copy(
                    status = IndexStatus.FAILED,
                    error = "Failed to flush remaining segments",
                )
                return false
            }

            // Save state
            val processedUrls = urlHashes.size
            stateManager.saveState(processedUrls, urlHashes.toMap())

            _progress.value = _progress.value.copy(
                status = IndexStatus.READY,
                processedFiles = processedUrls,
            )

            log.info(
                "Completed URL indexing for project $projectId: " +
                    "${processedUrls - skippedUrls} URLs indexed, " +
                    "$skippedUrls URLs skipped (unchanged), " +
                    "${deletedUrls.size} URLs removed",
            )
            return true
        } catch (e: Exception) {
            log.error("URL indexing failed for project $projectId", e)
            _progress.value = _progress.value.copy(
                status = IndexStatus.FAILED,
                error = e.message ?: "Unknown error",
            )
            return false
        }
    }

    /**
     * Index a single URL.
     */
    private suspend fun indexUrl(
        url: String,
        urlHashes: MutableMap<String, String>,
        previousHashes: Map<String, String>,
    ) {
        log.debug("Processing URL: $url")

        try {
            val urlHash = url.hashCode().toString()

            // Check if content has changed
            if (previousHashes[url] == urlHash) {
                log.debug("URL unchanged, skipping: $url")
                urlHashes[url] = urlHash
                processedUrlsCounter.incrementAndGet()
                _progress.value = _progress.value.copy(processedFiles = processedUrlsCounter.get())
                return
            }

            // Process and index the content
            val extractedContent = UrlContentExtractor.extractContent(url)
            val segments = resourceContentProcessor.processWebContent(
                url = url,
                content = extractedContent.content,
                metadata = mapOf(
                    "project_id" to projectId,
                    "project_name" to projectName,
                    "source_type" to "url",
                    "url" to url,
                    "title" to (extractedContent.title ?: ""),
                    "file_name" to (extractedContent.title ?: extractTitleFromUrl(url)),
                    "content_type" to extractedContent.contentType,
                ),
            )

            if (segments.isNotEmpty()) {
                // For URLs, we use a synthetic path based on the URL
                val syntheticPath = Paths.get(System.getProperty("java.io.tmpdir"), url.hashCode().toString())
                for (segment in segments) {
                    hybridIndexer.addSegmentToBatch(segment, syntheticPath)
                }
                urlHashes[url] = urlHash
                log.info("Successfully indexed URL: $url (${segments.size} segments)")
            } else {
                log.warn("No content extracted from URL: $url")
            }

            processedUrlsCounter.incrementAndGet()
            _progress.value = _progress.value.copy(processedFiles = processedUrlsCounter.get())
        } catch (e: Exception) {
            log.error("Failed to index URL: $url", e)
            throw e
        }
    }

    /**
     * Detect URLs that were in the previous state but are no longer in current URLs.
     */
    private fun detectDeletedUrls(
        previousHashes: Map<String, String>,
        currentHashes: Map<String, String>,
    ): List<String> = previousHashes.keys.filter { it !in currentHashes.keys }

    /**
     * Extract a user-friendly title from a URL.
     */
    private fun extractTitleFromUrl(url: String): String = try {
        val uri = java.net.URI(url)
        val path = uri.path.trim('/')

        if (path.isNotBlank()) {
            path.split('/').last()
                .replace(Regex("[_-]"), " ")
                .replaceFirstChar { it.uppercase() }
        } else {
            uri.host
        }
    } catch (_: Exception) {
        url
    }

    /**
     * Remove deleted URLs from the index.
     */
    private fun removeDeletedUrlsFromIndex(deletedUrls: List<String>) {
        for (url in deletedUrls) {
            log.info("Removing deleted URL from index: $url")
            val syntheticPath = Paths.get(System.getProperty("java.io.tmpdir"), url.hashCode().toString())
            hybridIndexer.removeFileFromIndex(syntheticPath)
        }
    }

    /**
     * URLs don't support watching for changes.
     */
    override fun startWatching(scope: CoroutineScope) {
        log.debug("URL sources do not support watching for changes")
    }

    /**
     * Nothing to stop for URL sources.
     */
    override fun stopWatching() {
        // No-op
    }

    /**
     * Clear all indexed data for this URL source:
     * - Removes the synthetic path segment from the embedding store and Lucene index
     * - Clears the DB hash-state records
     */
    override fun clearAll() {
        try {
            val syntheticPath = Paths.get(
                System.getProperty("java.io.tmpdir"),
                knowledgeSourceConfig.resourceIdentifier.hashCode().toString(),
            )
            hybridIndexer.removeFileFromIndex(syntheticPath)
        } catch (e: Exception) {
            log.error("Failed to clear hybrid index for URL ${knowledgeSourceConfig.resourceIdentifier} in project $projectId", e)
        }
        stateManager.clearStates()
        log.info("Cleared all index states for project $projectId (url: ${knowledgeSourceConfig.resourceIdentifier})")
    }

    override fun close() {
        stopWatching()
    }
}
