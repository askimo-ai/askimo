/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Wrapper around ProcessBuilder that automatically resolves executable paths
 * and enriches the child process PATH so binaries installed via nvm, Homebrew,
 * or user-level npm are found even when the app was launched outside a login shell.
 *
 * This is particularly important on macOS when running apps from ~/Applications,
 * as they don't inherit the shell's PATH environment variable.
 *
 * Cross-platform PATH enrichment strategy:
 * - macOS / Linux: ask the user's login shell (`$SHELL -l -c "echo $PATH"`) to
 *   resolve all shell-profile additions (nvm, pyenv, Homebrew, etc.), then
 *   append a set of well-known static fallback directories.
 * - Windows: append common Node / npm global bin locations under APPDATA and Program Files.
 */
class ProcessBuilderExt(vararg command: String) {
    constructor(command: List<String>) : this(*command.toTypedArray())

    private val processBuilder: ProcessBuilder

    init {
        val resolvedCommand = resolveCommand(command.toList())
        processBuilder = ProcessBuilder(resolvedCommand)
        processBuilder.environment()["PATH"] = enrichedPath(processBuilder.environment()["PATH"] ?: "")
    }

    /**
     * Delegates to the underlying ProcessBuilder.
     */
    fun redirectErrorStream(redirect: Boolean): ProcessBuilderExt {
        processBuilder.redirectErrorStream(redirect)
        return this
    }

    /**
     * Delegates to the underlying ProcessBuilder.
     */
    fun directory(directory: File?): ProcessBuilderExt {
        processBuilder.directory(directory)
        return this
    }

    /**
     * Delegates to the underlying ProcessBuilder.
     */
    fun environment(): MutableMap<String, String> = processBuilder.environment()

    /**
     * Delegates to the underlying ProcessBuilder.
     */
    fun command(): MutableList<String> = processBuilder.command()

    /**
     * Delegates to the underlying ProcessBuilder.
     */
    fun command(vararg command: String): ProcessBuilderExt {
        processBuilder.command(resolveCommand(command.toList()))
        return this
    }

    /**
     * Delegates to the underlying ProcessBuilder.
     */
    fun command(command: List<String>): ProcessBuilderExt {
        processBuilder.command(resolveCommand(command))
        return this
    }

    /**
     * Starts the process.
     */
    fun start(): Process = processBuilder.start()

    companion object {

        /**
         * Resolves [executableName] to an absolute path on the current `PATH`.
         *
         * @return The absolute path, or `null` if [executableName] can't be located.
         */
        fun which(executableName: String): String? {
            val resolved = findExecutable(executableName)
            val file = File(resolved)
            return if (file.isAbsolute && file.exists() && !file.isDirectory) resolved else null
        }

        fun resolveCommand(command: List<String>): List<String> {
            if (command.isEmpty()) return command
            val resolved = findExecutable(command[0])
            return listOf(resolved) + command.drop(1)
        }

        /**
         * Returns an enriched PATH string that includes the login shell's PATH (on Unix/macOS)
         * plus well-known static fallback directories (nvm, Homebrew, user-local bin, etc.).
         *
         * Useful for injecting into child processes that would otherwise inherit the app's
         * limited PATH (e.g. MCP stdio transports, script runners).
         *
         * @param currentPath  Baseline PATH to augment; defaults to the current process PATH.
         */
        fun enrichedPath(currentPath: String = System.getenv("PATH") ?: ""): String {
            val separator = File.pathSeparator
            val extra = if (isWindows()) windowsExtraPaths() else unixExtraPaths()
            val shellPath = if (isWindows()) resolveWindowsRegistryPath() else resolveShellPath()
            val existing = (shellPath ?: currentPath).split(separator)
            return (existing + extra)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(separator)
        }

        private fun findExecutable(executableName: String): String {
            // Already an absolute path
            File(executableName).let {
                if (it.isAbsolute && it.exists() && it.canExecute()) return executableName
            }

            val windowsExtensions = if (isWindows()) listOf(".exe", ".cmd", ".bat", ".com") else listOf("")

            val windowsBasePaths = if (isWindows()) {
                val localAppData = System.getenv("LOCALAPPDATA")
                val appData = System.getenv("APPDATA")
                listOfNotNull(
                    System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)"),
                    localAppData,
                    (System.getenv("windir") ?: "C:\\Windows") + "\\System32",
                    // Common per-tool installer layout: %LOCALAPPDATA%\<tool>\bin\<tool>.exe
                    // (e.g. `agy`, several Node-based CLI installers).
                    localAppData?.let { "$it\\$executableName\\bin" },
                    localAppData?.let { "$it\\Programs\\$executableName" },
                    appData?.let { "$it\\$executableName\\bin" },
                )
            } else {
                emptyList()
            }

            val commonPaths = listOf(
                "/usr/local/bin",
                "/opt/homebrew/bin",
                "/usr/bin",
                "/bin",
                "/opt/local/bin",
                System.getProperty("user.home") + "/.local/bin",
            )

            val allPaths = if (isWindows()) {
                (windowsBasePaths + commonPaths).flatMap { base ->
                    windowsExtensions.map { ext -> "$base\\$executableName$ext" }
                }
            } else {
                commonPaths.map { "$it/$executableName" }
            }

            allPaths.firstOrNull { File(it).let { f -> f.exists() && !f.isDirectory } }
                ?.let { return it }

            // On Windows, the JVM (and any child process it spawns, e.g. `where`/`Get-Command`)
            // inherits the PATH that was current when this process was launched. If the user
            // installed something afterwards (e.g. via an installer that updates the persisted
            // User/Machine PATH in the registry), that inherited PATH is stale and neither
            // `where` nor `Get-Command` — which both search the *inherited* PATH of the child
            // process — will find it. So look up the live registry-persisted PATH directly and
            // search it ourselves before falling back to shell tools.
            if (isWindows()) {
                val registryDirs = windowsRegistryPathDirs()
                val fromRegistry = registryDirs.flatMap { base ->
                    windowsExtensions.map { ext -> "$base\\$executableName$ext" }
                }.firstOrNull { File(it).let { f -> f.exists() && !f.isDirectory } }
                if (fromRegistry != null) return fromRegistry
            }

            // Shell fallback
            val resolvedPath = resolveViaShell(executableName)

            if (resolvedPath != null && isWindows()) {
                for (ext in listOf(".exe", ".cmd", ".bat", ".com", "")) {
                    val candidate = if (ext.isEmpty()) {
                        resolvedPath
                    } else if (resolvedPath.endsWith(ext, ignoreCase = true)) {
                        resolvedPath
                    } else {
                        val dotIndex = resolvedPath.lastIndexOf('.')
                        val lastSlash = maxOf(resolvedPath.lastIndexOf('\\'), resolvedPath.lastIndexOf('/'))
                        if (dotIndex > lastSlash) {
                            resolvedPath.substring(0, dotIndex) + ext
                        } else {
                            resolvedPath + ext
                        }
                    }
                    val f = File(candidate)
                    if (f.exists() && !f.isDirectory) return candidate
                }
            }

            return resolvedPath ?: executableName
        }

        private fun resolveViaShell(executableName: String): String? = if (isWindows()) {
            runShellCommand(listOf("cmd.exe", "/c", "where", executableName))
                ?: runShellCommand(
                    listOf(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "(Get-Command -Name '$executableName' -ErrorAction SilentlyContinue | " +
                            "Select-Object -First 1 -ExpandProperty Source)",
                    ),
                )
        } else {
            runShellCommand(listOf("/bin/sh", "-c", "which $executableName"))
        }

        /**
         * Runs [command] with a [TIMEOUT_SECONDS] deadline and returns the first non-blank
         * line of stdout.
         *
         * @return The first non-blank stdout line, or `null` if the process times out, exits
         *         non-zero, or produces no output.
         */
        private fun runShellCommand(command: List<String>): String? = try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText().trim() }
            }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            val output = try {
                outputFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            if (process.exitValue() == 0 && !output.isNullOrBlank()) {
                output.lines().map { it.trim() }.firstOrNull { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        /** Extra PATH entries on macOS / Linux (nvm bins + well-known install dirs). */
        private fun unixExtraPaths(): List<String> {
            val home = System.getProperty("user.home")
            val nvmBase = File("$home/.nvm/versions/node")
            val nvmBins = if (nvmBase.isDirectory) {
                nvmBase.listFiles()
                    ?.sortedDescending() // newest version first
                    ?.map { "${it.absolutePath}/bin" }
                    ?: emptyList()
            } else {
                emptyList()
            }
            return nvmBins + listOf(
                "/usr/local/bin",
                "/opt/homebrew/bin", // Apple Silicon Homebrew
                "/opt/homebrew/sbin",
                "/usr/bin",
                "/bin",
                "/opt/local/bin", // MacPorts
                "/usr/local/lib/node_modules/.bin",
                "/usr/lib/node_modules/.bin",
                "$home/.npm-global/bin",
                "$home/.local/bin",
                "$home/bin",
            )
        }

        /** Extra PATH entries on Windows (npm global, Node install dirs). */
        private fun windowsExtraPaths(): List<String> {
            val home = System.getProperty("user.home")
            val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
            return listOf(
                "$appData\\npm",
                "$home\\AppData\\Local\\Programs\\node",
                "$programFiles\\nodejs",
                "$programFilesX86\\nodejs",
                "C:\\Program Files\\nodejs",
            )
        }

        /**
         * Asks the user's login shell for its PATH, with a [TIMEOUT_SECONDS] deadline.
         *
         * @return The shell's PATH, or `null` on failure, timeout, or an empty result.
         */
        private fun resolveShellPath(): String? = runCatching {
            val shell = System.getenv("SHELL") ?: "/bin/zsh"
            val proc = ProcessBuilder(shell, "-l", "-c", "echo \$PATH")
                .redirectErrorStream(true)
                .start()
            val outputFuture = CompletableFuture.supplyAsync {
                proc.inputStream.bufferedReader().use { it.readText().trim() }
            }
            val ok = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!ok) {
                proc.destroyForcibly()
                return@runCatching null
            }
            val path = try {
                outputFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            path.takeIf { !it.isNullOrBlank() }
        }.getOrNull()

        /**
         * Reads the live, registry-persisted User + Machine PATH values via a fresh
         * PowerShell process, so entries added by installers after this JVM started
         * are picked up. Cached after the first successful call.
         *
         * @return The combined User + Machine PATH, or `null` if unavailable.
         */
        private val windowsRegistryPathCache: String? by lazy {
            if (!isWindows()) {
                null
            } else {
                parseRegistryPath(
                    runShellCommand(
                        listOf(
                            "powershell.exe",
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "[Environment]::GetEnvironmentVariable('PATH','User') + ';' + " +
                                "[Environment]::GetEnvironmentVariable('PATH','Machine')",
                        ),
                    ),
                )
            }
        }

        /**
         * Parses a `;`-joined PATH string (Windows registry `PATH` values are always
         * `;`-delimited, regardless of the host OS this happens to run on — unlike
         * [File.pathSeparator], which is `:` on Unix) into a clean, re-joined path,
         * dropping blank entries.
         *
         * @return The cleaned, `;`-joined PATH, or `null` if [raw] contains no non-blank
         *         directory entries.
         */
        fun parseRegistryPath(raw: String?): String? = raw
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(";")

        private fun resolveWindowsRegistryPath(): String? = windowsRegistryPathCache

        private fun windowsRegistryPathDirs(): List<String> = windowsRegistryPathCache
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("windows")

        /** Max time to wait for a shell fallback command (`where`, `which`, PowerShell, etc.). */
        private const val TIMEOUT_SECONDS = 5L
    }
}
