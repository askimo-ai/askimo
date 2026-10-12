/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.StreamingDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
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
     * Backfills [installedVersion]'s marker for [platform] **without any network call**, for
     * installs from older Askimo builds that predate version-marker tracking.
     *
     * Only safe where [LlamaServerPlatform.executableName] embeds the release tag in its path
     * (macOS/Linux: `llama-$releaseTag/llama-server`) — there, [executablePath] existing *is*
     * proof it matches [LlamaServerCatalog.releaseTag]. Windows' fixed `llama-server.exe` path
     * proves nothing about the installed version, so this is a no-op there (stays "unknown")
     * rather than calling the real (network) [download] just to find out — which would silently
     * trigger a large download from merely opening a settings page.
     */
    fun backfillVersionMarkerIfProvable(platform: LlamaServerPlatform) {
        if (installedVersion(platform) != null) return
        if (!isDownloaded(platform)) return
        when (platform.id) {
            LlamaPlatformId.WINDOWS_X86_64, LlamaPlatformId.WINDOWS_AARCH64 -> return
            else -> writeVersionMarker(platform)
        }
    }

    /**
     * Downloads and extracts [platform]'s `llama-server` archive, reporting progress via
     * [onProgress] as `(bytesDownloaded, totalBytes)` (`totalBytes` is `-1` if unknown).
     * No-op if already downloaded.
     *
     * The downloaded archive's SHA-256 is verified against the pinned checksum from
     * [LlamaServerCatalog.sha256] **before** any extraction happens — this is executable code
     * that gets launched directly, so a `Content-Length` match alone (which only rules out
     * truncation) is not sufficient integrity assurance against a compromised mirror/CDN.
     *
     * @throws DownloadException on network failure, non-2xx response, checksum mismatch, or
     * extraction failure.
     */
    suspend fun download(
        platform: LlamaServerPlatform,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        // isDownloaded only checks the executable exists — on platforms whose executableName
        // doesn't embed the release tag (e.g. Windows), that path never changes when releaseTag
        // is bumped, so a stale binary would be kept forever (and an unmarked legacy install
        // wrongly assumed current). Only skip the download when the recorded version matches.
        if (isDownloaded(platform) && installedVersion(platform) == LlamaServerCatalog.releaseTag()) {
            _binaryEnabled.value = true
            return@withContext executablePath(platform)
        }

        val assetUrl = LlamaServerCatalog.downloadUrl(platform)
        val destDir = platformDir(platform)
        Files.createDirectories(destDir)
        val archivePath = AskimoHome.llamaBinDir().resolve("${platform.resourceDir}.download")

        log.info("Downloading llama-server for {} from {}", platform.resourceDir, assetUrl)
        val downloadedBytes = try {
            StreamingDownloader.download(
                url = assetUrl,
                destPath = archivePath,
                connectTimeoutMs = Duration.ofSeconds(30).toMillis(),
                readTimeoutMs = Duration.ofHours(1).toMillis(),
                connectErrorMessage = { msg -> "Failed to connect to GitHub to download llama-server: $msg" },
                httpErrorMessage = { status ->
                    "Downloading llama-server failed (HTTP $status) from $assetUrl. " +
                        "The llama.cpp release asset layout may have changed — see LlamaServerCatalog."
                },
                incompleteMessage = { downloaded, total ->
                    "Download of llama-server appears incomplete ($downloaded of $total bytes received). " +
                        "Please try downloading again."
                },
                onProgress = onProgress,
            )
        } catch (e: StreamingDownloader.DownloadException) {
            throw DownloadException(e.message ?: "Download of llama-server failed or was cancelled.", e)
        }

        // The archive is extracted and its executable later launched directly — a Content-Length
        // match (already enforced by StreamingDownloader) only rules out truncation, not a
        // mirror/CDN/compromised host serving substitute bytes of the exact expected length.
        // Verify against the pinned per-platform checksum before touching the archive contents,
        // and fail closed (delete + throw) on any mismatch rather than extracting first.
        val expectedSha256 = LlamaServerCatalog.sha256(platform)
        val actualSha256 = sha256Hex(archivePath)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            Files.deleteIfExists(archivePath)
            throw DownloadException(
                "Checksum verification failed for llama-server archive downloaded from $assetUrl " +
                    "(expected sha256 $expectedSha256, got $actualSha256). Refusing to extract a " +
                    "potentially corrupted or tampered archive. If the llama.cpp release asset was " +
                    "legitimately updated, the pinned checksum in LocalLlmConfig must be updated too.",
            )
        }

        // destDir isn't keyed by release tag, and on Windows executableName has no
        // version-specific subpath at all — extracting over an old install would leave files
        // the new release removed/renamed behind. Clear it now that the archive is verified.
        prepareCleanDestDir(destDir)

        try {
            Files.newInputStream(archivePath).use { raw ->
                when (platform.archiveFormat) {
                    ArchiveFormat.ZIP -> extractZip(raw, destDir)
                    ArchiveFormat.TAR_GZ -> extractTarGz(raw, destDir)
                }
            }
        } catch (e: Exception) {
            // Partial extraction can leave a non-empty executable, which isDownloaded would
            // then mistake for a complete install. Clean up so a retry starts fresh.
            deletePartialInstall(destDir)
            throw DownloadException("Failed to extract llama-server archive: ${e.message}", e)
        } finally {
            Files.deleteIfExists(archivePath)
        }

        val exe = executablePath(platform)
        if (!Files.exists(exe)) {
            deletePartialInstall(destDir)
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

    /** Computes the lowercase hex SHA-256 digest of the file at [path], streaming it in chunks. */
    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 20) // 1 MiB
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun makeExecutable(path: Path) {
        if (Files.isExecutable(path)) return
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms.addAll(
                setOf(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_EXECUTE,
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

    /** Recursively removes a partially-extracted install directory after an extraction failure. */
    private fun deletePartialInstall(dir: Path) {
        if (!Files.exists(dir)) return
        runCatching {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * Wipes [destDir] (if present) and recreates it empty. Factored out of [download] so this
     * specific "don't let a new release's extraction merge with a stale old install" step is
     * independently unit-testable without needing a network call or a real archive.
     */
    internal fun prepareCleanDestDir(destDir: Path) {
        deletePartialInstall(destDir)
        Files.createDirectories(destDir)
    }

    // ── Archive extraction ──────────────────────────────────────────────────────────────────

    /**
     * Resolves a zip/tar entry's [name] against [destDir], rejecting both lexical traversal
     * (`../`) and traversal *through* an existing symlink ancestor. The lexical
     * `outPath.startsWith(destDir)` check alone is insufficient: a malicious archive can first
     * create `dest/link -> ../../outside` (a symlink entry, which passes the lexical check since
     * `link` itself is a legitimate child of `destDir`), then a *later* entry named `link/evil`
     * resolves lexically to `dest/link/evil` — still underneath `destDir` as a string — but
     * [Files.newOutputStream]/[Files.createDirectories] follow the `link` symlink at the OS level
     * and actually write outside the installation directory entirely. Rejecting any entry whose
     * path has a symlink as one of its ancestors closes that gap.
     */
    private fun resolveSafeEntryPath(destDir: Path, name: String): Path {
        val outPath = destDir.resolve(name).normalize()
        if (!outPath.startsWith(destDir)) {
            throw IOException("Archive entry escapes destination directory: $name")
        }
        var ancestor = outPath.parent
        while (ancestor != null && ancestor != destDir && ancestor.startsWith(destDir)) {
            if (Files.isSymbolicLink(ancestor)) {
                throw IOException("Archive entry traverses a symlinked ancestor directory: $name")
            }
            ancestor = ancestor.parent
        }
        return outPath
    }

    /**
     * Validates that a symlink entry's target doesn't escape [destDir]. An absolute target, or
     * a relative target that normalizes to somewhere outside `destDir` once resolved against the
     * symlink's own parent directory, is rejected — otherwise the symlink itself becomes the
     * escape vector even though its own entry path already passed [resolveSafeEntryPath].
     */
    private fun validateSymlinkTarget(destDir: Path, outPath: Path, linkName: String) {
        val linkTargetPath = java.nio.file.Paths.get(linkName)
        if (linkTargetPath.isAbsolute) {
            throw IOException("Archive symlink target is absolute: $linkName")
        }
        val resolvedTarget = outPath.parent.resolve(linkTargetPath).normalize()
        if (!resolvedTarget.startsWith(destDir)) {
            throw IOException("Archive symlink target escapes destination directory: $linkName -> $resolvedTarget")
        }
    }

    private fun extractZip(input: InputStream, destDir: Path) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outPath = resolveSafeEntryPath(destDir, entry.name)
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
                // Valid tarballs only end via a full all-zero block (checked below), never
                // mid-header — a short read here means the tar payload was truncated/corrupted,
                // which must not be mistaken for a clean end-of-archive (isDownloaded would then
                // treat the truncated install as complete).
                if (read == 0) break
                if (read < 512) {
                    throw IOException("Truncated tar archive: expected a 512-byte header, got only $read byte(s).")
                }
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
                        val outPath = resolveSafeEntryPath(destDir, name)
                        Files.createDirectories(outPath)
                    }

                    '0', '\u0000' -> {
                        // Regular file
                        val outPath = resolveSafeEntryPath(destDir, name)
                        Files.createDirectories(outPath.parent)
                        Files.newOutputStream(outPath).use { out ->
                            copyExactly(gzip, out, size)
                        }
                        skip(gzip, paddedSize - size)
                    }

                    '2' -> {
                        // Symlink — no data block (size is normally 0), target is in the header.
                        val outPath = resolveSafeEntryPath(destDir, name)
                        validateSymlinkTarget(destDir, outPath, linkName)
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
            if (read == -1) {
                // gzip can be well-formed while the tar payload it wraps is truncated — without
                // this check a short read is silently accepted, leaving a truncated executable
                // marked as a complete install.
                throw IOException("Unexpected end of archive: expected $remaining more byte(s) of entry data.")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
