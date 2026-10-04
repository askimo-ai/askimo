/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import io.askimo.core.logging.logger
import io.askimo.core.plan.domain.PlanInput
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.time.Duration

/**
 * Reads content for [PlanInput] entries with `type: file`, `type: folder`, or `type: url`
 * and returns it as a plain-text string ready for scope injection.
 *
 * - `file` / `folder`: reads local file system paths.
 * - `url`: fetches the page over HTTP, strips HTML tags, injects plain text.
 *
 * Content is always capped at [PlanInput.maxKb] kilobytes.
 */
object PlanFileContentReader {

    private val log = logger<PlanFileContentReader>()

    private const val SINGLE_FILE_MAX_KB = 128
    private const val BINARY_THRESHOLD = 0.1

    /**
     * Resolves content for the given [value] (path or URL) according to [input] type.
     */
    fun read(value: String, input: PlanInput): String {
        if (value.isBlank()) return "[Error: no value provided for input '${input.key}']"

        return when (input.type) {
            "url" -> fetchUrl(value, input)
            "file", "folder" -> readFileSystem(value, input)
            else -> "[Error: unsupported content input type '${input.type}']"
        }
    }

    // ── URL ───────────────────────────────────────────────────────────────────

    /**
     * Fetches [url], strips HTML tags, and returns the plain text truncated to [PlanInput.maxKb].
     */
    private fun fetchUrl(url: String, input: PlanInput): String {
        log.debug("Fetching URL for input '{}': {}", input.key, url)

        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(input.fetchTimeoutSec.toLong()))
            .build()

        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(input.fetchTimeoutSec.toLong()))
                .header("User-Agent", "Askimo-Plan/1.0")
                .GET()
                .build()
        } catch (e: Exception) {
            log.warn("Invalid URL '{}': {}", url, e.message)
            return "[Error: invalid URL '$url' — ${e.message}]"
        }

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return "[Error: HTTP ${response.statusCode()} fetching '$url']"
            }
            val text = stripHtml(response.body())
            val header = "=== $url ===\n"
            buildBlock(header, text, input.maxKb * 1024L)
        } catch (e: Exception) {
            log.warn("Failed to fetch URL '{}': {}", url, e.message)
            "[Error: could not fetch '$url' — ${e.message}]"
        }
    }

    /**
     * Strips HTML tags and normalises whitespace to produce readable plain text.
     * Handles `<br>`, `<p>`, `<div>`, `<li>` as line breaks before stripping all other tags.
     */
    private fun stripHtml(html: String): String = html
        .replace(Regex("<(br|p|div|li|tr|h[1-6])(\\s[^>]*)?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("&#39;"), "'")
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    // ── File system ───────────────────────────────────────────────────────────

    private fun readFileSystem(path: String, input: PlanInput): String {
        val file = File(path)
        if (!file.exists()) {
            log.warn("PlanFileContentReader: path does not exist: {}", path)
            return "[Error: path '$path' does not exist]"
        }

        val maxBytes = input.maxKb * 1024L
        val globs = parseGlobs(input.filter)

        return when {
            input.type == "file" && file.isFile -> readSingleFile(file, maxBytes)

            input.type == "folder" && file.isDirectory -> readFolder(file, globs, maxBytes)

            input.type == "file" && file.isDirectory -> readFolder(file, globs, maxBytes)

            else -> {
                log.warn("PlanFileContentReader: unexpected combination type={} isFile={} isDir={}", input.type, file.isFile, file.isDirectory)
                "[Error: '$path' is not a valid ${input.type}]"
            }
        }
    }

    private fun readSingleFile(file: File, maxBytes: Long): String {
        if (isBinary(file)) {
            log.debug("Skipping binary file: {}", file.name)
            return "[Skipped: '${file.name}' appears to be a binary file]"
        }
        if (file.length() > SINGLE_FILE_MAX_KB * 1024L) {
            log.debug("Skipping oversized single file: {} ({} KB)", file.name, file.length() / 1024)
            return "[Skipped: '${file.name}' exceeds the ${SINGLE_FILE_MAX_KB} KB per-file limit]"
        }
        val content = file.readText(Charsets.UTF_8)
        return buildBlock("=== ${file.name} ===\n", content, maxBytes)
    }

    private fun readFolder(root: File, globs: List<String>, maxBytes: Long): String {
        val files = root.walkTopDown()
            .filter { it.isFile }
            .filter { matchesGlobs(it, globs) }
            .filter { !isBinary(it) }
            .filter { it.length() <= SINGLE_FILE_MAX_KB * 1024L }
            .sortedBy { it.relativeTo(root).path }
            .toList()

        if (files.isEmpty()) return "[No matching text files found in '${root.name}']"

        val sb = StringBuilder()
        var totalBytes = 0L
        var truncated = false

        for (file in files) {
            val header = "=== ${file.relativeTo(root).path} ===\n"
            val content = file.readText(Charsets.UTF_8)
            val block = "$header$content\n\n"
            val blockBytes = block.toByteArray(Charsets.UTF_8).size

            if (totalBytes + blockBytes > maxBytes) {
                truncated = true
                break
            }
            sb.append(block)
            totalBytes += blockBytes
        }

        if (truncated) {
            sb.append("[... content truncated at ${maxBytes / 1024} KB limit — remaining files omitted ...]")
        }

        return sb.toString().trimEnd()
    }

    private fun buildBlock(header: String, content: String, maxBytes: Long): String {
        val full = "$header$content"
        val bytes = full.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= maxBytes) {
            full
        } else {
            val truncated = String(bytes.copyOf(maxBytes.toInt()), Charsets.UTF_8)
            "$truncated\n[... truncated at ${maxBytes / 1024} KB limit ...]"
        }
    }

    private fun parseGlobs(filter: String): List<String> = if (filter.isBlank()) {
        emptyList()
    } else {
        filter.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun matchesGlobs(file: File, globs: List<String>): Boolean {
        if (globs.isEmpty()) return true
        val fs = FileSystems.getDefault()
        return globs.any { glob -> fs.getPathMatcher("glob:$glob").matches(file.toPath().fileName) }
    }

    private fun isBinary(file: File): Boolean {
        if (file.length() == 0L) return false
        return try {
            val sample = file.inputStream().use { it.readNBytes(8192) }
            val nonPrintable = sample.count { b ->
                val c = b.toInt() and 0xFF
                c < 0x09 || (c in 0x0E..0x1F)
            }
            nonPrintable.toDouble() / sample.size > BINARY_THRESHOLD
        } catch (_: Exception) {
            true
        }
    }
}
