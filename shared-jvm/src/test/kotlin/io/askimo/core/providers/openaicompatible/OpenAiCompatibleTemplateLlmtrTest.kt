/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import io.askimo.core.providers.HttpVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleTemplateLlmtrTest {

    @Test
    fun `LLMTR template uses the prepaid OpenAI-compatible gateway`() {
        val template = OpenAiCompatibleTemplate.LLMTR
        assertEquals("LLMTR", template.displayName)
        assertEquals("LT", template.initials)
        assertEquals("https://llmtr.com/v1", template.baseUrl)
        assertTrue(template.apiKeyRequired)
        assertEquals("https://llmtr.com/dashboard/keys", template.apiKeyUrl)
        assertEquals("provider.template.llmtr.tagline", template.taglineKey)
        assertEquals("provider.template.llmtr.help", template.helpTextKey)
        assertEquals(OpenAiApiMode.CHAT_COMPLETIONS, template.apiMode)
        assertEquals(HttpVersion.HTTP_2, template.httpVersion)
        assertNull(template.modelFetcher)
    }
}
