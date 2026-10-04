/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.extraction

/**
 * Generic interface for extracting content from different resource types.
 * Each resource type (files, web pages, SEC filings, etc.) implements this interface.
 *
 * @param T The type of ResourceIdentifier this extractor handles
 */
interface ContentExtractor<T : ResourceIdentifier> {
    /**
     * Extract text content from the resource identified by the given identifier.
     *
     * @param resourceIdentifier The identifier of the resource to extract content from
     * @return The extracted text content, or null if extraction failed or resource is not supported
     */
    fun extractContent(resourceIdentifier: T): String?
}
