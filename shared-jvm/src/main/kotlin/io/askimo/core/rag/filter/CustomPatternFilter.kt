/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import java.nio.file.Path

/**
 * User-defined custom filter based on regex patterns.
 * Allows users to add their own exclusion rules.
 */
class CustomPatternFilter(private val patterns: List<Regex>) : IndexingFilter {
    override val name = "custom"
    override val priority = 30

    override fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean {
        val fullPath = if (context.relativePath.isEmpty()) {
            context.fileName
        } else {
            "${context.relativePath}/${context.fileName}".replace("\\", "/")
        }

        return patterns.any { pattern ->
            pattern.matches(fullPath) || pattern.matches(context.fileName)
        }
    }
}
