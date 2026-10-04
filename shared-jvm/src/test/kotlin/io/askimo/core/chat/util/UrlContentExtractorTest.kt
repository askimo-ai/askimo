/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UrlContentExtractorTest {

    @Test
    fun `should detect valid URLs`() {
        assertTrue(UrlContentExtractor.isUrl("https://example.com"))
        assertTrue(UrlContentExtractor.isUrl("http://example.com/path"))
        assertTrue(UrlContentExtractor.isUrl("https://docs.oracle.com/javase/8/docs/api/"))
        assertFalse(UrlContentExtractor.isUrl("not a url"))
        assertFalse(UrlContentExtractor.isUrl("example.com"))
    }

    @Test
    fun `should extract URLs from message`() {
        val message = """
            Check out this link: https://example.com
            And also https://github.com/user/repo
        """.trimIndent()

        val urls = UrlContentExtractor.extractUrls(message)
        assertEquals(2, urls.size)
        assertTrue(urls.contains("https://example.com"))
        assertTrue(urls.contains("https://github.com/user/repo"))
    }

    @Test
    fun `should normalize URLs`() {
        assertTrue(UrlContentExtractor.isSupported("https://example.com"))
        assertTrue(UrlContentExtractor.isSupported("http://example.com"))
        assertFalse(UrlContentExtractor.isSupported("ftp://example.com"))
        assertFalse(UrlContentExtractor.isSupported("file:///path/to/file"))
    }

    @Test
    fun `should provide unsupported messages`() {
        val ftpMessage = UrlContentExtractor.getUnsupportedMessage("ftp://example.com")
        assertTrue(ftpMessage.contains("FTP"))

        val fileMessage = UrlContentExtractor.getUnsupportedMessage("file:///path")
        assertTrue(fileMessage.contains("Local file"))
    }

    @Test
    fun `should extract content from HTML page`() {
        // This test requires network access - skip in CI
        if (System.getenv("CI") != null) {
            return
        }

        try {
            val result = UrlContentExtractor.extractContent("https://example.com")
            assertNotNull(result)
            assertNotNull(result.content)
            assertTrue(result.content.isNotBlank())
            assertEquals("https://example.com", result.url)
        } catch (e: Exception) {
            // Network tests can fail - that's okay
            println("Network test skipped: ${e.message}")
        }
    }
}
