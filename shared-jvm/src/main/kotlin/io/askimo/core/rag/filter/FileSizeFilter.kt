/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.config.AppConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * Filter based on file size.
 * Excludes files larger than configured limit.
 */
class FileSizeFilter(private val maxBytes: Long = AppConfig.indexing.maxFileBytes) : IndexingFilter {
    override val name = "filesize"
    override val priority = 8

    override fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean {
        if (isDirectory) return false

        return try {
            Files.size(path) > maxBytes
        } catch (_: Exception) {
            false
        }
    }
}
