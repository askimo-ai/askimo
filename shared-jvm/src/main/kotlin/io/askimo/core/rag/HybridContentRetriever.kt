/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.rag.content.Content
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.query.Query
import io.askimo.core.logging.logger

/**
 * Hybrid content retriever that combines multiple retrieval strategies.
 * Uses Reciprocal Rank Fusion (RRF) to merge results from:
 * - Vector similarity search (semantic)
 * - Keyword search (BM25/Lucene)
 * - Live web search (optional, enabled per-session via the UI chip)
 *
 * RRF Formula: score(doc) = Σ 1 / (k + rank_i)
 * where k=60 (standard constant) and rank_i is the rank from retriever i
 *
 * This approach gives better results than pure vector search by:
 * - Catching exact keyword matches
 * - Leveraging semantic understanding
 * - Being robust to individual retriever failures
 *
 * @param vectorRetriever The vector-based semantic search retriever
 * @param keywordRetriever The keyword-based (BM25/Lucene) retriever
 * @param maxResults Maximum number of final results to return after fusion
 * @param k RRF constant for rank fusion (configured via AppConfig.rag.rankFusionConstant)
 * @param webRetriever Optional live web search retriever (null = disabled)
 */
class HybridContentRetriever(
    private val vectorRetriever: ContentRetriever,
    private val keywordRetriever: ContentRetriever,
    private val maxResults: Int,
    private val k: Int,
    private val webRetriever: ContentRetriever? = null,
) : ContentRetriever {

    private val log = logger<HybridContentRetriever>()

    override fun retrieve(query: Query): List<Content> {
        log.debug("Hybrid retrieval for query: ${query.text()}")

        // Retrieve from both sources
        val vectorResults = try {
            vectorRetriever.retrieve(query)
        } catch (e: Exception) {
            log.warn("Vector retrieval failed: ${e.message}", e)
            emptyList()
        }

        val keywordResults = try {
            keywordRetriever.retrieve(query)
        } catch (e: Exception) {
            log.warn("Keyword retrieval failed: ${e.message}")
            emptyList<Content>()
        }

        val webResults = webRetriever?.let {
            try {
                it.retrieve(query)
            } catch (e: Exception) {
                log.warn("Web retrieval failed: ${e.message}")
                emptyList()
            }
        } ?: emptyList()

        log.debug("Vector retrieval: ${vectorResults.size} results")
        log.debug("Keyword retrieval: ${keywordResults.size} results")
        log.debug("Web retrieval: ${webResults.size} results")

        // If all retrievers fail, return empty
        if (vectorResults.isEmpty() && keywordResults.isEmpty() && webResults.isEmpty()) {
            log.warn("All retrievers returned no results")
            return emptyList()
        }

        // If only one source has results, return it directly
        if (vectorResults.isEmpty() && keywordResults.isEmpty()) {
            log.debug("Using only web results (local retrievers returned nothing)")
            return webResults.take(maxResults)
        }

        if (vectorResults.isEmpty() && webResults.isEmpty()) {
            log.debug("Using only keyword results (vector/web retrieval failed)")
            return keywordResults.take(maxResults)
        }

        if (keywordResults.isEmpty() && webResults.isEmpty()) {
            log.debug("Using only vector results (keyword/web retrieval failed)")
            return vectorResults.take(maxResults)
        }

        // Merge using Reciprocal Rank Fusion across all available sources
        val merged = reciprocalRankFusion(vectorResults, keywordResults, webResults)

        log.debug(
            "Hybrid retrieval merged ${vectorResults.size} vector + ${keywordResults.size} keyword" +
                (if (webResults.isNotEmpty()) " + ${webResults.size} web" else "") +
                " → ${merged.size} results",
        )

        return merged.take(maxResults)
    }

    /**
     * Reciprocal Rank Fusion (RRF) algorithm.
     * Combines ranked lists by assigning scores based on rank position.
     *
     * For each document:
     *   RRF_score = Σ 1 / (k + rank_in_list_i)
     *
     * Documents are then sorted by RRF score (higher is better).
     */
    private fun reciprocalRankFusion(
        vectorResults: List<Content>,
        keywordResults: List<Content>,
        webResults: List<Content> = emptyList(),
    ): List<Content> {
        // Map content to unique key for deduplication
        // Using text segment content as key (assumes same content = same document)
        val contentMap = mutableMapOf<String, Content>()
        val scoreMap = mutableMapOf<String, Double>()

        fun processResults(results: List<Content>) {
            results.forEachIndexed { index, content ->
                val key = content.textSegment().text()
                val rank = index + 1
                val score = 1.0 / (k + rank)
                if (key !in contentMap) contentMap[key] = content
                scoreMap[key] = scoreMap.getOrDefault(key, 0.0) + score
            }
        }

        processResults(vectorResults)
        processResults(keywordResults)
        processResults(webResults)

        // Sort by RRF score (descending)
        val sortedKeys = scoreMap.entries
            .sortedByDescending { it.value }
            .map { it.key }

        val totalInput = vectorResults.size + keywordResults.size + webResults.size
        log.debug("RRF fusion: ${contentMap.size} unique documents from $totalInput total results")

        return sortedKeys.mapNotNull { contentMap[it] }
    }
}
