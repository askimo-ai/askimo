/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.util.ProxyUtil
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Result of URL content extraction.
 */
data class ExtractedUrlContent(
    val url: String,
    val title: String?,
    val content: String,
    val contentType: String,
)

/**
 * Utility for extracting text content from URLs.
 * Uses java.net.http.HttpClient for fetching (with proxy support), Jsoup for HTML parsing, and Apache Tika for other formats.
 * Supports HTML pages, PDF documents, plain text, and other text-based formats.
 */
object UrlContentExtractor {

    private val log = currentFileLogger()

    // Lazy initialization to avoid GraalVM native image initialization issues
    private val parser: AutoDetectParser by lazy {
        try {
            AutoDetectParser()
        } catch (e: Exception) {
            log.error("Failed to initialize Apache Tika parser", e)
            throw IllegalStateException("Tika parser initialization failed. This may be a GraalVM native image configuration issue.", e)
        }
    }

    private val httpClient: HttpClient by lazy {
        // Configure HTTP client with proxy support (consistent with AI model factories)
        val httpClientBuilder = ProxyUtil.configureProxy(HttpClient.newBuilder())
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)

        httpClientBuilder.build()
    }

    /**
     * Extract text content from a URL.
     * Supports HTML, PDF, plain text, JSON, XML, and other text-based formats.
     *
     * @param url The URL to extract content from
     * @return ExtractedUrlContent containing the title and cleaned text content
     * @throws Exception if the URL cannot be fetched or the format is unsupported
     */
    fun extractContent(url: String): ExtractedUrlContent {
        val normalizedUrl = normalizeUrl(url)

        val request = HttpRequest.newBuilder()
            .uri(URI(normalizedUrl))
            .header("User-Agent", "Mozilla/5.0 (compatible; Askimo/1.0; +https://$DOMAIN)")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

        if (response.statusCode() !in 200..299) {
            throw IOException("Failed to fetch URL: HTTP ${response.statusCode()}")
        }

        val contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream")
        val bytes = response.body()

        return when {
            contentType.contains("text/html", ignoreCase = true) -> {
                extractHtmlContent(normalizedUrl, bytes)
            }

            contentType.contains("application/pdf", ignoreCase = true) -> {
                extractPdfContent(normalizedUrl, bytes)
            }

            contentType.startsWith("text/", ignoreCase = true) ||
                contentType.contains("json", ignoreCase = true) ||
                contentType.contains("xml", ignoreCase = true) -> {
                val text = String(bytes, Charsets.UTF_8)
                ExtractedUrlContent(
                    url = normalizedUrl,
                    title = extractTitleFromUrl(normalizedUrl),
                    content = ContentSanitizer.sanitizeTemplateVariables(text.trim()),
                    contentType = contentType,
                )
            }

            else -> {
                extractUsingTika(normalizedUrl, bytes, contentType)
            }
        }
    }

    /**
     * Extract content from HTML using Jsoup.
     * Removes scripts, styles, navigation, ads, and other non-content elements.
     */
    private fun extractHtmlContent(url: String, bytes: ByteArray): ExtractedUrlContent {
        val html = String(bytes, Charsets.UTF_8)
        val doc = Jsoup.parse(html, url)

        // Extract title
        val title = doc.title().takeIf { it.isNotBlank() }

        // Remove unwanted elements
        doc.select("script, style, nav, header, footer, aside, .ad, .advertisement, .sidebar, .menu, .navigation").remove()

        val mainContent = doc.body()

        val cleanText = mainContent.text().trim()

        val finalContent = if (cleanText.length < 100) {
            doc.body().text().trim()
        } else {
            cleanText
        }

        return ExtractedUrlContent(
            url = url,
            title = title,
            content = ContentSanitizer.sanitizeTemplateVariables(finalContent),
            contentType = "text/html",
        )
    }

    /**
     * Extract content from PDF using Tika.
     */
    private fun extractPdfContent(url: String, bytes: ByteArray): ExtractedUrlContent {
        val content = extractUsingTika(url, bytes, "application/pdf")
        return content.copy(
            title = content.title ?: extractTitleFromUrl(url),
        )
    }

    /**
     * Extract content using Tika parser.
     */
    private fun extractUsingTika(url: String, bytes: ByteArray, contentType: String): ExtractedUrlContent {
        try {
            ByteArrayInputStream(bytes).use { stream ->
                val handler = BodyContentHandler(-1) // -1 = no character limit
                val metadata = Metadata()
                metadata.set("resourceName", url)
                metadata.set("Content-Type", contentType)

                parser.parse(stream, handler, metadata)

                val extractedText = handler.toString().trim()
                val title = metadata.get("title")?.takeIf { it.isNotBlank() }
                    ?: extractTitleFromUrl(url)

                return ExtractedUrlContent(
                    url = url,
                    title = title,
                    content = ContentSanitizer.sanitizeTemplateVariables(extractedText),
                    contentType = contentType,
                )
            }
        } catch (e: Exception) {
            throw IOException("Failed to parse content from URL: $url", e)
        }
    }

    /**
     * Check if a URL is supported for content extraction.
     *
     * @param url The URL to check
     * @return true if the URL is supported, false otherwise
     */
    fun isSupported(url: String): Boolean = try {
        val uri = URI(url.trim())

        uri.scheme in listOf("http", "https")
    } catch (_: Exception) {
        try {
            val normalizedUrl = normalizeUrl(url)
            val uri = URI(normalizedUrl)
            uri.scheme in listOf("http", "https")
        } catch (e2: Exception) {
            log.debug("URL validation failed for: $url", e2)
            false
        }
    }

    /**
     * Check if a string is a valid URL.
     */
    fun isUrl(text: String): Boolean = URL_REGEX.matches(text.trim())

    /**
     * Extract all URLs from a message.
     */
    fun extractUrls(message: String): List<String> = URL_REGEX.findAll(message)
        .map { it.value }
        .filter { isSupported(it) }
        .toList()

    /**
     * Normalize URL by adding protocol if missing.
     */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.contains("://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    /**
     * Extract a title from URL (last path segment or domain).
     */
    private fun extractTitleFromUrl(url: String): String = try {
        val uri = URI(url)
        val path = uri.path.trim('/')

        if (path.isNotBlank()) {
            path.split('/').last()
                .replace(Regex("[_-]"), " ")
                .replaceFirstChar { it.uppercase() }
        } else {
            uri.host
        }
    } catch (_: Exception) {
        url
    }

    /**
     * Get a user-friendly message for unsupported URLs.
     */
    fun getUnsupportedMessage(url: String): String = try {
        val uri = URI(normalizeUrl(url))
        when (uri.scheme) {
            "ftp", "ftps" -> "FTP links are not supported"
            "file" -> "Local file URLs are not supported"
            else -> "Unsupported URL scheme: ${uri.scheme}"
        }
    } catch (_: Exception) {
        "Invalid URL format"
    }

    /**
     * Regex pattern for detecting URLs in text.
     */
    private val URL_REGEX = Regex(
        """https?://[^\s<>"{}|\\^`\[\]]+""",
        RegexOption.IGNORE_CASE,
    )
}
