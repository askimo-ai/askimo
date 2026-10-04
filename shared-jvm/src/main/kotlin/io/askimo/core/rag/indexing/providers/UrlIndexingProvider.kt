/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing.providers

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import io.askimo.core.context.AppContext
import io.askimo.core.rag.container.IndexingContainerType
import io.askimo.core.rag.indexing.IndexingCoordinator
import io.askimo.core.rag.indexing.IndexingCoordinatorProvider
import io.askimo.core.rag.indexing.UrlIndexingCoordinator

/**
 * Provider for creating IndexingCoordinator instances for URL knowledge sources.
 */
class UrlIndexingProvider : IndexingCoordinatorProvider {
    override fun supportedType(): Class<out KnowledgeSourceConfig> = UrlKnowledgeSourceConfig::class.java

    override fun createCoordinator(
        containerId: String,
        containerName: String,
        containerType: IndexingContainerType,
        knowledgeSource: KnowledgeSourceConfig,
        embeddingStore: EmbeddingStore<TextSegment>,
        embeddingModel: EmbeddingModel,
        appContext: AppContext,
    ): IndexingCoordinator<KnowledgeSourceConfig> = UrlIndexingCoordinator(
        containerId = containerId,
        containerName = containerName,
        containerType = containerType,
        knowledgeSourceConfig = knowledgeSource as UrlKnowledgeSourceConfig,
        embeddingStore = embeddingStore,
        embeddingModel = embeddingModel,
        appContext = appContext,
    )
}
