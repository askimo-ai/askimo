/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * Formats file size in human-readable format.
 *
 * @param bytes The file size in bytes
 * @return Formatted string (e.g., "1.5 KB", "2 MB")
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${String.format("%.1f", bytes / (1024.0 * 1024))} MB"
}

/**
 * Walks a classpath resource directory and invokes [action] for each matching file.
 *
 * Works in both exploded (IDE / `gradlew run`) and JAR layouts.
 *
 * @param resourceUrl  URL returned by `Class.getResource("/some/dir/")`, or null (no-op).
 * @param jarPath      Path inside the JAR to walk, e.g. `"/directives/"`.
 * @param extensions   File extensions to include (without the dot, e.g. `"yml"`).
 *                     Pass none to include every regular file.
 * @param action       Called for each matching [Path], in sorted order.
 */
fun walkResourceDirectory(
    resourceUrl: URL?,
    jarPath: String,
    vararg extensions: String,
    action: (Path) -> Unit,
) {
    if (resourceUrl == null) return

    fun matches(path: Path): Boolean = Files.isRegularFile(path) &&
        (extensions.isEmpty() || extensions.any { path.fileName.toString().endsWith(".$it") })

    val uri = resourceUrl.toURI()
    if (uri.scheme == "jar") {
        var ownedFs: FileSystem? = null
        val fs = try {
            FileSystems.getFileSystem(uri)
        } catch (_: FileSystemNotFoundException) {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).also { ownedFs = it }
        }
        ownedFs.use { _ ->
            Files.walk(fs.getPath(jarPath))
                .filter { matches(it) }
                .sorted()
                .forEach(action)
        }
    } else {
        Files.walk(Path.of(uri))
            .filter { matches(it) }
            .sorted()
            .forEach(action)
    }
}

/**
 * Parsed representation of a file URL used by RAG citations and markdown links.
 */
data class ParsedFileUrl(
    val filePath: String,
    val lineRange: IntRange? = null,
)

/**
 * Parse a file URL and extract normalized path + optional line range fragment.
 *
 * Supported formats:
 * - `file:///Users/a/b.txt#L1-L20`
 * - `file://Users/a/b.txt#L10` (non-standard but observed)
 * - `file:///C:/Users/dev/a.txt#L3-L9`
 */
fun parseFileUrl(rawUrl: String): ParsedFileUrl {
    val (pathPart, fragment) = rawUrl.split('#', limit = 2).let {
        it[0] to it.getOrElse(1) { "" }
    }

    var filePath = pathPart
        .removePrefix("file://")
        .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    // Handle non-standard but observed forms such as file://Users/... (missing 3rd slash)
    val looksLikeUnixPathMissingLeadingSlash =
        !filePath.startsWith("/") && !Regex("""^[A-Za-z]:[/\\].*""").matches(filePath)
    if (looksLikeUnixPathMissingLeadingSlash) {
        filePath = "/$filePath"
    }

    // Normalize Windows style URLs like /C:/Users/... to C:/Users/...
    if (Regex("""^/[A-Za-z]:[/\\].*""").matches(filePath)) {
        filePath = filePath.removePrefix("/")
    }

    val lineRange = when {
        fragment.isBlank() -> null

        Regex("""^L(\d+)-L(\d+)$""").matches(fragment) -> {
            val m = Regex("""^L(\d+)-L(\d+)$""").find(fragment)!!
            m.groupValues[1].toInt()..m.groupValues[2].toInt()
        }

        Regex("""^L(\d+)$""").matches(fragment) -> {
            val line = Regex("""^L(\d+)$""").find(fragment)!!.groupValues[1].toInt()
            line..line
        }

        else -> null
    }

    return ParsedFileUrl(filePath = filePath, lineRange = lineRange)
}
