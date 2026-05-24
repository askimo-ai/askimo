/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Comprehensive domain model for storing AI model capabilities and classifications.
 * This allows tracking of various model features including modality support (text, image, audio, video),
 * tool/function calling support, and sampling parameter support.
 *
 * This is particularly useful for custom models from providers like Ollama where capabilities
 * cannot be automatically determined.
 */
data class ModelClassification(
    val id: String,
    val provider: String,
    val modelName: String,

    // Modality capabilities
    val supportsText: Boolean = true,
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsVideo: Boolean = false,

    // Feature capabilities
    val supportsTools: Boolean = false,
    val supportsSampling: Boolean = true,
    val supportsStreaming: Boolean = true,

    // Metadata
    val description: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

/**
 * Exposed table definition for model_classifications.
 * Stores comprehensive model capabilities including modality and feature support.
 */
object ModelClassificationsTable : Table("model_classifications") {
    val id = varchar("id", 36)
    val provider = varchar("provider", 100)
    val modelName = varchar("model_name", 255)

    // Modality support flags (0 = false, 1 = true)
    val supportsText = integer("supports_text").default(1)
    val supportsImage = integer("supports_image").default(0)
    val supportsAudio = integer("supports_audio").default(0)
    val supportsVideo = integer("supports_video").default(0)

    // Feature support flags (0 = false, 1 = true)
    val supportsTools = integer("supports_tools").default(0)
    val supportsSampling = integer("supports_sampling").default(1)
    val supportsStreaming = integer("supports_streaming").default(1)

    // Metadata
    val description = text("description").nullable()
    val createdAt = sqliteInstant("created_at")
    val updatedAt = sqliteInstant("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // Create unique index on provider + modelName to prevent duplicates
        uniqueIndex(provider, modelName)
    }
}
