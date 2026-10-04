/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

/**
 * A single web search result returned by any [SearchBackend].
 */
data class SearchResult(
    /** Page title. */
    val title: String,
    /** Canonical URL of the result. */
    val url: String,
    /** Short snippet / description extracted from the result. */
    val snippet: String,
)
