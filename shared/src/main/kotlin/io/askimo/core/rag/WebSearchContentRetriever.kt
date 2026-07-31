/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.rag.content.Content
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.query.Query
import io.askimo.core.logging.logger
import io.askimo.tools.web.SearchBackend

/**
 *
 * Bridges the gap between askimo's own web search abstraction and the
 * LangChain4j RAG pipeline. Each search result is converted into a
 * [Content] whose text is: `"<title>\n<snippet>\nSource: <url>"`, with
 * `source`, `title`, and `type=web` stored in [Metadata] so that
 * [MetadataAwareContentInjector] can cite the URL in AI responses.
 *
 * Used by [HybridContentRetriever] when the user enables live web search
 * for a project chat session via the UI chip.
 *
 * @param backend The resolved [SearchBackend] (DuckDuckGo, Brave, Tavily, SearxNG).
 * @param maxResults Maximum number of web results to fetch per query (default 3).
 */
class WebSearchContentRetriever(
    private val backend: SearchBackend,
    private val maxResults: Int = 3,
) : ContentRetriever {

    private val log = logger<WebSearchContentRetriever>()

    override fun retrieve(query: Query): List<Content> {
        log.debug("Web search retrieval via '{}' for query: {}", backend.name, query.text())

        val results = backend.search(query.text(), maxResults)

        return results.map { result ->
            val text = buildString {
                appendLine(result.title)
                if (result.snippet.isNotBlank()) appendLine(result.snippet)
                append("Source: ${result.url}")
            }
            val metadata = Metadata.from(
                mapOf(
                    "source" to result.url,
                    "title" to result.title,
                    "type" to "web",
                ),
            )
            Content.from(TextSegment.from(text, metadata))
        }
    }
}
