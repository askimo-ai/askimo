/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.config.AppConfig
import io.askimo.core.config.ProjectType
import io.askimo.core.logging.logger
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Manages and applies multiple indexing filters in priority order.
 */
class FilterChain(filters: List<IndexingFilter>) {

    private val sortedFilters = filters.sortedBy { it.priority }

    // Cache detected project types per root path
    private val projectTypeCache = mutableMapOf<Path, List<ProjectType>>()

    /**
     * Check if a path should be excluded by any filter.
     * Returns immediately on first exclusion (short-circuit evaluation).
     */
    fun shouldExclude(path: Path, isDirectory: Boolean, context: FilterContext): Boolean {
        for (filter in sortedFilters) {
            if (filter.shouldExclude(path, isDirectory, context)) {
                log.trace("Path excluded by ${filter.name} filter: ${context.relativePath}")
                return true
            }
        }
        return false
    }

    /**
     * Convenience method to check if a path should be excluded.
     * Automatically creates the FilterContext from the path.
     * For proper project type detection, use shouldExclude with explicit rootPath.
     */
    fun shouldExcludePath(path: Path): Boolean {
        val isDirectory = path.isDirectory()
        val absolutePath = path.toAbsolutePath()

        // Try to find project root by looking upward for marker files
        val rootPath = findProjectRoot(absolutePath) ?: absolutePath

        val relativePath = if (rootPath != absolutePath) {
            rootPath.relativize(absolutePath).toString()
        } else {
            path.fileName?.toString() ?: path.toString()
        }

        // Detect project types for this root path (cached)
        val projectTypes = detectProjectTypes(rootPath)

        val context = FilterContext(
            rootPath = rootPath,
            relativePath = relativePath,
            fileName = path.name,
            extension = if (!isDirectory) path.extension.lowercase() else "",
            projectTypes = projectTypes,
        )

        return shouldExclude(path, isDirectory, context)
    }

    /**
     * Check if a path should be excluded, with explicit project root.
     * This is preferred over shouldExcludePath when you know the project root.
     */
    fun shouldExclude(path: Path, rootPath: Path): Boolean {
        val isDirectory = path.isDirectory()
        val absolutePath = path.toAbsolutePath()
        val absoluteRoot = rootPath.toAbsolutePath()

        val relativePath = try {
            if (absolutePath.startsWith(absoluteRoot)) {
                absoluteRoot.relativize(absolutePath).toString()
            } else {
                path.fileName.toString()
            }
        } catch (_: Exception) {
            path.fileName?.toString() ?: path.toString()
        }

        // Detect project types for this root path (cached)
        val projectTypes = detectProjectTypes(absoluteRoot)

        val context = FilterContext(
            rootPath = absoluteRoot,
            relativePath = relativePath,
            fileName = path.name,
            extension = if (!isDirectory) path.extension.lowercase() else "",
            projectTypes = projectTypes,
        )

        return shouldExclude(path, isDirectory, context)
    }

    /**
     * Find the CLOSEST project root by walking up the directory tree looking for project markers.
     * This is critical for monorepo support - we want the nearest project root, not the first one found.
     * Returns null if no project root is found.
     */
    private fun findProjectRoot(path: Path): Path? {
        var current = if (path.isDirectory()) path else path.parent

        while (current != null) {
            // Check if this directory has any project markers
            if (hasProjectMarkers(current)) {
                return current
            }

            current = current.parent
        }

        return null
    }

    /**
     * Check if a directory contains any project type markers.
     * This includes both specific project markers (build.gradle.kts, package.json, etc.)
     * and .git as a fallback indicator.
     */
    private fun hasProjectMarkers(dir: Path): Boolean {
        // Check for specific project type markers
        for (projectType in AppConfig.indexing.projectTypes) {
            for (marker in projectType.markers) {
                val markerExists = if (marker.contains("*")) {
                    // Handle wildcard patterns like *.csproj
                    val pattern = marker.replace("*", "")
                    dir.toFile().listFiles()?.any { it.name.endsWith(pattern) } == true
                } else {
                    dir.resolve(marker).exists()
                }

                if (markerExists) {
                    return true
                }
            }
        }

        // Also check for .git directory as a fallback
        if (dir.resolve(".git").exists()) {
            return true
        }

        return false
    }

    /**
     * Find all project roots within a directory tree.
     * Useful for monorepo scenarios where you want to discover all subprojects.
     *
     * @param rootDir The root directory to search
     * @param maxDepth Maximum depth to search (default 5 to avoid deep recursion)
     * @param includeRoot Whether to include the root directory itself if it has markers (default true)
     * @return List of paths that contain project markers
     */
    fun findAllProjectRoots(rootDir: Path, maxDepth: Int = 5, includeRoot: Boolean = true): List<Path> {
        val projectRoots = mutableListOf<Path>()

        fun searchRecursive(dir: Path, currentDepth: Int, isRootDir: Boolean) {
            if (currentDepth > maxDepth) return
            if (!dir.isDirectory()) return

            val hasMarkers = hasProjectMarkers(dir)

            // Add this directory if it has markers
            if (hasMarkers && (includeRoot || !isRootDir)) {
                projectRoots.add(dir)
            }

            // Continue searching subdirectories even if this is a project root
            // (to support nested projects in monorepos)
            // But skip if we're at max depth or this looks like a build artifact
            if (currentDepth < maxDepth) {
                try {
                    dir.toFile().listFiles()?.forEach { child ->
                        if (child.isDirectory && !shouldSkipDirectory(child.name)) {
                            searchRecursive(child.toPath(), currentDepth + 1, false)
                        }
                    }
                } catch (e: Exception) {
                    log.debug("Failed to search directory $dir: ${e.message}")
                }
            }
        }

        searchRecursive(rootDir, 0, true)
        return projectRoots
    }

    /**
     * Quick check to skip obviously non-project directories during search.
     */
    private fun shouldSkipDirectory(name: String): Boolean = name in setOf(
        "node_modules", "target", "build", "dist", ".git", ".gradle",
        "__pycache__", "venv", ".venv", "vendor", "bin", "obj",
    )

    /**
     * Detect project types in the given root path based on marker files.
     * Results are cached per root path for performance.
     */
    private fun detectProjectTypes(rootPath: Path): List<ProjectType> = projectTypeCache.getOrPut(rootPath) {
        val detectedTypes = mutableListOf<ProjectType>()

        for (projectType in AppConfig.indexing.projectTypes) {
            for (marker in projectType.markers) {
                // Check if marker file exists in root path
                val markerPath = if (marker.contains("*")) {
                    // Handle wildcard patterns like *.csproj
                    val pattern = marker.replace("*", "")
                    rootPath.toFile().listFiles()?.any { it.name.endsWith(pattern) } == true
                } else {
                    rootPath.resolve(marker).exists()
                }

                if (markerPath) {
                    detectedTypes.add(projectType)
                    log.debug("Detected project type '${projectType.name}' in $rootPath")
                    break
                }
            }
        }

        if (detectedTypes.isEmpty()) {
            log.debug("No specific project type detected in $rootPath, using common excludes only")
        }

        detectedTypes
    }

    companion object {
        private val log = logger<FilterChain>()

        /**
         * Default filter chain with all standard filters.
         * No project root needed - filters detect their context per-path.
         */
        val DEFAULT: FilterChain by lazy {
            val filters = listOf(
                GitignoreFilter(),
                ProjectTypeFilter(),
                BinaryFileFilter(),
                FileSizeFilter(),
                CustomPatternFilter(emptyList()),
            )
            FilterChain(filters)
        }

        /**
         * Filter chain for local files indexing.
         * Only checks if file extension is supported, without project-based filters.
         * Use this when indexing user-selected files rather than entire folders.
         */
        val LOCAL_FILES: FilterChain by lazy {
            val filters = listOf(
                SupportedExtensionFilter(),
                FileSizeFilter(),
            )
            FilterChain(filters)
        }
    }
}
