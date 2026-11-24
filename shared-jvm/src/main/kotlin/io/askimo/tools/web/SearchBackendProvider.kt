/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

/**
 * Resolves the [SearchBackend] that [WebSearchTools.searchWeb] should use.
 *
 * Exists as a pluggable seam so other consumers can swap backend resolution without
 * duplicating [WebSearchTools]'s tool registration / langchain4j wiring. The default
 * provider resolves via [WebSearchDispatcher] against the user's local BYOK
 * [io.askimo.core.config.WebSearchConfig]; consumers that need different backend
 * resolution (e.g. a centrally managed backend) can override
 * [WebSearchTools.backendProvider] at startup instead.
 */
fun interface SearchBackendProvider {
    fun get(): SearchBackend
}
