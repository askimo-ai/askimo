/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.extraction

import io.askimo.core.logging.logger
import io.askimo.core.chat.util.FileContentExtractor as UtilFileContentExtractor

/**
 * Content extractor for local files.
 * Supports text files, PDF, DOCX, and other formats via Apache Tika.
 */
class LocalFileContentExtractor : ContentExtractor<FileResourceIdentifier> {
    private val log = logger<LocalFileContentExtractor>()

    /**
     * Extract text content from a local file.
     *
     * @param resourceIdentifier The file resource identifier containing the file path
     * @return The extracted text content, or null if extraction failed or file type is not supported
     */
    override fun extractContent(resourceIdentifier: FileResourceIdentifier): String? {
        val filePath = resourceIdentifier.filePath

        return try {
            val file = filePath.toFile()

            if (!UtilFileContentExtractor.isSupported(file)) {
                log.debug(
                    "Unsupported file type: {} - {}",
                    filePath.fileName,
                    UtilFileContentExtractor.getUnsupportedMessage(file),
                )
                return null
            }

            UtilFileContentExtractor.extractContent(file)
        } catch (e: Exception) {
            log.warn("Failed to extract content from file {}", filePath.fileName, e)
            null
        }
    }

    /**
     * Check if a file is a text-based file where line numbers are meaningful.
     * Returns false for binary files like PDF, DOCX, etc.
     */
    fun isTextFile(resourceIdentifier: FileResourceIdentifier): Boolean {
        val file = resourceIdentifier.filePath.toFile()
        return UtilFileContentExtractor.isTextFile(file)
    }
}
