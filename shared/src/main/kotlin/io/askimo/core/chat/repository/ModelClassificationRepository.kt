/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ModelClassification
import io.askimo.core.chat.domain.ModelClassificationsTable
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/**
 * Extension function to map an Exposed ResultRow to a ModelClassification object.
 */
private fun ResultRow.toModelClassification(): ModelClassification = ModelClassification(
    id = this[ModelClassificationsTable.id],
    provider = this[ModelClassificationsTable.provider],
    modelName = this[ModelClassificationsTable.modelName],
    supportsText = this[ModelClassificationsTable.supportsText] == 1,
    supportsImage = this[ModelClassificationsTable.supportsImage] == 1,
    supportsAudio = this[ModelClassificationsTable.supportsAudio] == 1,
    supportsVideo = this[ModelClassificationsTable.supportsVideo] == 1,
    supportsTools = this[ModelClassificationsTable.supportsTools] == 1,
    supportsSampling = this[ModelClassificationsTable.supportsSampling] == 1,
    supportsStreaming = this[ModelClassificationsTable.supportsStreaming] == 1,
    description = this[ModelClassificationsTable.description],
    createdAt = this[ModelClassificationsTable.createdAt],
    updatedAt = this[ModelClassificationsTable.updatedAt],
)

/**
 * Repository for managing AI model classifications with comprehensive capability tracking.
 * This is particularly useful for classifying models from providers like Ollama
 * where capabilities cannot be automatically determined.
 */
class ModelClassificationRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    /**
     * Save a new model classification or update existing one.
     * If a classification for the same provider+model already exists, it will be updated.
     *
     * @param classification The model classification to save
     * @return The saved classification with generated ID if it was a new entry
     */
    fun save(classification: ModelClassification): ModelClassification {
        val classificationWithId = classification.copy(
            id = classification.id.ifBlank { UUID.randomUUID().toString() },
            updatedAt = Instant.now(),
        )

        transaction(database) {
            ModelClassificationsTable.upsert {
                it[id] = classificationWithId.id
                it[provider] = classificationWithId.provider
                it[modelName] = classificationWithId.modelName
                it[supportsText] = if (classificationWithId.supportsText) 1 else 0
                it[supportsImage] = if (classificationWithId.supportsImage) 1 else 0
                it[supportsAudio] = if (classificationWithId.supportsAudio) 1 else 0
                it[supportsVideo] = if (classificationWithId.supportsVideo) 1 else 0
                it[supportsTools] = if (classificationWithId.supportsTools) 1 else 0
                it[supportsSampling] = if (classificationWithId.supportsSampling) 1 else 0
                it[supportsStreaming] = if (classificationWithId.supportsStreaming) 1 else 0
                it[description] = classificationWithId.description
                it[createdAt] = classificationWithId.createdAt
                it[updatedAt] = classificationWithId.updatedAt
            }
        }

        return classificationWithId
    }

    /**
     * Get a model classification by provider and model name.
     *
     * @param provider The AI provider (e.g., "openai", "ollama")
     * @param modelName The model name (e.g., "gpt-4", "llama2")
     * @return The classification if found, null otherwise
     */
    fun getByProviderAndModel(provider: String, modelName: String): ModelClassification? = transaction(database) {
        ModelClassificationsTable
            .selectAll()
            .where {
                (ModelClassificationsTable.provider eq provider) and
                    (ModelClassificationsTable.modelName eq modelName)
            }
            .singleOrNull()
            ?.toModelClassification()
    }

    /**
     * List all model classifications for a specific provider.
     *
     * @param provider The AI provider
     * @return List of classifications, ordered by model name
     */
    fun listByProvider(provider: String): List<ModelClassification> = transaction(database) {
        ModelClassificationsTable
            .selectAll()
            .where { ModelClassificationsTable.provider eq provider }
            .orderBy(ModelClassificationsTable.modelName to SortOrder.ASC)
            .map { it.toModelClassification() }
    }

    /**
     * List all model classifications that support images.
     *
     * @return List of classifications, ordered by provider and model name
     */
    fun listImageModels(): List<ModelClassification> = transaction(database) {
        ModelClassificationsTable
            .selectAll()
            .where { ModelClassificationsTable.supportsImage eq 1 }
            .orderBy(ModelClassificationsTable.provider to SortOrder.ASC)
            .orderBy(ModelClassificationsTable.modelName to SortOrder.ASC)
            .map { it.toModelClassification() }
    }

    /**
     * List all model classifications that support tools/function calling.
     *
     * @return List of classifications, ordered by provider and model name
     */
    fun listToolCapableModels(): List<ModelClassification> = transaction(database) {
        ModelClassificationsTable
            .selectAll()
            .where { ModelClassificationsTable.supportsTools eq 1 }
            .orderBy(ModelClassificationsTable.provider to SortOrder.ASC)
            .orderBy(ModelClassificationsTable.modelName to SortOrder.ASC)
            .map { it.toModelClassification() }
    }

    /**
     * List all model classifications.
     *
     * @return List of all classifications, ordered by provider and model name
     */
    fun listAll(): List<ModelClassification> = transaction(database) {
        ModelClassificationsTable
            .selectAll()
            .orderBy(ModelClassificationsTable.provider to SortOrder.ASC)
            .orderBy(ModelClassificationsTable.modelName to SortOrder.ASC)
            .map { it.toModelClassification() }
    }

    /**
     * Update capabilities for an existing classification.
     *
     * @param provider The AI provider
     * @param modelName The model name
     * @param updateFn Function to update the classification
     * @return true if updated, false if classification doesn't exist
     */
    fun updateCapabilities(
        provider: String,
        modelName: String,
        updateFn: (ModelClassification) -> ModelClassification,
    ): Boolean {
        val existing = getByProviderAndModel(provider, modelName) ?: return false
        val updated = updateFn(existing).copy(updatedAt = Instant.now())
        save(updated)
        return true
    }

    /**
     * Delete a model classification.
     *
     * @param provider The AI provider
     * @param modelName The model name
     * @return true if deleted, false if classification doesn't exist
     */
    fun delete(provider: String, modelName: String): Boolean = transaction(database) {
        ModelClassificationsTable.deleteWhere {
            (ModelClassificationsTable.provider eq provider) and
                (ModelClassificationsTable.modelName eq modelName)
        } > 0
    }

    /**
     * Check if a model supports images.
     *
     * @param provider The AI provider
     * @param modelName The model name
     * @return true if the model supports images
     */
    fun supportsImage(provider: String, modelName: String): Boolean = getByProviderAndModel(provider, modelName)?.supportsImage ?: false

    /**
     * Check if a model supports tools/function calling.
     *
     * @param provider The AI provider
     * @param modelName The model name
     * @return true if the model supports tools
     */
    fun supportsTools(provider: String, modelName: String): Boolean = getByProviderAndModel(provider, modelName)?.supportsTools ?: false

    /**
     * Check if a model supports sampling parameters.
     *
     * @param provider The AI provider
     * @param modelName The model name
     * @return true if the model supports sampling (default: true if not classified)
     */
    fun supportsSampling(provider: String, modelName: String): Boolean = getByProviderAndModel(provider, modelName)?.supportsSampling ?: true
}
