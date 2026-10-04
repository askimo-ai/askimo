/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ModelClassification
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Model_classifications
import io.askimo.core.util.TimeUtil
import java.util.UUID
import kotlin.time.Clock

/**
 * Maps a generated [Model_classifications] row to the shared [ModelClassification] domain object.
 */
private fun Model_classifications.toModelClassification(): ModelClassification = ModelClassification(
    id = id,
    provider = provider,
    modelName = model_name,
    supportsText = supports_text == 1L,
    supportsImage = supports_image == 1L,
    supportsAudio = supports_audio == 1L,
    supportsVideo = supports_video == 1L,
    supportsTools = supports_tools == 1L,
    supportsSampling = supports_sampling == 1L,
    supportsStreaming = supports_streaming == 1L,
    description = description,
    createdAt = TimeUtil.parseInstant(created_at),
    updatedAt = TimeUtil.parseInstant(updated_at),
)

/**
 * Repository for managing AI model classifications with comprehensive capability tracking.
 * This is particularly useful for classifying models from providers like Ollama
 * where capabilities cannot be automatically determined.
 */
class ModelClassificationRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    private val queries get() = db.modelClassificationsQueries

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
            updatedAt = Clock.System.now(),
        )

        db.transaction {
            val existing = queries.selectById(classificationWithId.id).executeAsOneOrNull()
            if (existing == null) {
                queries.insertClassification(
                    id = classificationWithId.id,
                    provider = classificationWithId.provider,
                    modelName = classificationWithId.modelName,
                    supportsText = if (classificationWithId.supportsText) 1L else 0L,
                    supportsImage = if (classificationWithId.supportsImage) 1L else 0L,
                    supportsAudio = if (classificationWithId.supportsAudio) 1L else 0L,
                    supportsVideo = if (classificationWithId.supportsVideo) 1L else 0L,
                    supportsTools = if (classificationWithId.supportsTools) 1L else 0L,
                    supportsSampling = if (classificationWithId.supportsSampling) 1L else 0L,
                    supportsStreaming = if (classificationWithId.supportsStreaming) 1L else 0L,
                    description = classificationWithId.description,
                    createdAt = classificationWithId.createdAt.toString(),
                    updatedAt = classificationWithId.updatedAt.toString(),
                )
            } else {
                queries.updateClassification(
                    provider = classificationWithId.provider,
                    modelName = classificationWithId.modelName,
                    supportsText = if (classificationWithId.supportsText) 1L else 0L,
                    supportsImage = if (classificationWithId.supportsImage) 1L else 0L,
                    supportsAudio = if (classificationWithId.supportsAudio) 1L else 0L,
                    supportsVideo = if (classificationWithId.supportsVideo) 1L else 0L,
                    supportsTools = if (classificationWithId.supportsTools) 1L else 0L,
                    supportsSampling = if (classificationWithId.supportsSampling) 1L else 0L,
                    supportsStreaming = if (classificationWithId.supportsStreaming) 1L else 0L,
                    description = classificationWithId.description,
                    updatedAt = classificationWithId.updatedAt.toString(),
                    id = classificationWithId.id,
                )
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
    fun getByProviderAndModel(provider: String, modelName: String): ModelClassification? = queries.selectByProviderAndModel(provider, modelName).executeAsOneOrNull()?.toModelClassification()

    /**
     * List all model classifications for a specific provider.
     *
     * @param provider The AI provider
     * @return List of classifications, ordered by model name
     */
    fun listByProvider(provider: String): List<ModelClassification> = queries.selectByProvider(provider).executeAsList().map { it.toModelClassification() }

    /**
     * List all model classifications that support images.
     *
     * @return List of classifications, ordered by provider and model name
     */
    fun listImageModels(): List<ModelClassification> = queries.selectImageModels().executeAsList().map { it.toModelClassification() }

    /**
     * List all model classifications that support tools/function calling.
     *
     * @return List of classifications, ordered by provider and model name
     */
    fun listToolCapableModels(): List<ModelClassification> = queries.selectToolCapableModels().executeAsList().map { it.toModelClassification() }

    /**
     * List all model classifications.
     *
     * @return List of all classifications, ordered by provider and model name
     */
    fun listAll(): List<ModelClassification> = queries.selectAllOrdered().executeAsList().map { it.toModelClassification() }

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
        val updated = updateFn(existing).copy(updatedAt = Clock.System.now())
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
    fun delete(provider: String, modelName: String): Boolean = queries.deleteByProviderAndModel(provider, modelName).value > 0

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
