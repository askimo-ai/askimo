/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs

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

    /**
     * How strongly retrieved content from this source is favoured (see
     * `HybridContentRetriever`). Defaults to [KnowledgeSourcePriority.NORMAL], which
     * leaves ranking unchanged, so JSON stored before this field existed decodes as-is.
     */
    abstract val priority: KnowledgeSourcePriority
}

/**
 * Stores a [KnowledgeSourcePriority] as its [KnowledgeSourcePriority.weight], so the saved
 * JSON stays a plain number while code passes the enum around.
 */
object KnowledgeSourcePrioritySerializer : KSerializer<KnowledgeSourcePriority> {
    override val descriptor = PrimitiveSerialDescriptor("KnowledgeSourcePriority", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: KnowledgeSourcePriority) = encoder.encodeDouble(value.weight)

    override fun deserialize(decoder: Decoder) = KnowledgeSourcePriority.fromWeight(decoder.decodeDouble())
}

/**
 * The priority levels offered in the UI and the ranking weight each maps to. Saved as the
 * plain [weight], so the weights can be tuned without migrating saved sources.
 */
@Serializable(with = KnowledgeSourcePrioritySerializer::class)
enum class KnowledgeSourcePriority(val weight: Double) {
    LOW(0.5),
    NORMAL(1.0),
    HIGH(2.0),
    ALWAYS_PRIORITIZE(100.0),
    ;

    companion object {
        /** The level whose weight is closest to [weight], used when reading a saved value. */
        fun fromWeight(weight: Double): KnowledgeSourcePriority = entries.minBy { abs(it.weight - weight) }
    }
}

/** Returns a copy of this source with [priority] replaced, keeping its concrete type. */
fun KnowledgeSourceConfig.withPriority(priority: KnowledgeSourcePriority): KnowledgeSourceConfig = when (this) {
    is LocalFoldersKnowledgeSourceConfig -> copy(priority = priority)
    is LocalFilesKnowledgeSourceConfig -> copy(priority = priority)
    is UrlKnowledgeSourceConfig -> copy(priority = priority)
}

/**
 * Configuration for local folder knowledge sources.
 * Folders are watched for changes, unless [watchForChanges] is disabled.
 *
 * @property resourceIdentifier Folder path (e.g., "/path/to/folder")
 * @property watchForChanges Whether Askimo should watch this folder for file changes
 *   and re-index automatically. Disable for very large trees to avoid exhausting the
 *   OS file-watch limit (e.g. inotify on Linux) — use manual rescan instead.
 * @property config Configuration options:
 *   - fileExtensions: ".kt,.java,.md" (comma-separated)
 *   - excludePatterns: "node_modules,build,.git" (comma-separated)
 * @property createdAt Timestamp when this source was added
 */
@Serializable
@SerialName("local_folders")
data class LocalFoldersKnowledgeSourceConfig(
    override val resourceIdentifier: String,
    val watchForChanges: Boolean = true,
    override val config: Map<String, String> = emptyMap(),
    override val priority: KnowledgeSourcePriority = KnowledgeSourcePriority.NORMAL,
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
    override val priority: KnowledgeSourcePriority = KnowledgeSourcePriority.NORMAL,
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
    override val priority: KnowledgeSourcePriority = KnowledgeSourcePriority.NORMAL,
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
