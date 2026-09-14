/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

/**
 * Common interface for all web search backends.
 *
 * Implementations:
 * - [DuckDuckGoBackend]  — scraping-based, zero-config, no API key required
 * - [BraveBackend]       — Brave Search API, requires `BRAVE_SEARCH_API_KEY`
 * - [TavilyBackend]      — Tavily API, requires `TAVILY_API_KEY`
 * - [SerplyBackend]      — Serply API (Google organic results), requires `SERPLY_API_KEY`
 * - [SearxNGBackend]     — self-hosted SearxNG instance, configurable endpoint
 */
interface SearchBackend {
    /** Human-readable name of this backend, used in tool output and logs. */
    val name: String

    /**
     * Perform a web search and return up to [maxResults] results.
     *
     * @param query     The search query string.
     * @param maxResults Maximum number of results to return (callers coerce to 1–10).
     * @return List of [SearchResult]s, may be empty if nothing is found.
     * @throws Exception on network or parse failure — callers should handle.
     */
    fun search(query: String, maxResults: Int = 5): List<SearchResult>
}
