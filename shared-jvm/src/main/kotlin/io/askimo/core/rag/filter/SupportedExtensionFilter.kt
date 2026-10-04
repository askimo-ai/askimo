/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.chat.util.FileContentExtractor
import java.nio.file.Path

/**
 * Filter that only accepts files with supported extensions.
 * Used for local files indexing where we want to index any supported file type
 * without project-based exclusions.
 */
class SupportedExtensionFilter : IndexingFilter {
    override val name = "supported-extension"
    override val priority = 1

    override fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean {
        if (isDirectory) return false

        // Accept file if extension is in the supported list
        val extension = context.extension.lowercase()
        return extension !in FileContentExtractor.ALL_SUPPORTED_EXTENSIONS
    }
}
