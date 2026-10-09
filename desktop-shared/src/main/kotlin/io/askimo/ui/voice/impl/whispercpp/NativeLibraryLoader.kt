/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl.whispercpp

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Extracts the bundled whisper.cpp native libraries (see `desktop-shared/src/main/resources/
 * natives/whisper/<platform>/`) to a per-user cache directory and [System.load]s the main
 * library so [io.askimo.ui.voice.whispercpp.whisper_h]'s symbol lookup resolves against it.
 *
 * **[ensureLoaded] MUST be called before any `whisper_h`/`whisper_h_1` call.** jextract's
 * generated `SYMBOL_LOOKUP` (patched by `scripts/generate-whisper-bindings.sh`) only uses
 * [java.lang.foreign.SymbolLookup.loaderLookup] — it finds symbols from already-[System.load]ed
 * libraries but never loads anything itself. (jextract's *default* lookup would instead throw
 * `IllegalArgumentException` up front, since our cache dir isn't on the default library search
 * path — hence the fallback-only lookup and the mandatory preload.)
 *
 * All sibling files in the platform folder are extracted together. CPU dispatch variants
 * (`libggml-cpu-*`) need no special handling — ggml's backend registry finds them at runtime
 * next to `ggml-base`. The *direct* dependencies ([PlatformTarget.preloadOrder]) do need
 * explicit, ordered [System.load] calls before the main library, so the OS loader resolves
 * `whisper`'s `ggml`/`ggml-base` imports against them by name instead of relying on default
 * search-path behavior (unreliable for our temp dir on Linux/Windows).
 *
 * Extraction (vs. loading straight from the classpath) is required because [System.load] needs
 * a real filesystem path.
 */
object NativeLibraryLoader {
    private val log = logger<NativeLibraryLoader>()

    @Volatile
    private var loaded = false
    private val lock = Any()

    /**
     * Platform/arch resource folder under `natives/whisper/`, the main library's filename, and
     * [preloadOrder] — direct dependencies that must be [System.load]ed (by exact
     * filename/soname) before the main library so the OS loader resolves against them. Empty
     * for macOS, whose single universal dylib only depends on system frameworks.
     */
    private data class PlatformTarget(
        val resourceDir: String,
        val mainLibraryFile: String,
        val preloadOrder: List<String> = emptyList(),
    )

    private fun detectPlatformTarget(): PlatformTarget {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val archRaw = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val isArm64 = archRaw.contains("aarch64") || archRaw.contains("arm64")

        return when {
            osName.contains("mac") || osName.contains("darwin") ->
                PlatformTarget("macos", "libwhisper.dylib")

            osName.contains("win") -> if (isArm64) {
                PlatformTarget(
                    "windows-aarch64",
                    "whisper.dll",
                    preloadOrder = listOf("libomp140.aarch64.dll", "ggml-base.dll", "ggml.dll"),
                )
            } else {
                PlatformTarget(
                    "windows-x86_64",
                    "whisper.dll",
                    preloadOrder = listOf("ggml-base.dll", "ggml.dll"),
                )
            }

            osName.contains("nux") || osName.contains("nix") -> PlatformTarget(
                if (isArm64) "linux-aarch64" else "linux-x86_64",
                "libwhisper.so",
                // Exact soname (not the "libggml.so" dev symlink) so the dynamic linker's
                // "already loaded by this soname" check matches libwhisper.so's DT_NEEDED.
                preloadOrder = listOf("libggml-base.so.0", "libggml.so.0"),
            )

            else -> error("Unsupported OS for whisper.cpp FFM native library: $osName ($archRaw)")
        }
    }

    /** Idempotent — extraction + [System.load] only happen once per JVM process. */
    @Suppress("UnsafeDynamicallyLoadedCode")
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val target = detectPlatformTarget()
            val destDir = AskimoHome.base().resolve("natives").resolve("whisper").resolve(target.resourceDir)
            val resourceRoot = "natives/whisper/${target.resourceDir}/"

            extractResourceDirectory(resourceRoot, destDir)

            for (dependencyFile in target.preloadOrder) {
                val path = destDir.resolve(dependencyFile)
                check(Files.exists(path)) { "Missing whisper.cpp native dependency after extraction: $path" }
                log.debug("Preloading whisper.cpp native dependency {}", path)
                System.load(path.toAbsolutePath().toString())
            }

            val mainLibPath = destDir.resolve(target.mainLibraryFile)
            check(Files.exists(mainLibPath)) {
                "whisper.cpp native library not found after extraction: $mainLibPath " +
                    "(resource dir '$resourceRoot' missing from classpath?)"
            }

            log.info("Loading whisper.cpp native library from {}", mainLibPath)
            System.load(mainLibPath.toAbsolutePath().toString())
            loaded = true
        }
    }

    /**
     * Copies every file from [listResourceFiles] under `resourceRoot` into [destDir], skipping
     * files whose size already matches to avoid re-extracting on every launch.
     */
    private fun extractResourceDirectory(resourceRoot: String, destDir: Path) {
        Files.createDirectories(destDir)
        val fileNames = listResourceFiles(resourceRoot)
        check(fileNames.isNotEmpty()) {
            "No bundled native files found under classpath resource '$resourceRoot' — " +
                "was this platform's natives folder included in the build?"
        }

        for (fileName in fileNames) {
            val resourcePath = "$resourceRoot$fileName"
            val destFile = destDir.resolve(fileName)
            val resourceUrl = javaClass.classLoader.getResource(resourcePath)
                ?: error("Missing bundled native resource: $resourcePath")

            if (Files.exists(destFile) && Files.size(destFile) == resourceUrl.contentLengthSafe()) {
                continue
            }

            javaClass.classLoader.getResourceAsStream(resourcePath)!!.use { input ->
                Files.copy(input, destFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun URL.contentLengthSafe(): Long = runCatching { openConnection().contentLengthLong }.getOrDefault(-1L)

    /**
     * Lists file names directly under [resourceRoot] on the classpath — works both from
     * exploded classes (IDE/test) and from a packaged jar:
     * - Exploded: lists the directory via [File.listFiles].
     * - Jar: falls back to a `.manifest` file (one filename per line) committed alongside each
     *   platform's natives (see `.../natives/whisper/<platform>/.manifest`), since jar directory
     *   listing isn't reliably available via [ClassLoader]. Keep in sync manually when files are
     *   added/removed.
     */
    private fun listResourceFiles(resourceRoot: String): List<String> {
        val resourceUrl = javaClass.classLoader.getResource(resourceRoot)
        if (resourceUrl != null && resourceUrl.protocol == "file") {
            // Exploded classpath (IDE run / test) — list the directory directly.
            return File(resourceUrl.toURI()).listFiles()?.map { it.name } ?: emptyList()
        }

        // Packaged jar — read the manifest listing written alongside the natives at build time.
        val manifestPath = "$resourceRoot.manifest"
        javaClass.classLoader.getResourceAsStream(manifestPath)?.use { input ->
            return input.bufferedReader(Charsets.UTF_8).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        error(
            "Cannot list bundled native files under '$resourceRoot' from a packaged jar without " +
                "a '$manifestPath' listing file. Add/update that .manifest file (one filename " +
                "per line) alongside the natives for this platform.",
        )
    }
}
