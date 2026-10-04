/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.config.AppConfig
import io.askimo.core.logging.logger
import io.askimo.tools.ToolResponseBuilder
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Web search and page reading tools.
 *
 * - [searchWeb]: Searches the web using Brave Search API (requires `BRAVE_SEARCH_API_KEY`
 *   env variable) and returns structured results with titles, URLs, and snippets.
 * - [readWebPage]: Fetches and extracts clean text content from any URL using Jsoup.
 */
object WebSearchTools {
    private const val CLASS_NAME = "io.askimo.tools.web.WebSearchTools"
    private const val PAGE_TIMEOUT_MS = 15_000
    private const val USER_AGENT =
        "Mozilla/5.0 (compatible; Askimo/1.0; +https://$DOMAIN)"

    private val log = logger<WebSearchTools>()

    /**
     * Search the web for current information using the configured backend.
     *
     * @param query      The search query string.
     * @param maxResults Maximum number of results to return (1–10, default 5).
     * @return JSON with a list of results (title, url, snippet) and the backend used.
     */
    @Tool(
        """Search the web for current information, recent news, or any topic that needs up-to-date data.

Use this tool when the user asks about:
- Recent events, news, or anything that may have changed recently
- Current prices, statistics, or live data
- Topics the AI might not have knowledge about
- Finding specific websites, products, people, or organisations

WORKFLOW:
1. Call searchWeb() with a concise, targeted query.
2. Review the returned titles and snippets.
3. Call readWebPage() on the most relevant URL(s) to get full content if needed.
4. Summarise the findings directly to the user.

OUTPUT FORMAT:
Present results as a numbered list with title, URL, and a brief note about each.
Always cite the source URLs in your response.
        """,
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"searchWeb\" }",
    )
    fun searchWeb(
        @P("The search query string — be concise and specific") query: String,
        @P("Maximum number of results to return, between 1 and 10. Default 5.") maxResults: Int = 5,
    ): String = try {
        require(query.isNotBlank()) { "Query cannot be blank" }

        val config = AppConfig.webSearch
        if (!config.enabled) {
            return ToolResponseBuilder.failure(
                error = "Web search is disabled. Enable it in Settings → Web Search.",
            )
        }

        val safeMax = maxResults.coerceIn(1, 10)
        val backend = WebSearchDispatcher.activeBackend(config)

        log.debug("searchWeb: query='{}', backend='{}', maxResults={}", query, backend.name, safeMax)

        val results = backend.search(query, safeMax)

        if (results.isEmpty()) {
            return ToolResponseBuilder.failure(
                error = "No results found for: \"$query\". Try a different or broader query.",
                metadata = mapOf("query" to query, "backend" to backend.name),
            )
        }

        val summary = results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   URL: ${r.url}\n   ${r.snippet}"
        }.joinToString("\n\n")

        ToolResponseBuilder.successWithData(
            output = summary,
            data = mapOf(
                "query" to query,
                "backend" to backend.name,
                "resultCount" to results.size,
                "results" to results.map {
                    mapOf("title" to it.title, "url" to it.url, "snippet" to it.snippet)
                },
            ),
        )
    } catch (e: Exception) {
        log.error("searchWeb failed for query '{}': {}", query, e.message)
        ToolResponseBuilder.failure(
            error = "Web search failed: ${e.message}",
            metadata = mapOf(
                "query" to query,
                "exception" to (e::class.simpleName ?: "Exception"),
            ),
        )
    }

    /**
     * Fetch and extract the readable text content of a web page.
     *
     * @param url The URL of the web page to read
     * @param maxChars Maximum number of characters to return (default 8000)
     * @return JSON with the page title, url, and extracted text content
     */
    @Tool(
        """Fetch and read the content of a web page from a URL.

Use this tool when you need to:
- Read the full content of a specific URL found in search results
- Extract information from a known web page or article
- Summarise or analyse content from a specific webpage
- Get more detail from a search result snippet

OUTPUT FORMAT:
Return the extracted page title and the most relevant portions of the text.
Keep your summary focused on what the user actually asked about.
        """,
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"readWebPage\" }",
    )
    fun readWebPage(
        @P("Full URL of the page to fetch, must start with http:// or https://") url: String,
    ): String = try {
        val maxChars = 100_000
        require(url.isNotBlank()) { "URL cannot be empty" }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }

        val doc: Document = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(PAGE_TIMEOUT_MS)
            .get()

        val title = doc.title().ifBlank { url }

        // Remove noise elements
        doc.select("script, style, nav, footer, header, aside, .ads, .advertisement, #cookie-banner").remove()

        // Extract main content — prefer article/main, fall back to body
        val contentEl = doc.selectFirst("article, main, [role=main], .content, .post-content, .entry-content")
            ?: doc.body()

        val rawText = contentEl.wholeText()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        val truncated = if (rawText.length > maxChars) {
            rawText.take(maxChars) + "\n\n[Content truncated at $maxChars characters. Use a smaller maxChars or ask about specific sections.]"
        } else {
            rawText
        }

        ToolResponseBuilder.successWithData(
            output = "Read page: $title ($url)",
            data = mapOf(
                "url" to url,
                "title" to title,
                "content" to truncated,
                "characterCount" to rawText.length,
                "truncated" to (rawText.length > maxChars),
            ),
        )
    } catch (e: HttpStatusException) {
        ToolResponseBuilder.failure(
            error = "Failed to fetch page (HTTP ${e.statusCode}): ${e.message}",
            metadata = mapOf("url" to url, "statusCode" to e.statusCode),
        )
    } catch (e: Exception) {
        ToolResponseBuilder.failure(
            error = "Failed to read web page: ${e.message}",
            metadata = mapOf(
                "url" to url,
                "exception" to (e::class.simpleName ?: "Exception"),
            ),
        )
    }
}
