/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.providers.openaicompatible.OpenAiCompatibleModelFactory
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

@AskimoTestHome
class EmbeddingModelConfigurationTest {

    @Test
    fun `missing embedding model explains how to configure one`() {
        val exception = assertThrows<IllegalStateException> {
            OpenAiCompatibleModelFactory().createEmbeddingModel(
                OpenAiCompatibleSettings(
                    baseUrl = "http://localhost:11434/v1",
                    embeddingModel = "",
                ),
            )
        }

        assertEquals(
            "No embedding model is configured for OPENAI_COMPATIBLE. " +
                "Go to Settings > AI Provider and select an embedding model under the provider configuration card.",
            exception.message,
        )
    }
}
