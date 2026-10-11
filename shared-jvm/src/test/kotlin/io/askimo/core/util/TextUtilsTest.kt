/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Confirms [redactErrorText] scrubs known-identifying patterns before any exception
 * text is allowed into an analytics payload — this is the only sanctioned way to include
 * free-text error content in analytics (see the privacy contract in [io.askimo.core.analytics.AnalyticsEventPayload]).
 */
class TextUtilsTest {

    @Test
    fun `redacts email addresses`() {
        val result = redactErrorText("Contact alice@example.com for support")
        assertFalse(result.contains("alice@example.com"))
        assertTrue(result.contains("[email]"))
    }

    @Test
    fun `redacts URLs including embedded account IDs and query params`() {
        // Regression test: this is the exact shape of the real-world leak we found —
        // "Function '...': Not found for account 'TaVuEPzt2Ve4oM2Rd4FxTTpi_IP8blJJn5271Ebcgwo'"
        val result = redactErrorText(
            "https://api.example.com/v1/functions/abc?account=TaVuEPzt2Ve4oM2Rd4FxTTpi_IP8blJJn5271Ebcgwo",
        )
        assertFalse(result.contains("TaVuEPzt2Ve4oM2Rd4FxTTpi_IP8blJJn5271Ebcgwo"))
        assertTrue(result.contains("[url]"))
    }

    @Test
    fun `redacts bearer tokens`() {
        val result = redactErrorText("Authorization failed: Bearer sk-abcdef1234567890ghijklmnop")
        assertFalse(result.contains("sk-abcdef1234567890ghijklmnop"))
        assertTrue(result.contains("[token]"))
    }

    @Test
    fun `redacts api-key-shaped tokens outside of a bearer header`() {
        val result = redactErrorText("invalid key: sk-abcdef1234567890ghijklmnop")
        assertFalse(result.contains("sk-abcdef1234567890ghijklmnop"))
    }

    @Test
    fun `redacts absolute unix file paths`() {
        val result = redactErrorText("failed to read /Users/alice/secret-project/config.json")
        assertFalse(result.contains("/Users/alice/secret-project/config.json"))
        assertTrue(result.contains("[path]"))
    }

    @Test
    fun `redacts long alphanumeric ids such as account ids and uuids`() {
        val result = redactErrorText("account 'TaVuEPzt2Ve4oM2Rd4FxTTpi_IP8blJJn5271Ebcgwo' not found")
        assertFalse(result.contains("TaVuEPzt2Ve4oM2Rd4FxTTpi_IP8blJJn5271Ebcgwo"))
        assertTrue(result.contains("[id]"))
    }

    @Test
    fun `preserves safe diagnostic words needed to classify new error shapes`() {
        val result = redactErrorText(
            "unknown model architecture: 'mllama' — llama-server process has terminated",
        )
        // Short tokens like "mllama" (under the 16-char id threshold) and ordinary English
        // words must survive redaction — otherwise we lose the ability to spot new patterns.
        assertTrue(result.contains("mllama"))
        assertTrue(result.contains("unknown model architecture"))
        assertTrue(result.contains("llama-server"))
    }

    @Test
    fun `collapses newlines to spaces`() {
        val result = redactErrorText("line one\nline two\r\nline three")
        assertFalse(result.contains("\n"))
        assertFalse(result.contains("\r"))
    }

    @Test
    fun `truncates to 500 characters`() {
        // Use realistic word-separated text, not one giant contiguous token — a single run of
        // 5000 identical characters would itself match the long-id pattern and collapse to
        // "[id]" before truncation even applies, which would test the wrong thing.
        val result = redactErrorText("error details repeated many times. ".repeat(200))
        assertEquals(500, result.length)
    }
}
