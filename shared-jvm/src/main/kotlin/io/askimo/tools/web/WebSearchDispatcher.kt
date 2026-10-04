/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import io.askimo.core.config.WebSearchBackend
import io.askimo.core.config.WebSearchConfig
import io.askimo.core.logging.logger

/**
 * Selects the active [SearchBackend] based on the current [WebSearchConfig].
 *
 * Resolution order:
 * 1. User-configured backend (if it has a valid API key / endpoint)
 * 2. DuckDuckGo fallback (zero-config, always available)
 */
object WebSearchDispatcher {

    private val log = logger<WebSearchDispatcher>()

    /**
     * Returns the active [SearchBackend] for the given config.
     * Falls back to [DuckDuckGoBackend] if the configured backend has no valid credentials.
     */
    fun activeBackend(config: WebSearchConfig): SearchBackend {
        val backend = when (config.backend) {
            WebSearchBackend.BRAVE -> {
                if (config.braveApiKey.isNotBlank()) {
                    log.debug("Using Brave Search backend")
                    BraveBackend(config.braveApiKey)
                } else {
                    log.debug("Brave backend selected but no API key — falling back to DuckDuckGo")
                    null
                }
            }

            WebSearchBackend.TAVILY -> {
                if (config.tavilyApiKey.isNotBlank()) {
                    log.debug("Using Tavily backend")
                    TavilyBackend(config.tavilyApiKey)
                } else {
                    log.debug("Tavily backend selected but no API key — falling back to DuckDuckGo")
                    null
                }
            }

            WebSearchBackend.SERPLY -> {
                if (config.serplyApiKey.isNotBlank()) {
                    log.debug("Using Serply backend")
                    SerplyBackend(config.serplyApiKey)
                } else {
                    log.debug("Serply backend selected but no API key — falling back to DuckDuckGo")
                    null
                }
            }

            WebSearchBackend.SEARXNG -> {
                log.debug("Using SearxNG backend: {}", config.searxngEndpoint)
                SearxNGBackend(config.searxngEndpoint)
            }

            WebSearchBackend.DUCKDUCKGO -> null
        }

        return backend ?: DuckDuckGoBackend().also {
            if (config.backend != WebSearchBackend.DUCKDUCKGO) {
                log.debug("Resolved backend: DuckDuckGo (fallback)")
            } else {
                log.debug("Using DuckDuckGo backend")
            }
        }
    }

    /**
     * Tests the active backend with a simple query.
     * Returns [Result.success] with a short status string, or [Result.failure] on error.
     */
    fun testBackend(config: WebSearchConfig): Result<String> = runCatching {
        val backend = activeBackend(config)
        val results = backend.search("Askimo AI", maxResults = 1)
        if (results.isEmpty()) {
            error("No results returned from ${backend.name}")
        }
        "OK · ${backend.name} · \"${results.first().title}\""
    }
}
