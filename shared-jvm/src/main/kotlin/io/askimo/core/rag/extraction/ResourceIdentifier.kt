/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.extraction

/**
 * Base interface for resource identifiers.
 * Each resource type (file, web page, SEC filing, etc.) should implement this.
 */
interface ResourceIdentifier {
    /**
     * Get a string representation of this resource identifier.
     * Examples: file path, URL, document ID, etc.
     */
    fun asString(): String
}
