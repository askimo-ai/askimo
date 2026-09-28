/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli

import io.askimo.core.chat.util.UrlContentExtractor
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test for UrlContentExtractor to verify it works in GraalVM native image.
 *
 * This test uses real URLs to ensure Apache Tika is properly configured
 * for native image compilation.
 */
@AskimoTestHome
class UrlContentExtractorTest {

    @Test
    fun `should extract content from HTML page`() {
        // Use a simple, stable page that's unlikely to change
        val url = "https://example.com"

        val result = UrlContentExtractor.extractContent(url)

        assertNotNull(result, "Result should not be null")
        assertNotNull(result.title, "Title should be extracted")
        assertTrue(result.content.isNotBlank(), "Content should not be blank")
        assertTrue(result.contentType.contains("text/html"), "Content type should be HTML")

        println("✓ HTML extraction successful")
        println("  URL: ${result.url}")
        println("  Title: ${result.title}")
        println("  Content length: ${result.content.length} chars")
    }

    @Test
    fun `should extract content from PDF using Tika`() {
        // Using a simple PDF document - W3C test file
        // This will trigger the extractPdfContent -> extractUsingTika path
        val url = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"

        val result = UrlContentExtractor.extractContent(url)

        assertNotNull(result, "Result should not be null")
        assertTrue(result.content.isNotBlank(), "Content should not be blank")
        assertTrue(result.contentType.contains("pdf"), "Content type should be PDF")

        println("✓ PDF extraction using Tika successful")
        println("  URL: ${result.url}")
        println("  Title: ${result.title}")
        println("  Content type: ${result.contentType}")
        println("  Content length: ${result.content.length} chars")
        println("  Content preview: ${result.content.take(100)}...")
    }

    @Test
    fun `should extract content from plain text`() {
        // Using a public robots.txt as a simple text file
        val url = "https://www.google.com/robots.txt"

        val result = UrlContentExtractor.extractContent(url)

        assertNotNull(result, "Result should not be null")
        assertTrue(result.content.isNotBlank(), "Content should not be blank")
        assertTrue(
            result.content.contains("User-agent") || result.content.contains("Disallow"),
            "Content should contain robots.txt directives",
        )

        println("✓ Plain text extraction successful")
        println("  URL: ${result.url}")
        println("  Content length: ${result.content.length} chars")
    }

    @Test
    fun `should detect valid URLs`() {
        assertTrue(UrlContentExtractor.isUrl("https://example.com"))
        assertTrue(UrlContentExtractor.isUrl("http://example.com"))
        assertTrue(UrlContentExtractor.isUrl("https://example.com/path/to/page"))

        println("✓ URL detection working")
    }

    @Test
    fun `should check if URL is supported`() {
        assertTrue(UrlContentExtractor.isSupported("https://example.com"))
        assertTrue(UrlContentExtractor.isSupported("http://example.com"))

        println("✓ URL support check working")
    }

    @Test
    fun `should extract URLs from text`() {
        val text = "Check out https://example.com and http://test.org for more info"

        val urls = UrlContentExtractor.extractUrls(text)

        assertTrue(urls.isNotEmpty(), "Should extract URLs from text")
        assertTrue(urls.contains("https://example.com"))
        assertTrue(urls.contains("http://test.org"))

        println("✓ URL extraction from text working")
        println("  Found URLs: $urls")
    }
}
