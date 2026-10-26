/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.container

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.chat.domain.ResourceCollectionOrigin

/**
 * Discriminator for the kind of entity indexed by [io.askimo.core.rag.RagIndexer]. Used
 * throughout the indexing event pipeline so consumers (ViewModels, UI) can filter events
 * to the container they care about — a Project view must never react to a Resource
 * Collection's indexing progress and vice versa.
 */
enum class IndexingContainerType {
    PROJECT,
    RESOURCE_COLLECTION,
}

/**
 * Abstraction over anything indexable for RAG: a [Project] or a [ResourceCollection].
 * Lets [io.askimo.core.rag.RagIndexer] treat both uniformly (same queue, coordinators,
 * progress tracking) while still reporting which concrete kind an event relates to.
 */
sealed class IndexingContainer {
    abstract val id: String
    abstract val name: String
    abstract val knowledgeSources: List<KnowledgeSourceConfig>
    abstract val type: IndexingContainerType

    data class ProjectContainer(val project: Project) : IndexingContainer() {
        override val id: String = project.id
        override val name: String = project.name
        override val knowledgeSources: List<KnowledgeSourceConfig> = project.knowledgeSources
        override val type: IndexingContainerType = IndexingContainerType.PROJECT
    }

    data class ResourceCollectionContainer(val collection: ResourceCollection) : IndexingContainer() {
        override val id: String = collection.id
        override val name: String = collection.name
        override val knowledgeSources: List<KnowledgeSourceConfig> = collection.knowledgeSources
        override val type: IndexingContainerType = IndexingContainerType.RESOURCE_COLLECTION
    }

    /**
     * A [ResourceCollection] whose [ResourceCollection.origin] is
     * [ResourceCollectionOrigin.TEAM_MANAGED] — indexed and retrieved entirely by a
     * remote backend, not on this device. Kept as a distinct variant (rather than
     * reusing [ResourceCollectionContainer]) so
     * [io.askimo.core.chat.service.ChatSessionService.createRetrieverForContainer]
     * can pattern-match it directly and skip the local hybrid-retriever pipeline —
     * there's no local embedding store or Lucene index for these rows.
     * [knowledgeSources] is always empty; [io.askimo.core.rag.RagIndexer] never
     * receives indexing events for this container (no local "add knowledge
     * source" action exists for it), so no indexing-side special-casing is needed.
     */
    data class ServerResourceCollectionContainer(val collection: ResourceCollection) : IndexingContainer() {
        override val id: String = collection.id
        override val name: String = collection.name
        override val knowledgeSources: List<KnowledgeSourceConfig> = collection.knowledgeSources
        override val type: IndexingContainerType = IndexingContainerType.RESOURCE_COLLECTION
    }
}

/** Convenience wrapper for a [Project] as an [IndexingContainer]. */
fun Project.asIndexingContainer(): IndexingContainer = IndexingContainer.ProjectContainer(this)

/**
 * Convenience wrapper for a [ResourceCollection] as an [IndexingContainer] —
 * dispatches on [ResourceCollection.origin] so callers never need to check it
 * themselves.
 */
fun ResourceCollection.asIndexingContainer(): IndexingContainer = when (origin) {
    ResourceCollectionOrigin.LOCAL -> IndexingContainer.ResourceCollectionContainer(this)
    ResourceCollectionOrigin.TEAM_MANAGED -> IndexingContainer.ServerResourceCollectionContainer(this)
}
