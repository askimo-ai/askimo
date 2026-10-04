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
 * Factory for creating appropriate IndexingCoordinator based on knowledge source types.
 * Uses a pluggable provider registry to support extensibility.
 */
object IndexingCoordinatorFactory {

    /**
     * Create an indexing coordinator for a single knowledge source.
     *
     * @param containerId The container (project or resource collection) ID
     * @param containerName The container (project or resource collection) name
     * @param containerType Whether this container is a project or a resource collection
     * @param knowledgeSource The knowledge source to index
     * @param embeddingStore The embedding store
     * @param embeddingModel The embedding model
     * @param appContext The application context
     * @return An IndexingCoordinator instance
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
    ): IndexingCoordinator<KnowledgeSourceConfig> {
        val provider = IndexingCoordinatorProviderRegistry.getProvider(knowledgeSource)
            ?: throw IllegalArgumentException(
                "No provider registered for knowledge source type: ${knowledgeSource::class.simpleName}. " +
                    "Available types: ${IndexingCoordinatorProviderRegistry.getAllProviders()
                        .joinToString { it.supportedType().simpleName }}",
            )

        return provider.createCoordinator(
            containerId = containerId,
            containerName = containerName,
            containerType = containerType,
            knowledgeSource = knowledgeSource,
            embeddingStore = embeddingStore,
            embeddingModel = embeddingModel,
            appContext = appContext,
        )
    }
}
