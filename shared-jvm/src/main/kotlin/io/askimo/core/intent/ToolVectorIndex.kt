/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

import dev.langchain4j.community.store.embedding.jvector.JVectorEmbeddingStore
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import io.askimo.core.logging.logger

/**
 * In-memory vector index for MCP tool descriptions.
 *
 * **How it works**
 * - Index time: each tool's *description* is embedded as the primary semantic signal.
 *   The humanised name and category are appended as secondary context so the model
 *   understands what domain the tool belongs to.
 * - Query time: the user message is embedded and compared against every stored
 *   description via cosine similarity; the closest tools are returned.
 *
 * The tool *name* is never part of the similarity calculation — it is only stored
 * in segment metadata as a lookup key so we can retrieve the original [ToolConfig]
 * after the search.
 *
 * This is intentionally separate from the RAG embedding store (project-scoped,
 * persisted to disk). This index is:
 *  - In-memory only — rebuilt whenever tools are (re-)loaded for a project
 *  - Scoped to [ToolConfig] objects, not document chunks
 *  - Queried to return [ToolConfig] objects, not text content
 */
class ToolVectorIndex(
    private val embeddingModel: EmbeddingModel,
    /** Minimum cosine similarity score to include a tool in results (0.0–1.0). */
    private val minScore: Double = 0.60,
    /** Maximum number of tools to return per query. */
    private val topK: Int = 5,
) {
    private val log = logger<ToolVectorIndex>()

    companion object {
        // Stored in segment metadata only as an identifier for reverse-lookup after search.
        // It plays no role in the similarity calculation.
        private const val KEY_TOOL_NAME = "toolName"
    }

    private var store: JVectorEmbeddingStore = buildStore()
    private val toolsByName = mutableMapOf<String, ToolConfig>()

    /**
     * (Re-)index a list of tools. Safe to call multiple times — clears the previous index.
     *
     * Tools without a description are skipped: without description text there is no
     * meaningful semantic signal to embed, so including them would only add noise.
     */
    fun index(tools: List<ToolConfig>) {
        store = buildStore()
        toolsByName.clear()

        val (withDesc, withoutDesc) = tools.partition { !it.specification.description().isNullOrBlank() }

        withoutDesc.forEach { tool ->
            log.debug(
                "Skipping tool '{}' from vector index — no description to embed",
                tool.specification.name(),
            )
        }

        if (withDesc.isEmpty()) {
            log.debug(
                "ToolVectorIndex built: 0 indexed, {} skipped (no description or error)",
                withoutDesc.size,
            )
            return
        }

        val segments = withDesc.map { tool ->
            TextSegment.from(
                // Description is the primary signal; humanised name and category
                // are appended as lightweight context.
                buildIndexText(tool, tool.specification.description()!!),
                // Name stored only as a reverse-lookup key, NOT embedded
                Metadata.from(KEY_TOOL_NAME, tool.specification.name()),
            )
        }

        var indexed = 0
        val skipped = withoutDesc.size

        try {
            // Single batched API call instead of N sequential calls — O(1) round-trips
            val embeddings = embeddingModel.embedAll(segments).content()

            embeddings.zip(segments).zip(withDesc).forEach { (pair, tool) ->
                val (embedding, segment) = pair
                store.add(embedding, segment)
                toolsByName[tool.specification.name()] = tool
                indexed++
            }
        } catch (e: Exception) {
            log.warn("Batch embedding failed for {} tools: {}", withDesc.size, e.message)
        }

        log.debug(
            "ToolVectorIndex built: {} indexed, {} skipped (no description or error)",
            indexed,
            skipped,
        )
    }

    /**
     * Find tools whose description is most semantically similar to [userMessage].
     * Returns an empty list when no tools meet the [minScore] threshold.
     */
    fun search(userMessage: String): List<Pair<ToolConfig, Double>> {
        if (toolsByName.isEmpty()) return emptyList()

        return try {
            val queryEmbedding = embeddingModel.embed(userMessage).content()
            val request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build()

            store.search(request).matches()
                .mapNotNull { match ->
                    // Use the metadata name only as a key to retrieve the ToolConfig
                    val toolName = match.embedded().metadata().getString(KEY_TOOL_NAME)
                    val tool = toolsByName[toolName]
                    if (tool != null) tool to match.score() else null
                }
                .also { results ->
                    log.debug(
                        "Vector search '{}' → {} match(es){}",
                        userMessage.take(60),
                        results.size,
                        if (results.isNotEmpty()) {
                            ": " + results.joinToString { (t, s) -> "${t.specification.name()}(${String.format("%.2f", s)})" }
                        } else {
                            ""
                        },
                    )
                }
        } catch (e: Exception) {
            log.warn("Vector search failed for '{}': {}", userMessage.take(60), e.message)
            emptyList()
        }
    }

    /**
     * Constructs the text that is sent to the embedding model for a tool.
     *
     * Layout (in order of semantic importance):
     * 1. **Description** — the primary signal; describes *what the tool does*
     * 2. **Category**    — domain context (e.g. "version control", "database")
     * 3. **Name**        — humanised identifier appended last as weak context
     *
     * Example output:
     * ```
     * Creates a new pull request in a GitHub repository. Category: version control. Tool: create pull request
     * ```
     */
    private fun buildIndexText(tool: ToolConfig, description: String): String = buildString {
        // 1. Description — primary semantic content
        append(description.trim())

        // 2. Category — domain context
        append(". Category: ")
        append(tool.category.name.lowercase().replace("_", " "))

        // 3. Humanised name — weak supplementary context
        append(". Tool: ")
        append(tool.specification.name().replace("_", " "))
    }

    private fun buildStore(): JVectorEmbeddingStore = JVectorEmbeddingStore.builder()
        .dimension(embeddingModel.dimension())
        .build()
}
