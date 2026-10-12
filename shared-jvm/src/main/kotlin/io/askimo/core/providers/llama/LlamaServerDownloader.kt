/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.ProxyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Downloads and extracts the platform-matching `llama-server` release asset (see
 * [LlamaServerCatalog]) to [io.askimo.core.util.AskimoHome.llamaBinDir].
 *
 * Triggered **explicitly** from Settings (same "explicit download, not lazy" rule already used
 * for Whisper models) — the user opts into the built-in local LLM provider and sees a progress
 * bar before it becomes usable.
 *
 * No external archive library is used (no `commons-compress` dependency in this module) — zip
 * extraction uses [ZipInputStream] (JDK built-in) and tar.gz extraction uses a small hand-rolled
 * POSIX tar reader over [GZIPInputStream], since only [ArchiveFormat.ZIP] (macOS/Windows assets)
 * and [ArchiveFormat.TAR_GZ] (Linux assets) are needed.
 */
object LlamaServerDownloader {

    private val log = logger<LlamaServerDownloader>()

    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val _binaryEnabled = MutableStateFlow(false)

    /**
     * Reactive "is the llama-server engine binary downloaded" status for the current platform —
     * single source of truth shared by Settings UI and the footer status pill, so toggling the
     * binary Switch in Settings is reflected everywhere immediately in the same session (not
     * just after an app restart). Call [refreshBinaryStatus] once per composable root to seed
     * this from disk; [download]/[delete] keep it current afterward.
     */
    val binaryEnabled: StateFlow<Boolean> = _binaryEnabled.asStateFlow()

    /** (Re)computes [binaryEnabled] from disk for [platform] — call once at startup to seed it. */
    fun refreshBinaryStatus(platform: LlamaServerPlatform) {
        _binaryEnabled.value = isDownloaded(platform)
    }

    /** File name of the small marker file recording which release tag was actually extracted. */
    private const val VERSION_MARKER_FILE_NAME = ".installed_version"

    /**
     * Returns the llama.cpp release tag (see [LlamaServerCatalog.releaseTag]) that was
     * actually downloaded and extracted for [platform], or `null` if not downloaded — or if it
     * was downloaded by an older Askimo build that predates this tracking, in which case we
     * deliberately return `null` rather than guess, since the pre-tracking directory layout can't
     * be reliably mapped back to a specific tag. [download] backfills the marker the next time it
     * runs (even on the "already downloaded" fast path), so this self-heals within one session.
     */
    fun installedVersion(platform: LlamaServerPlatform): String? {
        val marker = platformDir(platform).resolve(VERSION_MARKER_FILE_NAME)
        if (!Files.exists(marker)) return null
        return runCatching { Files.readString(marker).trim() }.getOrNull()?.ifBlank { null }
    }

    private fun writeVersionMarker(platform: LlamaServerPlatform) {
        val marker = platformDir(platform).resolve(VERSION_MARKER_FILE_NAME)
        runCatching { Files.writeString(marker, LlamaServerCatalog.releaseTag()) }
    }

    /** Directory the current platform's `llama-server` + shared libs are extracted to. */
    private fun platformDir(platform: LlamaServerPlatform): Path = AskimoHome.llamaBinDir().resolve(platform.resourceDir)

    /** Resolves the extracted `llama-server` executable path for [platform]. */
    fun executablePath(platform: LlamaServerPlatform): Path = platformDir(platform).resolve(platform.executableName(LlamaServerCatalog.releaseTag()))

    /** `true` if [platform]'s binary has already been downloaded and extracted. */
    fun isDownloaded(platform: LlamaServerPlatform): Boolean {
        val exe = executablePath(platform)
        return Files.exists(exe) && Files.size(exe) > 0
    }

    /**
     * Downloads and extracts [platform]'s `llama-server` archive, reporting progress via
     * [onProgress] as `(bytesDownloaded, totalBytes)` (`totalBytes` is `-1` if unknown).
     * No-op if already downloaded.
     *
     * @throws DownloadException on network failure, non-2xx response, or extraction failure.
     */
    suspend fun download(
        platform: LlamaServerPlatform,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        if (isDownloaded(platform)) {
            writeVersionMarker(platform) // backfill if missing (pre-tracking install)
            _binaryEnabled.value = true
            return@withContext executablePath(platform)
        }

        val assetUrl = LlamaServerCatalog.downloadUrl(platform)
        val destDir = platformDir(platform)
        Files.createDirectories(destDir)
        val archivePath = AskimoHome.llamaBinDir().resolve("${platform.resourceDir}.download")

        val client = ProxyUtil.configureProxy(HttpClient.newBuilder(), assetUrl)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI(assetUrl))
            .timeout(Duration.ofHours(1))
            .GET()
            .build()

        log.info("Downloading llama-server for {} from {}", platform.resourceDir, assetUrl)
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw DownloadException("Failed to connect to GitHub to download llama-server: ${e.message}", e)
        }

        if (response.statusCode() !in 200..299) {
            throw DownloadException(
                "Downloading llama-server failed (HTTP ${response.statusCode()}) from $assetUrl. " +
                    "The llama.cpp release asset layout may have changed — see LlamaServerCatalog.",
            )
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var downloadedBytes = 0L

        try {
            response.body().use { input ->
                Files.newOutputStream(archivePath).use { output ->
                    val buffer = ByteArray(1 shl 20) // 1 MiB
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Files.deleteIfExists(archivePath)
            throw DownloadException("Download of llama-server failed or was cancelled: ${e.message}", e)
        }

        try {
            Files.newInputStream(archivePath).use { raw ->
                when (platform.archiveFormat) {
                    ArchiveFormat.ZIP -> extractZip(raw, destDir)
                    ArchiveFormat.TAR_GZ -> extractTarGz(raw, destDir)
                }
            }
        } catch (e: Exception) {
            throw DownloadException("Failed to extract llama-server archive: ${e.message}", e)
        } finally {
            Files.deleteIfExists(archivePath)
        }

        val exe = executablePath(platform)
        if (!Files.exists(exe)) {
            throw DownloadException(
                "Extraction succeeded but expected executable was not found at $exe. " +
                    "The archive's internal layout may have changed — see LlamaServerCatalog.executableName.",
            )
        }
        makeExecutable(exe)
        // Make every extracted shared library/executable in the directory executable too —
        // llama-server dlopen's sibling ggml backend libraries at runtime.
        Files.walk(destDir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { runCatching { makeExecutable(it) } }
        }

        log.info("Downloaded and extracted llama-server ({} bytes) to {}", downloadedBytes, destDir)
        writeVersionMarker(platform)
        _binaryEnabled.value = true
        exe
    }

    private fun makeExecutable(path: Path) {
        if (Files.isExecutable(path)) return
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms.addAll(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Windows has no POSIX permission bits — .exe files are executable by default.
        }
    }

    /** Deletes the extracted binary directory for [platform], e.g. to force a re-download. */
    fun delete(platform: LlamaServerPlatform) {
        val dir = platformDir(platform)
        _binaryEnabled.value = false
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ── Archive extraction ──────────────────────────────────────────────────────────────────

    private fun extractZip(input: InputStream, destDir: Path) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outPath = destDir.resolve(entry.name).normalize()
                if (!outPath.startsWith(destDir)) {
                    throw IOException("Zip entry escapes destination directory: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outPath)
                } else {
                    Files.createDirectories(outPath.parent)
                    Files.newOutputStream(outPath).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Minimal POSIX ustar/GNU-tar reader — enough to extract a `llama-server` release tarball
     * (regular files, directories, symlinks, and GNU long-name/long-link entries). No external
     * archive library dependency is pulled in for this one use case.
     *
     * Symlink support matters here: llama.cpp's macOS/Linux release tarballs ship each shared
     * library under its full versioned name (e.g. `libllama-common.0.6.0.dylib`) plus a
     * symlinked soname (e.g. `libllama-common.0.dylib`) that `llama-server`'s dyld/`@rpath`
     * dependency actually references. Silently skipping symlink entries (as a prior version of
     * this reader did) leaves those soname files missing entirely, and `llama-server` fails to
     * launch with a `dyld: Library not loaded` / "no such file" error at runtime.
     */
    private fun extractTarGz(input: InputStream, destDir: Path) {
        GZIPInputStream(input).use { gzip ->
            val header = ByteArray(512)
            var pendingLongName: String? = null
            var pendingLongLinkName: String? = null
            while (true) {
                val read = readFully(gzip, header)
                if (read < 512) break
                if (header.all { it == 0.toByte() }) break // end-of-archive marker

                val nameFromHeader = String(header, 0, 100).trimEnd('\u0000')
                val sizeOctal = String(header, 124, 12).trimEnd('\u0000', ' ').trim()
                val size = if (sizeOctal.isBlank()) 0L else sizeOctal.toLong(8)
                val typeFlag = header[156].toInt().toChar()
                val name = pendingLongName ?: nameFromHeader
                val linkNameFromHeader = String(header, 157, 100).trimEnd('\u0000')
                val linkName = pendingLongLinkName ?: linkNameFromHeader
                pendingLongName = null
                pendingLongLinkName = null

                val paddedSize = ((size + 511) / 512) * 512
                when (typeFlag) {
                    'L' -> {
                        // GNU long-name entry: the data block holds the real name of the *next* entry.
                        val nameBytes = ByteArray(size.toInt())
                        readFully(gzip, nameBytes)
                        skip(gzip, paddedSize - size)
                        pendingLongName = String(nameBytes).trimEnd('\u0000')
                    }

                    'K' -> {
                        // GNU long-link entry: the data block holds the real symlink target of
                        // the *next* entry (mirrors 'L' but for the link name, not the file name).
                        val linkBytes = ByteArray(size.toInt())
                        readFully(gzip, linkBytes)
                        skip(gzip, paddedSize - size)
                        pendingLongLinkName = String(linkBytes).trimEnd('\u0000')
                    }

                    '5' -> {
                        // Directory
                        val outPath = destDir.resolve(name).normalize()
                        if (!outPath.startsWith(destDir)) throw IOException("Tar entry escapes destination directory: $name")
                        Files.createDirectories(outPath)
                    }

                    '0', '\u0000' -> {
                        // Regular file
                        val outPath = destDir.resolve(name).normalize()
                        if (!outPath.startsWith(destDir)) throw IOException("Tar entry escapes destination directory: $name")
                        Files.createDirectories(outPath.parent)
                        Files.newOutputStream(outPath).use { out ->
                            copyExactly(gzip, out, size)
                        }
                        skip(gzip, paddedSize - size)
                    }

                    '2' -> {
                        // Symlink — no data block (size is normally 0), target is in the header.
                        val outPath = destDir.resolve(name).normalize()
                        if (!outPath.startsWith(destDir)) throw IOException("Tar entry escapes destination directory: $name")
                        Files.createDirectories(outPath.parent)
                        Files.deleteIfExists(outPath)
                        Files.createSymbolicLink(outPath, java.nio.file.Paths.get(linkName))
                        skip(gzip, paddedSize)
                    }

                    else -> {
                        // PAX extended headers, etc. — skip the data block.
                        skip(gzip, paddedSize)
                    }
                }
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }

    private fun skip(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            remaining -= read
        }
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(1 shl 16)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
