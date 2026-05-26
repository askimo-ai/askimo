/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import io.askimo.core.AppConstants.DOMAIN
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
