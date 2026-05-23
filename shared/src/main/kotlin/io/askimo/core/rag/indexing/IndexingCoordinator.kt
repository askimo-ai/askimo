/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.rag.state.IndexProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/**
 * Coordinator for indexing knowledge sources.
 * Each knowledge source type (files, web pages, databases) implements this interface
 * to handle indexing and watching for changes in its own way.
 *
 * The type parameter [T] represents the specific [KnowledgeSourceConfig] subtype
 * used by this coordinator. The `out` variance allows coordinators with specific
 * config types to be used where the base type is expected.
 */
interface IndexingCoordinator<out T : KnowledgeSourceConfig> : Closeable {
    /**
     * The knowledge source configuration this coordinator is responsible for.
     */
    val knowledgeSourceConfig: T

    /**
     * Progress of the indexing operation.
     */
    val progress: StateFlow<IndexProgress>

    /**
     * Start indexing with progress tracking.
     * @return true if successful, false otherwise
     */
    suspend fun startIndexing(): Boolean

    /**
     * Start watching for changes (if applicable for this knowledge source type).
     * @param scope The coroutine scope for watching
     */
    fun startWatching(scope: CoroutineScope)

    /**
     * Stop watching for changes.
     */
    fun stopWatching()

    /**
     * Mark this coordinator as queued — waiting for another project to finish indexing.
     * Called by [ProjectIndexer] before acquiring the global indexing mutex.
     */
    fun markQueued()

    /**
     * Clear all indexed data for this knowledge source.
     * This is used when the project is deleted or reset.
     */
    fun clearAll()
}
