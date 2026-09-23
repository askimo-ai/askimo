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
import io.askimo.core.rag.container.IndexingContainerType

/**
 * Provider interface for creating IndexingCoordinator instances.
 * Implement this interface to add support for new knowledge source types.
 *
 * Providers can be registered:
 * 1. Programmatically via [IndexingCoordinatorProviderRegistry.registerProvider]
 * 2. Automatically via Java ServiceLoader mechanism
 */
interface IndexingCoordinatorProvider {
    /**
     * Returns the type of KnowledgeSourceConfig this provider handles.
     * This is used to match providers to knowledge sources.
     *
     * @return The Class object representing the supported KnowledgeSourceConfig type
     */
    fun supportedType(): Class<out KnowledgeSourceConfig>

    /**
     * Creates an IndexingCoordinator for the given knowledge source.
     *
     * @param containerId The container (project or resource collection) ID
     * @param containerName The container (project or resource collection) name
     * @param containerType Whether this container is a project or a resource collection
     * @param knowledgeSource The knowledge source configuration
     * @param embeddingStore The embedding store for storing vectors
     * @param embeddingModel The embedding model for generating embeddings
     * @param appContext The application context
     * @return A configured IndexingCoordinator instance
     * @throws IllegalArgumentException if the knowledge source type is not supported
     */
    fun createCoordinator(
        containerId: String,
        containerName: String,
        containerType: IndexingContainerType,
        knowledgeSource: KnowledgeSourceConfig,
        embeddingStore: EmbeddingStore<TextSegment>,
        embeddingModel: EmbeddingModel,
        appContext: AppContext,
    ): IndexingCoordinator<KnowledgeSourceConfig>
}
