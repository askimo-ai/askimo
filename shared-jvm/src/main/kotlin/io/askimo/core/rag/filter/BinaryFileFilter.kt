/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.config.AppConfig
import java.nio.file.Path

/**
 * Filter based on file extensions and binary types.
 * Excludes binary files, images, videos, hidden files, etc.
 */
class BinaryFileFilter : IndexingFilter {
    override val name = "binary"
    override val priority = 5

    override fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean {
        if (isDirectory) return false

        // Skip binary/media files
        if (context.extension in AppConfig.indexing.binaryExtensions) {
            return true
        }

        // Skip system and lock files
        if (context.fileName in AppConfig.indexing.excludeFileNames) {
            return true
        }

        // Skip hidden files (starting with .)
        if (context.fileName.startsWith(".")) {
            return true
        }

        return false
    }
}
