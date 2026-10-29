/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.rag.content.Content
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.KnowledgeSourcePriority
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import java.nio.file.Paths

/**
 * Maps retrieved [Content] back to the knowledge source it was indexed from and returns
 * that source's [KnowledgeSourceConfig.priority].
 *
 * Chunks carry no source id, but every local chunk has an absolute `file_path` and every
 * URL chunk has its `url`, so the owning source can be found at query time without
 * re-indexing: the longest matching folder prefix (on a path boundary), an exact file
 * path, or an exact URL. Live web results and anything unmatched get
 * [KnowledgeSourcePriority.NORMAL].
 *
 * @param sources Supplies the container's current sources. It's read once per
 *   [snapshot] rather than captured at construction, so a priority change applies to
 *   the next query even though the session's retriever is cached.
 * @param ignoreCase Whether paths compare case-insensitively (true on Windows).
 */
class KnowledgeSourcePriorityResolver(
    private val ignoreCase: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    private val sources: () -> List<KnowledgeSourceConfig>,
) {

    /** Returns a weight lookup for one query, built from the sources as they are now. */
    fun snapshot(): (Content) -> Double {
        val current = sources()
        if (current.all { it.priority == KnowledgeSourcePriority.NORMAL.weight }) return { NORMAL }

        val urlWeights = current.filterIsInstance<UrlKnowledgeSourceConfig>()
            .associate { it.resourceIdentifier.trim() to it.priority }
        // Longest first, so a nested folder wins over its parent.
        val pathWeights = current.filterNot { it is UrlKnowledgeSourceConfig }
            .map { normalizePath(it.resourceIdentifier) to it.priority }
            .sortedByDescending { it.first.length }

        return { content ->
            val metadata = content.textSegment().metadata()
            val url = metadata.getString("url")
            val filePath = metadata.getString("file_path")
            when {
                url != null && url.trim() in urlWeights -> urlWeights.getValue(url.trim())
                filePath != null -> weightForPath(normalize(filePath), pathWeights)
                else -> NORMAL
            }
        }
    }

    private fun weightForPath(path: String, pathWeights: List<Pair<String, Double>>): Double = pathWeights.firstOrNull { (root, _) ->
        path.equals(root, ignoreCase) || path.startsWith("$root/", ignoreCase)
    }?.second ?: NORMAL

    /** Mirrors how indexing derives `file_path`: absolute, with forward slashes. */
    private fun normalizePath(resourceIdentifier: String): String = normalize(
        runCatching { Paths.get(resourceIdentifier).toAbsolutePath().toString() }.getOrDefault(resourceIdentifier),
    )

    private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')

    private companion object {
        val NORMAL = KnowledgeSourcePriority.NORMAL.weight
    }
}
