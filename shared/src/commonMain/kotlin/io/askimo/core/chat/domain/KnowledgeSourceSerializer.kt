/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.logging.logger
import io.askimo.core.util.JsonUtils.json

/**
 * Utilities for serializing and deserializing knowledge sources to/from the indexed_paths JSON format.
 *
 * Supports two formats:
 * 1. Legacy format: Simple JSON array of file paths - `["/path1", "/path2"]`
 * 2. New format: Structured JSON with version and sources
 */
object KnowledgeSourceSerializer {
    private val log = logger<KnowledgeSourceSerializer>()

    /**
     * Serialize knowledge sources to JSON for database storage in indexed_paths column.
     *
     * Strategy:
     * - Always use structured format with version and sources
     */
    fun serialize(sources: List<KnowledgeSourceConfig>): String {
        // Always use new structured format
        return json.encodeToString(
            IndexedPathsData(
                version = 1,
                sources = sources,
            ),
        )
    }

    /**
     * Deserialize JSON from database indexed_paths column to knowledge sources.
     *
     * Auto-detects format:
     * - If starts with `[`: legacy array format
     * - If starts with `{`: new structured format
     * - Otherwise: treat as single file path string
     */
    fun deserialize(indexedPaths: String): List<KnowledgeSourceConfig> {
        if (indexedPaths.isBlank()) {
            return emptyList()
        }

        return try {
            val trimmed = indexedPaths.trim()

            when {
                trimmed.startsWith("[") -> deserializeLegacyFormat(indexedPaths)
                trimmed.startsWith("{") -> deserializeStructuredFormat(indexedPaths)
                else -> deserializeSinglePath(indexedPaths)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize indexed paths: ${e.message}", e)
            // Fallback: treat as single file path
            listOf(
                LocalFoldersKnowledgeSourceConfig(resourceIdentifier = indexedPaths),
            )
        }
    }

    /**
     * Deserialize legacy format: `["/path1", "/path2"]`
     * Creates one LocalFoldersKnowledgeSourceConfig per path
     */
    private fun deserializeLegacyFormat(jsonStr: String): List<KnowledgeSourceConfig> {
        val paths = json.decodeFromString<List<String>>(jsonStr)
        return paths.map { path ->
            LocalFoldersKnowledgeSourceConfig(resourceIdentifier = path)
        }
    }

    /**
     * Deserialize new structured format:
     * ```
     * {
     *   "version": 1,
     *   "sources": [...]
     * }
     * ```
     */
    private fun deserializeStructuredFormat(jsonStr: String): List<KnowledgeSourceConfig> {
        val data = json.decodeFromString<IndexedPathsData>(jsonStr)
        return data.sources
    }

    /**
     * Deserialize single path string (no JSON structure)
     */
    private fun deserializeSinglePath(path: String): List<KnowledgeSourceConfig> = listOf(
        LocalFoldersKnowledgeSourceConfig(resourceIdentifier = path),
    )
}
