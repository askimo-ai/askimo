/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.logging.logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Zero-config web search backend using DuckDuckGo's HTML endpoint.
 *
 * - No API key required.
 * - Uses the `/html/` endpoint which returns a plain HTML page with results.
 * - Real destination URLs are encoded in DDG's redirect parameter `uddg=`.
 * - Ad / promoted results (class `result--ad`) are filtered out automatically.
 */
class DuckDuckGoBackend : SearchBackend {

    override val name: String = "DuckDuckGo"

    private val log = logger<DuckDuckGoBackend>()

    companion object {
        private const val SEARCH_URL = "https://html.duckduckgo.com/html/"
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "Mozilla/5.0 (compatible; Askimo/1.0; +https://$DOMAIN)"
    }

    override fun search(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        val url = "$SEARCH_URL?q=$encoded"

        log.debug("DuckDuckGo search: query='{}', url='{}'", query, url)

        val doc: Document = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            // DDG requires these headers to return proper HTML results
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml")
            // POST with the query for more reliable results
            .data("q", query.trim())
            .post()

        val results = mutableListOf<SearchResult>()

        // Each organic result is wrapped in a <div class="result ...">
        // Ad results have class "result--ad" — skip them
        val resultDivs = doc.select("div.result:not(.result--ad)")

        for (div in resultDivs) {
            if (results.size >= maxResults) break

            // Title and raw (redirected) href
            val titleEl = div.selectFirst("a.result__a") ?: continue
            val rawHref = titleEl.attr("href")
            val title = titleEl.text().trim()
            if (title.isBlank()) continue

            // Decode the actual destination URL from DDG's redirect wrapper
            val destinationUrl = extractRealUrl(rawHref) ?: continue

            // Skip DDG-internal or empty links
            if (destinationUrl.contains("duckduckgo.com") || destinationUrl.isBlank()) continue

            // Snippet text
            val snippet = div.selectFirst("a.result__snippet")?.text()?.trim()
                ?: div.selectFirst(".result__snippet")?.text()?.trim()
                ?: ""

            results += SearchResult(
                title = title,
                url = destinationUrl,
                snippet = snippet,
            )
        }

        log.debug("DuckDuckGo returned {} results for '{}'", results.size, query)
        return results
    }

    /**
     * DDG wraps destination URLs as: `//duckduckgo.com/l/?uddg=<encoded_url>&...`
     * This extracts and decodes the real URL from the `uddg` parameter.
     * Falls back to returning the raw href if it already looks like a real URL.
     */
    private fun extractRealUrl(href: String): String? {
        if (href.isBlank()) return null

        // Already a proper URL (unlikely from DDG HTML but defensive)
        if (href.startsWith("http://") || href.startsWith("https://")) return href

        // DDG redirect pattern: /l/?uddg=<encoded>&rut=...
        val normalized = if (href.startsWith("//")) "https:$href" else href
        return try {
            val uri = java.net.URI(normalized)
            val query = uri.rawQuery ?: return null
            val uddg = query.split("&")
                .firstOrNull { it.startsWith("uddg=") }
                ?.removePrefix("uddg=")
                ?: return null
            URLDecoder.decode(uddg, StandardCharsets.UTF_8)
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (e: Exception) {
            log.trace("Could not parse DDG redirect href '{}': {}", href, e.message)
            null
        }
    }
}
