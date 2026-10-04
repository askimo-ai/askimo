/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.community.store.embedding.jvector.JVectorEmbeddingStore
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.config.AppConfig
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatClient
import io.askimo.core.util.AskimoHome
import io.askimo.tools.web.WebSearchDispatcher
import java.nio.file.Path
import java.util.Properties

/**
 * Utility functions for RAG (Retrieval-Augmented Generation) module.
 */
object RagUtils {
    private val log = logger<RagUtils>()

    /**
     * Get the index directory path for a project.
     * Creates the directory if it doesn't exist.
     *
     * @param projectId The project ID
     * @param createIfNotExists Whether to create the directory if it doesn't exist (default: true)
     * @return Path to the project's index directory
     */
    fun getProjectIndexDir(projectId: String, createIfNotExists: Boolean = true): Path {
        val indexDir = AskimoHome.projectsDir().resolve(projectId).resolve("index")

        if (createIfNotExists) {
            indexDir.toFile().mkdirs()
        }

        return indexDir
    }

    fun getProjectJVectorIndexDir(projectId: String): Path = getProjectIndexDir(projectId, true).resolve("jvector")

    fun getProjectLuceneIndexDir(projectId: String): Path = getProjectIndexDir(projectId, true).resolve("lucene")

    /**
     * Get embedding dimension for the model by testing it with a sample text.
     * Falls back to 384 (common dimension for many models) if detection fails.
     *
     * @param embeddingModel The embedding model to test
     * @return The dimension of the embedding vectors
     */
    fun getDimensionForModel(embeddingModel: EmbeddingModel): Int = try {
        val testSegment = TextSegment.from("test")
        val embedding = embeddingModel.embed(testSegment).content()
        embedding.vector().size
    } catch (e: Exception) {
        log.warn("Failed to detect embedding dimension, using default 384", e)
        384
    }

    fun getEmbeddingStore(projectId: String, embeddingModel: EmbeddingModel): EmbeddingStore<TextSegment> = getEmbeddingStoreWithDimension(projectId, getDimensionForModel(embeddingModel))

    fun getEmbeddingStoreWithDimension(projectId: String, dimension: Int): EmbeddingStore<TextSegment> {
        val jVectorIndexDir = getProjectJVectorIndexDir(projectId)

        return JVectorEmbeddingStore.builder()
            .dimension(dimension)
            .persistencePath(jVectorIndexDir.toString())
            .build()
    }

    /**
     * Read the stored embedding dimension from index.meta for the given project.
     * Returns null if the file does not exist or cannot be read.
     */
    fun getStoredEmbeddingDimension(projectId: String): Int? {
        val metaFile = getProjectIndexDir(projectId, createIfNotExists = false)
            .resolve("index.meta").toFile()
        if (!metaFile.exists()) return null
        return try {
            val props = Properties()
            metaFile.inputStream().use { props.load(it) }
            props.getProperty("embeddingDimension")?.toIntOrNull()
        } catch (e: Exception) {
            log.warn("Failed to read index.meta for project {}", projectId, e)
            null
        }
    }

    /**
     * Read the stored embedding model identity (e.g. `"OPENAI:text-embedding-3-small"`) from
     * index.meta for the given project. Returns null if the file/property does not exist,
     * which is the case for indexes created before this field was introduced.
     *
     * This detects model changes that a dimension-only comparison would miss — e.g. switching
     * between two different models (or providers) that happen to share the same output
     * dimension, which would otherwise silently reuse stale, semantically-incompatible vectors.
     * See [io.askimo.core.context.AppContext.activeEmbeddingModelIdentity].
     */
    fun getStoredEmbeddingModelId(projectId: String): String? {
        val metaFile = getProjectIndexDir(projectId, createIfNotExists = false)
            .resolve("index.meta").toFile()
        if (!metaFile.exists()) return null
        return try {
            val props = Properties()
            metaFile.inputStream().use { props.load(it) }
            props.getProperty("embeddingModelId")
        } catch (e: Exception) {
            log.warn("Failed to read embeddingModelId from index.meta for project {}", projectId, e)
            null
        }
    }

    /**
     * Persist the current embedding dimension and model identity to index.meta for the given
     * project. The model identity is optional (omitted when unavailable, e.g. no active
     * instance) for backward compatibility with the dimension-only mismatch check.
     */
    fun saveEmbeddingMetadata(projectId: String, dimension: Int, modelId: String?) {
        val metaFile = getProjectIndexDir(projectId, createIfNotExists = true)
            .resolve("index.meta").toFile()
        try {
            val props = Properties()
            props.setProperty("embeddingDimension", dimension.toString())
            modelId?.let { props.setProperty("embeddingModelId", it) }
            metaFile.outputStream().use { props.store(it, "Askimo RAG index metadata") }
        } catch (e: Exception) {
            log.warn("Failed to write index.meta for project {}", projectId, e)
        }
    }

    fun enrichContentRetrieverWithLucene(
        classifierChatClient: ChatClient,
        projectId: String,
        retriever: ContentRetriever,
        knowledgeSourcePaths: List<String> = emptyList(),
        useWebSearch: Boolean = false,
    ): ContentRetriever {
        val ragConfig = AppConfig.rag

        val webRetriever: ContentRetriever? = if (useWebSearch && AppConfig.webSearch.enabled) {
            val backend = WebSearchDispatcher.activeBackend(AppConfig.webSearch)
            log.debug("Web search enabled for RAG pipeline using backend: {}", backend.name)
            WebSearchContentRetriever(backend, maxResults = 3)
        } else {
            null
        }

        return RAGContentProcessor(
            HybridContentRetriever(
                vectorRetriever = retriever,
                keywordRetriever = LuceneKeywordRetriever(projectId),
                maxResults = ragConfig.hybridMaxResults,
                k = ragConfig.rankFusionConstant,
                webRetriever = webRetriever,
            ),
            classifierChatClient,
            knowledgeSourcePaths,
        )
    }
}
