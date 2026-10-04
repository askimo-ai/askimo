/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.config.AppConfig
import java.nio.file.Path

/**
 * Filter based on project type-specific exclude patterns.
 * Falls back to common excludes if no specific project type detected.
 */
class ProjectTypeFilter : IndexingFilter {
    override val name = "projecttype"
    override val priority = 50 // Medium priority - project conventions

    override fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean {
        val relativePath = context.relativePath.replace('\\', '/')

        // Check project-specific excludes
        for (projectType in context.projectTypes) {
            if (matchesPattern(relativePath, context.fileName, projectType.excludePaths, isDirectory)) {
                return true
            }
        }

        return matchesPattern(relativePath, context.fileName, AppConfig.indexing.commonExcludes, isDirectory)
    }

    private fun matchesPattern(
        relativePath: String,
        fileName: String,
        patterns: Set<String>,
        isDirectory: Boolean,
    ): Boolean {
        for (pattern in patterns) {
            // Directory patterns (ending with /)
            if (pattern.endsWith("/")) {
                if (!isDirectory) continue

                val dirPattern = pattern.removeSuffix("/")
                // Match directory at any level:
                // - /node_modules/ (in middle)
                // - node_modules/ (at start)
                // - node_modules (exact match)
                // - src/node_modules (ends with directory name)
                if (relativePath.contains("/$dirPattern/") ||
                    relativePath.startsWith("$dirPattern/") ||
                    relativePath == dirPattern ||
                    relativePath.endsWith("/$dirPattern") ||
                    (relativePath.contains("/") && relativePath.substringAfterLast("/") == dirPattern)
                ) {
                    return true
                }
            } else {
                if (pattern.contains("*")) {
                    val regex = pattern.replace("*", ".*").toRegex()
                    if (regex.matches(relativePath) || regex.matches(fileName)) {
                        return true
                    }
                } else {
                    if (relativePath.contains("/$pattern/") ||
                        relativePath.startsWith("$pattern/") ||
                        relativePath == pattern ||
                        fileName == pattern
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
