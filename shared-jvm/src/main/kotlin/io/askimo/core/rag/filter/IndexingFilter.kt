/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.config.ProjectType
import java.nio.file.Path

/**
 * A filter that determines whether a file/directory should be indexed.
 * Filters can be chained and composed for flexible indexing rules.
 */
interface IndexingFilter {
    /**
     * Unique identifier for this filter (e.g., "gitignore", "projecttype", "custom")
     */
    val name: String

    /**
     * Priority for filter execution (lower number = higher priority).
     * Filters are evaluated in priority order.
     */
    val priority: Int get() = 100

    /**
     * Check if a path should be excluded from indexing.
     *
     * @param path The file/directory path to check
     * @param isDirectory Whether the path is a directory
     * @param context Additional context (root path, project type, etc.)
     * @return true if the path should be excluded, false otherwise
     */
    fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean
}

/**
 * Context passed to filters for decision making.
 */
data class FilterContext(
    val rootPath: Path,
    val relativePath: String,
    val fileName: String,
    val extension: String,
    val projectTypes: List<ProjectType> = emptyList(),
    val metadata: Map<String, Any> = emptyMap(),
)
