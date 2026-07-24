/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.providers.ollama.OllamaModelFactory
import io.askimo.core.providers.ollama.OllamaSettings
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

@AskimoTestHome
class EmbeddingModelConfigurationTest {

    @Test
    fun `missing embedding model explains how to configure one`() {
        val exception = assertThrows<IllegalStateException> {
            OllamaModelFactory().createEmbeddingModel(
                OllamaSettings(
                    baseUrl = "http://localhost:11434/v1",
                    embeddingModel = "",
                ),
            )
        }

        assertEquals(
            "No embedding model is configured for OLLAMA. " +
                "Go to Settings > AI Provider and select an embedding model under the provider configuration card.",
            exception.message,
        )
    }
}
