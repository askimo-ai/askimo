/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.service

import io.askimo.core.VersionInfo
import io.askimo.core.logging.logger
import io.askimo.core.util.JsonUtils.json
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@kotlinx.serialization.Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val published_at: String,
    val html_url: String,
    val body_html: String?,
)

/**
 * Information about a software release.
 *
 * @param versionsBehind How many releases the current install lags behind the latest.
 *        Capped at [UpdateChecker.MAX_VERSIONS_BEHIND_CAP]. 0 means up to date.
 */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String,
    val releaseDate: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isNewVersion: Boolean,
    val versionsBehind: Int = 0,
)

/**
 * Core service to check for application updates from GitHub releases.
 * This service is shared across desktop and CLI applications.
 */
class UpdateChecker(
    private val userAgent: String = "Askimo/${VersionInfo.version}",
) {
    private val log = logger<UpdateChecker>()

    private val releasesUrl = "https://api.askimo.chat/releases?per_page=1"

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Check for updates from the Askimo public API.
     * Returns null if the check fails or if there's an error.
     */
    fun checkForUpdates(): UpdateInfo? = try {
        log.debug("Checking for updates from: $releasesUrl")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(releasesUrl))
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            parseUpdateInfo(response.body())
        } else {
            log.warn("Failed to check for updates. Status code: ${response.statusCode()}")
            null
        }
    } catch (e: Exception) {
        log.error("Error checking for updates", e)
        null
    }

    private fun parseUpdateInfo(jsonResponse: String): UpdateInfo? = try {
        val releases = json.decodeFromString(ListSerializer(GitHubRelease.serializer()), jsonResponse)
        if (releases.isEmpty()) return null

        val latest = releases.first()
        val latestVersion = latest.tag_name.removePrefix("v")
        val currentVersion = VersionInfo.version

        log.debug("Current version: $currentVersion, Latest version: $latestVersion")

        val isNewVersion = isNewerVersion(latestVersion, currentVersion)

        UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            releaseName = latest.name,
            releaseDate = formatReleaseDate(latest.published_at),
            downloadUrl = "https://askimo.chat/download/",
            releaseNotes = htmlToMarkdown(latest.body_html.orEmpty()),
            isNewVersion = isNewVersion,
            versionsBehind = if (isNewVersion) 1 else 0,
        )
    } catch (e: Exception) {
        log.error("Error parsing release info", e)
        null
    }

    private fun formatReleaseDate(isoDate: String): String = try {
        isoDate.substringBefore('T')
    } catch (_: Exception) {
        isoDate
    }

    private fun htmlToMarkdown(html: String): String = html
        .replace(Regex("<h1[^>]*>"), "# ")
        .replace(Regex("</h1>"), "\n")
        .replace(Regex("<h2[^>]*>"), "## ")
        .replace(Regex("</h2>"), "\n")
        .replace(Regex("<h3[^>]*>"), "### ")
        .replace(Regex("</h3>"), "\n")
        .replace(Regex("<strong[^>]*>"), "**")
        .replace("</strong>", "**")
        .replace(Regex("<em[^>]*>"), "_")
        .replace("</em>", "_")
        .replace(Regex("<code[^>]*>"), "`")
        .replace("</code>", "`")
        .replace(Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>")) { mr ->
            "[${mr.groupValues[2]}](${mr.groupValues[1]})"
        }
        .replace(Regex("<li[^>]*>"), "- ")
        .replace("</li>", "\n")
        .replace(Regex("<ul[^>]*>"), "\n")
        .replace("</ul>", "\n")
        .replace(Regex("<ol[^>]*>"), "\n")
        .replace("</ol>", "\n")
        .replace(Regex("<p[^>]*>"), "\n")
        .replace("</p>", "\n")
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /**
     * Compare two semantic version strings.
     * Returns true if the latest version is newer than current.
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        if (current == "unknown") return true // Always show updates if version is unknown

        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }

            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    companion object {
        fun getCurrentVersion(): String = VersionInfo.version

        const val MAX_VERSIONS_BEHIND_CAP = 10
    }
}
