/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ModelClassification
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests for the SQLDelight-backed [ModelClassificationRepository] /
 * [DatabaseManager]. No Exposed-based IT test existed to mirror, so this covers every
 * public method directly.
 */
class ModelClassificationRepositoryIT {

    @AfterEach
    fun tearDown() {
        repository.listAll().forEach { classification ->
            repository.delete(classification.provider, classification.modelName)
        }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: ModelClassificationRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)

            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            repository = databaseManager.getModelClassificationRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    @Test
    fun `should save and retrieve a classification`() {
        val classification = ModelClassification(
            id = "",
            provider = "ollama",
            modelName = "llama2",
            supportsImage = true,
            supportsTools = true,
        )

        val saved = repository.save(classification)

        assertTrue(saved.id.isNotBlank())

        val retrieved = repository.getByProviderAndModel("ollama", "llama2")
        assertNotNull(retrieved)
        assertEquals(saved.id, retrieved.id)
        assertTrue(retrieved.supportsImage)
        assertTrue(retrieved.supportsTools)
        assertTrue(retrieved.supportsText)
        assertTrue(retrieved.supportsSampling)
        assertTrue(retrieved.supportsStreaming)
        assertFalse(retrieved.supportsAudio)
        assertFalse(retrieved.supportsVideo)
    }

    @Test
    fun `should return null for non-existent classification`() {
        val result = repository.getByProviderAndModel("non-existent", "non-existent")

        assertNull(result)
    }

    @Test
    fun `should update existing classification on save with same id`() {
        val saved = repository.save(ModelClassification(id = "", provider = "openai", modelName = "gpt-4"))

        val updated = saved.copy(description = "Updated description", supportsImage = true)
        repository.save(updated)

        val retrieved = repository.getByProviderAndModel("openai", "gpt-4")
        assertNotNull(retrieved)
        assertEquals("Updated description", retrieved.description)
        assertTrue(retrieved.supportsImage)
    }

    @Test
    fun `should list classifications by provider ordered by model name`() {
        repository.save(ModelClassification(id = "", provider = "ollama", modelName = "zebra-model"))
        repository.save(ModelClassification(id = "", provider = "ollama", modelName = "alpha-model"))
        repository.save(ModelClassification(id = "", provider = "openai", modelName = "gpt-4"))

        val models = repository.listByProvider("ollama")

        assertEquals(2, models.size)
        assertEquals("alpha-model", models[0].modelName)
        assertEquals("zebra-model", models[1].modelName)
    }

    @Test
    fun `should list image-capable models ordered by provider then model name`() {
        repository.save(ModelClassification(id = "", provider = "openai", modelName = "gpt-4-vision", supportsImage = true))
        repository.save(ModelClassification(id = "", provider = "anthropic", modelName = "claude-vision", supportsImage = true))
        repository.save(ModelClassification(id = "", provider = "openai", modelName = "gpt-text-only", supportsImage = false))

        val models = repository.listImageModels()

        assertEquals(2, models.size)
        assertEquals("anthropic", models[0].provider)
        assertEquals("openai", models[1].provider)
    }

    @Test
    fun `should list tool-capable models ordered by provider then model name`() {
        repository.save(ModelClassification(id = "", provider = "openai", modelName = "gpt-4", supportsTools = true))
        repository.save(ModelClassification(id = "", provider = "openai", modelName = "gpt-3.5", supportsTools = false))

        val models = repository.listToolCapableModels()

        assertEquals(1, models.size)
        assertEquals("gpt-4", models[0].modelName)
    }

    @Test
    fun `should list all classifications ordered by provider then model name`() {
        repository.save(ModelClassification(id = "", provider = "zprovider", modelName = "model-a"))
        repository.save(ModelClassification(id = "", provider = "aprovider", modelName = "model-b"))

        val models = repository.listAll()

        assertEquals(2, models.size)
        assertEquals("aprovider", models[0].provider)
        assertEquals("zprovider", models[1].provider)
    }

    @Test
    fun `should update capabilities of existing classification`() {
        repository.save(ModelClassification(id = "", provider = "ollama", modelName = "llava", supportsImage = false))

        val result = repository.updateCapabilities("ollama", "llava") { it.copy(supportsImage = true) }

        assertTrue(result)
        val retrieved = repository.getByProviderAndModel("ollama", "llava")
        assertNotNull(retrieved)
        assertTrue(retrieved.supportsImage)
    }

    @Test
    fun `should return false updating capabilities of non-existent classification`() {
        val result = repository.updateCapabilities("non-existent", "non-existent") { it }

        assertFalse(result)
    }

    @Test
    fun `should delete existing classification`() {
        repository.save(ModelClassification(id = "", provider = "ollama", modelName = "to-delete"))

        val deleted = repository.delete("ollama", "to-delete")

        assertTrue(deleted)
        assertNull(repository.getByProviderAndModel("ollama", "to-delete"))
    }

    @Test
    fun `should return false when deleting non-existent classification`() {
        val deleted = repository.delete("non-existent", "non-existent")

        assertFalse(deleted)
    }

    @Test
    fun `should check supportsImage supportsTools supportsSampling flags`() {
        repository.save(
            ModelClassification(
                id = "",
                provider = "ollama",
                modelName = "flags-model",
                supportsImage = true,
                supportsTools = false,
                supportsSampling = false,
            ),
        )

        assertTrue(repository.supportsImage("ollama", "flags-model"))
        assertFalse(repository.supportsTools("ollama", "flags-model"))
        assertFalse(repository.supportsSampling("ollama", "flags-model"))
    }

    @Test
    fun `should default supportsImage and supportsTools to false and supportsSampling to true for unknown model`() {
        assertFalse(repository.supportsImage("unknown", "unknown"))
        assertFalse(repository.supportsTools("unknown", "unknown"))
        assertTrue(repository.supportsSampling("unknown", "unknown"))
    }
}
