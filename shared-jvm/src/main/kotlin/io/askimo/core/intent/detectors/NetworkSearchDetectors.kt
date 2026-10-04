/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent.detectors

import io.askimo.core.intent.BaseIntentDetector
import io.askimo.core.intent.ToolCategory

/**
 * Detector for network/HTTP operations.
 * Uses word boundaries for short keywords to prevent false positives.
 */
class NetworkDetector :
    BaseIntentDetector(
        category = ToolCategory.NETWORK,
        directKeywords = listOf(
            "http", "https", "api", "call api", "fetch from",
            "get request", "post request", "webhook", "rest api",
            "download", "upload", "curl",
        ),
        contextualPatterns = listOf(
            "\\bcall\\b.*\\bapi\\b", "\\bcall\\b.*\\bendpoint\\b", "\\bcall\\b.*\\bservice\\b",
            "\\brequest\\b.*\\bapi\\b", "\\brequest\\b.*\\bendpoint\\b", "\\brequest\\b.*\\burl\\b",
            "\\bfetch\\b.*\\bapi\\b", "\\bfetch\\b.*\\burl\\b", "\\bfetch\\b.*\\bendpoint\\b",
            "\\bget\\b.*\\bapi\\b", "\\bget\\b.*\\burl\\b", "\\bget\\b.*\\bendpoint\\b",
            "\\bpost\\b.*\\bapi\\b", "\\bpost\\b.*\\burl\\b", "\\bpost\\b.*\\bendpoint\\b",
            "\\bsend\\b.*\\brequest\\b", "\\bsend\\b.*\\bapi\\b", "\\bsend\\b.*\\burl\\b",
            "\\bmake\\b.*\\brequest\\b", "\\bmake\\b.*\\bapi\\b call",
            "\\binvoke\\b.*\\bapi\\b", "\\binvoke\\b.*\\bendpoint\\b", "\\binvoke\\b.*\\bservice\\b",
            "\\bdownload\\b.*\\bfrom\\b", "\\bdownload\\b.*\\burl\\b", "\\bdownload\\b.*\\bfile\\b",
            "\\bupload\\b.*\\bto\\b", "\\bupload\\b.*\\burl\\b", "\\bupload\\b.*\\bfile\\b",
            "\\bfrom\\b.*\\bapi\\b", "\\bfrom\\b.*\\burl\\b", "\\bfrom\\b.*\\bendpoint\\b",
            "\\bto\\b.*\\bapi\\b", "\\bto\\b.*\\burl\\b", "\\bto\\b.*\\bendpoint\\b",
            "\\brest\\b.*\\bapi\\b", "\\bgraphql\\b", "\\bgrpc\\b", "\\bwebsocket\\b",
            "https://", "http://", "\\bapi\\b.*\\bendpoint\\b", "\\burl\\b.*\\bendpoint\\b",
        ),
    ) {
    override fun detectDirectKeywords(message: String): Boolean = directKeywords.any { keyword ->
        when {
            // Use word boundaries for short keywords to avoid false positives
            keyword.length <= 4 -> message.contains(Regex("\\b$keyword\\b"))

            else -> message.contains(keyword)
        }
    }
}

/**
 * Detector for search/lookup operations.
 */
class SearchDetector :
    BaseIntentDetector(
        category = ToolCategory.SEARCH,
        directKeywords = listOf(
            "search", "find", "lookup", "query for", "search for",
            "find files", "search github", "search code", "grep",
            "locate", "discover", "list",
        ),
        contextualPatterns = listOf(
            "\\bsearch\\b.*\\bfor\\b", "\\bsearch\\b.*\\bin\\b", "\\bsearch\\b.*\\bfiles\\b",
            "\\bsearch\\b.*\\bcode\\b", "\\bsearch\\b.*\\bgithub\\b", "\\bsearch\\b.*\\brepo\\b",
            "\\bfind\\b.*\\bfiles\\b", "\\bfind\\b.*\\bcode\\b", "\\bfind\\b.*\\bin\\b",
            "\\bfind\\b.*\\bgithub\\b", "\\bfind\\b.*\\brepo\\b", "\\bfind\\b.*\\bproject\\b",
            "\\blookup\\b.*\\bin\\b", "\\blookup\\b.*\\bcode\\b", "\\blookup\\b.*\\bfiles\\b",
            "\\blocate\\b.*\\bfile\\b", "\\blocate\\b.*\\bcode\\b", "\\blocate\\b.*\\bin\\b",
            "\\bdiscover\\b.*\\bfile\\b", "\\bdiscover\\b.*\\bcode\\b",
            "\\bgrep\\b.*\\bfor\\b", "\\bgrep\\b.*\\bin\\b",
            "look.*\\bfor\\b.*\\bfile\\b", "look.*\\bfor\\b.*\\bcode\\b",
            "\\bquery\\b.*\\bgithub\\b", "\\bquery\\b.*\\brepo\\b",
        ),
    )
