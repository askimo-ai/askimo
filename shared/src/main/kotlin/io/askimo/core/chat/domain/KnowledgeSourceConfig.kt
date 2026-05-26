/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base sealed class for all knowledge source configurations.
 * Uses polymorphic serialization to handle different source types.
 *
 * Each subclass represents a specific knowledge source type with its own
 * resource identifier format and configuration options.
 */
@Serializable
sealed class KnowledgeSourceConfig {
    abstract val resourceIdentifier: String
    abstract val config: Map<String, String>
}

/**
 * Configuration for local folder knowledge sources.
 * Folders are watched for changes.
 *
 * @property resourceIdentifier Folder path (e.g., "/path/to/folder")
 * @property config Configuration options:
 *   - watchForChanges: "true" or "false"
 *   - fileExtensions: ".kt,.java,.md" (comma-separated)
 *   - excludePatterns: "node_modules,build,.git" (comma-separated)
 * @property createdAt Timestamp when this source was added
 */
@Serializable
@SerialName("local_folders")
data class LocalFoldersKnowledgeSourceConfig(
    override val resourceIdentifier: String,
    override val config: Map<String, String> = emptyMap(),
) : KnowledgeSourceConfig()

/**
 * Configuration for individual files as knowledge sources.
 * Unlike folders, files are NOT watched for changes.
 *
 * @property resourceIdentifier File path (e.g., "/path/to/document.pdf")
 * @property config Configuration options (currently unused)
 * @property createdAt Timestamp when this source was added
 */
@Serializable
@SerialName("local_files")
data class LocalFilesKnowledgeSourceConfig(
    override val resourceIdentifier: String,
    override val config: Map<String, String> = emptyMap(),
) : KnowledgeSourceConfig()

/**
 * Configuration for URL-based knowledge sources.
 * URLs are fetched and indexed for retrieval.
 *
 * @property resourceIdentifier URL (e.g., "https://example.com/docs")
 * @property config Configuration options:
 *   - crawlDepth: "1" (how many levels deep to crawl, default: 0 = single page only)
 *   - maxPages: "100" (maximum number of pages to index)
 *   - respectRobotsTxt: "true" or "false" (default: true)
 * @property createdAt Timestamp when this source was added
 */
@Serializable
@SerialName("urls")
data class UrlKnowledgeSourceConfig(
    override val resourceIdentifier: String,
    override val config: Map<String, String> = emptyMap(),
) : KnowledgeSourceConfig()

/**
 * Wrapper for the new indexed_paths JSON format.
 * Contains version for future migrations and list of knowledge sources.
 */
@Serializable
data class IndexedPathsData(
    val version: Int = 1,
    val sources: List<KnowledgeSourceConfig> = emptyList(),
)
