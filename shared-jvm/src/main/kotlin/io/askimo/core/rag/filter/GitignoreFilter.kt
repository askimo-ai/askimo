/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.logging.logger
import io.askimo.core.util.ProcessBuilderExt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Parses and applies .gitignore patterns for file filtering.
 */
class GitignoreParser(private val rootPath: Path) {
    private val log = logger<GitignoreParser>()
    private val patterns = mutableListOf<GitignorePattern>()

    data class GitignorePattern(
        val pattern: String,
        val isNegation: Boolean,
        val isDirectoryOnly: Boolean,
        val regex: Regex,
        val gitignorePath: Path,
    )

    init {
        loadGlobalGitignore()
        loadGitignoreFiles(rootPath)
    }

    /**
     * Load global gitignore patterns from standard locations:
     * 1. Git config core.excludesfile
     * 2. ~/.config/git/ignore (XDG standard)
     * 3. ~/.gitignore_global (common convention)
     */
    private fun loadGlobalGitignore() {
        val globalGitignorePaths = mutableListOf<Path>()

        // Try to get from git config
        try {
            val process = ProcessBuilderExt("git", "config", "--global", "core.excludesfile")
                .redirectErrorStream(true)
                .start()

            val configPath = process.inputStream.bufferedReader().readText().trim()
            if (configPath.isNotEmpty()) {
                val expandedPath = if (configPath.startsWith("~/")) {
                    Path.of(System.getProperty("user.home"), configPath.substring(2))
                } else {
                    Path.of(configPath)
                }
                if (Files.exists(expandedPath)) {
                    globalGitignorePaths.add(expandedPath)
                }
            }
        } catch (e: Exception) {
            log.debug("Could not read git config for global excludesfile: ${e.message}")
        }

        // Add standard locations if they exist
        val userHome = System.getProperty("user.home")
        listOf(
            Path.of(userHome, ".config", "git", "ignore"),
            Path.of(userHome, ".gitignore_global"),
            Path.of(userHome, ".gitignore"),
        ).forEach { path ->
            if (Files.exists(path) && !globalGitignorePaths.contains(path)) {
                globalGitignorePaths.add(path)
            }
        }

        // Load patterns from all found global gitignore files
        globalGitignorePaths.forEach { gitignoreFile ->
            try {
                Files.readAllLines(gitignoreFile)
                    .map { it.trim() }
                    .filterNot { it.isEmpty() || it.startsWith("#") }
                    .forEach { line ->
                        // Global gitignore patterns apply from the root
                        patterns.add(parsePattern(line, rootPath))
                    }
                log.debug("Loaded global .gitignore: $gitignoreFile")
            } catch (e: Exception) {
                log.warn("Failed to load global .gitignore: $gitignoreFile", e)
            }
        }
    }

    private fun loadGitignoreFiles(path: Path) {
        try {
            Files.walk(path)
                .asSequence()
                .filter { it.fileName.toString() == ".gitignore" }
                .forEach { gitignoreFile ->
                    try {
                        val gitignoreDir = gitignoreFile.parent
                        Files.readAllLines(gitignoreFile)
                            .map { it.trim() }
                            .filterNot { it.isEmpty() || it.startsWith("#") }
                            .forEach { line ->
                                patterns.add(parsePattern(line, gitignoreDir))
                            }
                        log.debug("Loaded .gitignore: $gitignoreFile (${patterns.size} patterns)")
                    } catch (e: Exception) {
                        log.warn("Failed to load .gitignore: $gitignoreFile", e)
                    }
                }
        } catch (e: Exception) {
            log.warn("Failed to walk directory tree for .gitignore files", e)
        }
    }

    private fun parsePattern(line: String, gitignoreDir: Path): GitignorePattern {
        var pattern = line
        val isNegation = pattern.startsWith("!")
        if (isNegation) pattern = pattern.substring(1)

        val isDirectoryOnly = pattern.endsWith("/")
        if (isDirectoryOnly) pattern = pattern.removeSuffix("/")

        // Check for ** at the beginning
        val hasLeadingDoubleAsterisk = pattern.startsWith("**/")
        if (hasLeadingDoubleAsterisk) {
            pattern = pattern.substring(3) // Remove **/ prefix
        }

        // Convert .gitignore pattern to regex
        val regexPattern = buildString {
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '*' -> {
                        if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                            // ** in middle or end - matches any number of directories
                            append(".*")
                            i++ // Skip next *
                        } else {
                            // * matches anything except /
                            append("[^/]*")
                        }
                    }

                    '?' -> append("[^/]")

                    '.' -> append("\\.")

                    '/' -> append("/")

                    else -> append(Regex.escape(c.toString()))
                }
                i++
            }
        }

        // Anchor pattern appropriately
        val finalPattern = when {
            // Pattern started with **/ - match at any level
            hasLeadingDoubleAsterisk -> "(^|.*/)$regexPattern(/.*|\$)"

            // Pattern starts with / - anchored to root (relative to .gitignore location)
            line.startsWith("/") -> "^${regexPattern.substring(1)}(/.*|\$)"

            // Pattern contains / - match from current directory down
            pattern.contains("/") -> "^$regexPattern(/.*|\$)"

            // Pattern contains wildcards (* or ?) - match at any level
            pattern.contains("*") || pattern.contains("?") -> "(^|.*/)$regexPattern(/.*|\$)"

            // Simple literal pattern without / or wildcards - match only at the .gitignore directory level
            else -> "^$regexPattern(/.*|\$)"
        }

        val gitignorePattern = GitignorePattern(
            pattern = line,
            isNegation = isNegation,
            isDirectoryOnly = isDirectoryOnly,
            regex = Regex(finalPattern),
            gitignorePath = gitignoreDir,
        )

        log.trace("Parsed pattern: '$line' -> regex: '$finalPattern' (negation=$isNegation, dirOnly=$isDirectoryOnly)")

        return gitignorePattern
    }

    /**
     * Check if a path should be ignored according to .gitignore rules.
     */
    fun shouldIgnore(path: Path, isDirectory: Boolean): Boolean {
        val absoluteRelativePath = try {
            rootPath.relativize(path).toString().replace('\\', '/')
        } catch (_: Exception) {
            return false
        }

        var ignored = false
        var matchedPattern: GitignorePattern? = null

        // Apply patterns in order (later patterns override earlier ones)
        for (pattern in patterns) {
            // Get the directory where this .gitignore is located (relative to root)
            val patternDir = try {
                rootPath.relativize(pattern.gitignorePath).toString().replace('\\', '/')
            } catch (_: Exception) {
                ""
            }

            // Skip if this .gitignore doesn't apply to the path
            // (path must be within or under the .gitignore's directory)
            if (patternDir.isNotEmpty() && !absoluteRelativePath.startsWith("$patternDir/") && absoluteRelativePath != patternDir) {
                continue
            }

            // Make path relative to the .gitignore file's directory
            val pathRelativeToGitignore = if (patternDir.isEmpty()) {
                absoluteRelativePath
            } else {
                absoluteRelativePath.removePrefix("$patternDir/")
            }

            // Check if pattern matches the path relative to .gitignore location
            val matches = pattern.regex.matches(pathRelativeToGitignore)

            if (matches) {
                // Directory-only patterns only match directories
                if (pattern.isDirectoryOnly && !isDirectory) {
                    continue
                }

                ignored = if (pattern.isNegation) {
                    false // Negation pattern un-ignores the file
                } else {
                    true // Normal pattern ignores the file
                }
                matchedPattern = pattern
            }
        }

        if (ignored && matchedPattern != null) {
            log.trace("Path excluded by gitignore filter: $absoluteRelativePath (pattern: '${matchedPattern.pattern}', regex: '${matchedPattern.regex.pattern}')")
        }

        return ignored
    }

    fun hasPatterns(): Boolean = patterns.isNotEmpty()
}

/**
 * Filter based on .gitignore patterns.
 * Automatically discovers and applies .gitignore rules from Git repositories.
 * Detects Git repository for each path dynamically - supports multiple unrelated paths.
 */
class GitignoreFilter : IndexingFilter {
    override val name = "gitignore"
    override val priority = 10 // High priority - respect user's ignore rules first

    private val log = logger<GitignoreFilter>()

    // Cache of git root -> parser to avoid re-parsing .gitignore files
    private val parserCache = mutableMapOf<Path, GitignoreParser?>()

    /**
     * Find the Git repository root for a given path by walking up the directory tree
     */
    private fun findGitRoot(path: Path): Path? = generateSequence(path.toAbsolutePath()) { it.parent }
        .firstOrNull { candidate ->
            val gitDir = candidate.resolve(".git")
            Files.exists(gitDir) && Files.isDirectory(gitDir)
        }

    /**
     * Get or create a GitignoreParser for the given Git repository root
     */
    private fun getParserForGitRoot(gitRoot: Path): GitignoreParser? = parserCache.getOrPut(gitRoot) {
        try {
            GitignoreParser(gitRoot).takeIf { it.hasPatterns() }
        } catch (e: Exception) {
            log.warn("Failed to parse .gitignore for $gitRoot: ${e.message}")
            null
        }
    }

    override fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean {
        // Try to find Git repository root for this path
        val gitRoot = findGitRoot(if (isDirectory) path else path.parent ?: path)
            ?: return false // Not in a Git repository, don't exclude

        // Get parser for this Git repository
        val parser = getParserForGitRoot(gitRoot)
            ?: return false // No .gitignore patterns found

        // Check if path should be ignored
        return parser.shouldIgnore(path, isDirectory)
    }
}
