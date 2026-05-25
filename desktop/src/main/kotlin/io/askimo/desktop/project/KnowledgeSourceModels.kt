/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.graphics.vector.ImageVector
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFilesKnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import java.io.File
import java.net.URI
import java.util.UUID

/**
 * UI representation of a knowledge source item
 */
sealed class KnowledgeSourceItem {
    abstract val id: String
    abstract val displayName: String
    abstract val isValid: Boolean

    /**
     * Get the type information for this knowledge source
     */
    abstract val typeInfo: TypeInfo

    data class Folder(
        override val id: String,
        val path: String,
        override val isValid: Boolean,
    ) : KnowledgeSourceItem() {
        override val displayName = path
        override val typeInfo = TypeInfo.FOLDER
    }

    data class File(
        override val id: String,
        val path: String,
        override val isValid: Boolean,
    ) : KnowledgeSourceItem() {
        override val displayName = path
        override val typeInfo = TypeInfo.FILE
    }

    data class Url(
        override val id: String,
        val url: String,
        override val isValid: Boolean,
    ) : KnowledgeSourceItem() {
        override val displayName = url
        override val typeInfo = TypeInfo.URL
    }

    /**
     * Type information for knowledge sources (icon and label key)
     */
    enum class TypeInfo(
        val icon: ImageVector,
        val typeLabelKey: String,
    ) {
        FOLDER(Icons.Default.Folder, "knowledge.source.type.folder"),
        FILE(Icons.AutoMirrored.Filled.InsertDriveFile, "knowledge.source.type.file"),
        URL(Icons.Default.Language, "knowledge.source.type.url"),
    }

    companion object {
        /**
         * All available knowledge source types for UI selection
         */
        val availableTypes: List<TypeInfo> = TypeInfo.entries
    }
}

/**
 * Parse KnowledgeSourceConfig into UI items
 */
fun parseKnowledgeSourceConfigs(configs: List<KnowledgeSourceConfig>): List<KnowledgeSourceItem> = configs.map { config ->
    when (config) {
        is LocalFoldersKnowledgeSourceConfig -> {
            KnowledgeSourceItem.Folder(
                id = UUID.randomUUID().toString(),
                path = config.resourceIdentifier,
                isValid = validateFolder(config.resourceIdentifier),
            )
        }

        is LocalFilesKnowledgeSourceConfig -> {
            KnowledgeSourceItem.File(
                id = UUID.randomUUID().toString(),
                path = config.resourceIdentifier,
                isValid = validateFile(config.resourceIdentifier),
            )
        }

        is UrlKnowledgeSourceConfig -> {
            KnowledgeSourceItem.Url(
                id = UUID.randomUUID().toString(),
                url = config.resourceIdentifier,
                isValid = validateUrl(config.resourceIdentifier),
            )
        }
    }
}

/**
 * Build KnowledgeSourceConfig list from UI items
 */
fun buildKnowledgeSourceConfigs(sources: List<KnowledgeSourceItem>): List<KnowledgeSourceConfig> = sources.map { source ->
    when (source) {
        is KnowledgeSourceItem.Folder -> {
            LocalFoldersKnowledgeSourceConfig(resourceIdentifier = source.path)
        }

        is KnowledgeSourceItem.File -> {
            LocalFilesKnowledgeSourceConfig(resourceIdentifier = source.path)
        }

        is KnowledgeSourceItem.Url -> {
            UrlKnowledgeSourceConfig(resourceIdentifier = source.url)
        }
    }
}

/**
 * Validate if a folder path is valid
 */
fun validateFolder(path: String): Boolean {
    val file = File(path)
    return file.exists() && file.isDirectory && file.canRead()
}

/**
 * Validate if a file path is valid
 */
fun validateFile(path: String): Boolean {
    val file = File(path)
    return file.exists() && file.isFile && file.canRead()
}

/**
 * Merge existing and new knowledge source configurations, deduplicating by resource identifier
 */
fun mergeKnowledgeSourceConfigs(
    existing: List<KnowledgeSourceConfig>,
    new: List<KnowledgeSourceConfig>,
): List<KnowledgeSourceConfig> = (existing + new)
    .groupBy { it::class }
    .flatMap { (_, configs) ->
        configs.distinctBy { it.resourceIdentifier }
    }
    .sortedBy { it.resourceIdentifier }

/**
 * Validate if a URL is valid
 */
fun validateUrl(url: String): Boolean = try {
    val uri = URI(url)
    uri.scheme in listOf("http", "https") && uri.host != null
} catch (_: Exception) {
    false
}
