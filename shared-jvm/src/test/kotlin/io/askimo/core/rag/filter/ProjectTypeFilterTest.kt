/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.config.ProjectType
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@AskimoTestHome
class ProjectTypeFilterTest {

    @Test
    fun `should exclude common build directories with project types`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // build/ is excluded by Node.js, Gradle, Python project types
        val buildDir = tempDir.resolve("build")
        Files.createDirectory(buildDir)

        // Create a context with Node.js project type
        val nodeProjectType = ProjectType(
            name = "Node.js",
            markers = setOf("package.json"),
            excludePaths = setOf("node_modules/", "dist/", "build/"),
        )

        val contextWithProject = FilterContext(
            rootPath = tempDir,
            relativePath = "build",
            fileName = "build",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )

        assertTrue(filter.shouldExclude(buildDir, true, contextWithProject))

        // dist/ is also in Node.js project type
        val distDir = tempDir.resolve("dist")
        Files.createDirectory(distDir)

        val distContext = FilterContext(
            rootPath = tempDir,
            relativePath = "dist",
            fileName = "dist",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )

        assertTrue(filter.shouldExclude(distDir, true, distContext))

        // target/ is in Maven/Rust project types
        val mavenProjectType = ProjectType(
            name = "Maven",
            markers = setOf("pom.xml"),
            excludePaths = setOf("target/", ".mvn/"),
        )

        val targetDir = tempDir.resolve("target")
        Files.createDirectory(targetDir)

        val targetContext = FilterContext(
            rootPath = tempDir,
            relativePath = "target",
            fileName = "target",
            extension = "",
            projectTypes = listOf(mavenProjectType),
        )

        assertTrue(filter.shouldExclude(targetDir, true, targetContext))
    }

    @Test
    fun `should exclude node_modules directory with Node project type`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val nodeProjectType = ProjectType(
            name = "Node.js",
            markers = setOf("package.json"),
            excludePaths = setOf("node_modules/", "dist/", "build/"),
        )

        // Root level node_modules
        val rootNodeModules = tempDir.resolve("node_modules")
        Files.createDirectory(rootNodeModules)

        val rootContext = FilterContext(
            rootPath = tempDir,
            relativePath = "node_modules",
            fileName = "node_modules",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )
        assertTrue(filter.shouldExclude(rootNodeModules, true, rootContext))

        // Nested node_modules
        val nestedNodeModules = tempDir.resolve("src/vendor/node_modules")
        Files.createDirectories(nestedNodeModules)

        val nestedContext = FilterContext(
            rootPath = tempDir,
            relativePath = "src/vendor/node_modules",
            fileName = "node_modules",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )
        assertTrue(filter.shouldExclude(nestedNodeModules, true, nestedContext))

        // node_modules file (should not exclude - it's a directory pattern)
        val nodeModulesFile = tempDir.resolve("node_modules.txt")
        Files.createFile(nodeModulesFile)

        val fileContext = FilterContext(
            rootPath = tempDir,
            relativePath = "node_modules.txt",
            fileName = "node_modules.txt",
            extension = "txt",
            projectTypes = listOf(nodeProjectType),
        )
        assertFalse(filter.shouldExclude(nodeModulesFile, false, fileContext))
    }

    @Test
    fun `should exclude IDE directories from commonExcludes`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // .idea/ and .vscode/ are in commonExcludes
        val ideaDir = tempDir.resolve(".idea")
        Files.createDirectory(ideaDir)
        assertTrue(filter.shouldExclude(ideaDir, true, createContext(ideaDir, tempDir)))

        val vscodeDir = tempDir.resolve(".vscode")
        Files.createDirectory(vscodeDir)
        assertTrue(filter.shouldExclude(vscodeDir, true, createContext(vscodeDir, tempDir)))
    }

    @Test
    fun `should exclude VCS directories from commonExcludes`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // .git/, .svn/, .hg/ are in commonExcludes
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)
        assertTrue(filter.shouldExclude(gitDir, true, createContext(gitDir, tempDir)))

        val svnDir = tempDir.resolve(".svn")
        Files.createDirectory(svnDir)
        assertTrue(filter.shouldExclude(svnDir, true, createContext(svnDir, tempDir)))

        val hgDir = tempDir.resolve(".hg")
        Files.createDirectory(hgDir)
        assertTrue(filter.shouldExclude(hgDir, true, createContext(hgDir, tempDir)))
    }

    @Test
    fun `should not exclude source files`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val kotlinFile = tempDir.resolve("Main.kt")
        Files.createFile(kotlinFile)
        assertFalse(filter.shouldExclude(kotlinFile, false, createContext(kotlinFile, tempDir)))

        val javaFile = tempDir.resolve("App.java")
        Files.createFile(javaFile)
        assertFalse(filter.shouldExclude(javaFile, false, createContext(javaFile, tempDir)))

        val pythonFile = tempDir.resolve("script.py")
        Files.createFile(pythonFile)
        assertFalse(filter.shouldExclude(pythonFile, false, createContext(pythonFile, tempDir)))

        val jsFile = tempDir.resolve("app.js")
        Files.createFile(jsFile)
        assertFalse(filter.shouldExclude(jsFile, false, createContext(jsFile, tempDir)))
    }

    @Test
    fun `should not exclude documentation files`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val readme = tempDir.resolve("README.md")
        Files.createFile(readme)
        assertFalse(filter.shouldExclude(readme, false, createContext(readme, tempDir)))

        val contributing = tempDir.resolve("CONTRIBUTING.md")
        Files.createFile(contributing)
        assertFalse(filter.shouldExclude(contributing, false, createContext(contributing, tempDir)))

        val license = tempDir.resolve("LICENSE")
        Files.createFile(license)
        assertFalse(filter.shouldExclude(license, false, createContext(license, tempDir)))
    }

    @Test
    fun `should exclude directories at nested levels`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val gradleProjectType = ProjectType(
            name = "Gradle",
            markers = setOf("build.gradle"),
            excludePaths = setOf("build/", ".gradle/", "out/", "bin/"),
        )

        // Test build directory at nested level
        val nestedBuild = tempDir.resolve("src/main/build")
        Files.createDirectories(nestedBuild)

        val buildContext = FilterContext(
            rootPath = tempDir,
            relativePath = "src/main/build",
            fileName = "build",
            extension = "",
            projectTypes = listOf(gradleProjectType),
        )
        assertTrue(filter.shouldExclude(nestedBuild, true, buildContext))

        // Test .gradle directory at nested level
        val nestedGradle = tempDir.resolve("project/.gradle")
        Files.createDirectories(nestedGradle)

        val gradleContext = FilterContext(
            rootPath = tempDir,
            relativePath = "project/.gradle",
            fileName = ".gradle",
            extension = "",
            projectTypes = listOf(gradleProjectType),
        )
        assertTrue(filter.shouldExclude(nestedGradle, true, gradleContext))
    }

    @Test
    fun `should handle directory pattern matching correctly`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val nodeProjectType = ProjectType(
            name = "Node.js",
            markers = setOf("package.json"),
            excludePaths = setOf("build/", "dist/"),
        )

        // Create directories that end with common exclude patterns
        val customBuildDir = tempDir.resolve("my-custom-build")
        Files.createDirectory(customBuildDir)

        val customContext = FilterContext(
            rootPath = tempDir,
            relativePath = "my-custom-build",
            fileName = "my-custom-build",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )
        // Should NOT exclude - "my-custom-build" != "build"
        assertFalse(filter.shouldExclude(customBuildDir, true, customContext))

        // Create exact match
        val buildDir = tempDir.resolve("build")
        Files.createDirectory(buildDir)

        val buildContext = FilterContext(
            rootPath = tempDir,
            relativePath = "build",
            fileName = "build",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )
        assertTrue(filter.shouldExclude(buildDir, true, buildContext))
    }

    @Test
    fun `should handle files with same name as directory patterns`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val nodeProjectType = ProjectType(
            name = "Node.js",
            markers = setOf("package.json"),
            excludePaths = setOf("build/"),
        )

        // File named "build" should not be excluded if pattern is "build/"
        val buildFile = tempDir.resolve("build")
        Files.createFile(buildFile)

        val context = FilterContext(
            rootPath = tempDir,
            relativePath = "build",
            fileName = "build",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )

        // Directory patterns should only match directories
        assertFalse(filter.shouldExclude(buildFile, false, context))
    }

    @Test
    fun `should handle relative paths correctly`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // Test with Windows-style path separators
        val nestedFile = tempDir.resolve("src/main/resources/config.properties")
        Files.createDirectories(nestedFile.parent)
        Files.createFile(nestedFile)

        val context = createContext(nestedFile, tempDir)
        // Should handle both / and \ path separators
        assertFalse(filter.shouldExclude(nestedFile, false, context))
    }

    @Test
    fun `should handle project type specific excludes`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // Test with a custom project type that excludes node_modules
        val nodeModules = tempDir.resolve("node_modules")
        Files.createDirectory(nodeModules)

        val customProjectType = ProjectType(
            name = "JavaScript",
            markers = setOf("package.json", "node_modules"),
            excludePaths = setOf("node_modules/", "dist/"),
        )

        val jsProjectContext = FilterContext(
            rootPath = tempDir,
            relativePath = "node_modules",
            fileName = "node_modules",
            extension = "",
            projectTypes = listOf(customProjectType),
        )

        assertTrue(filter.shouldExclude(nodeModules, true, jsProjectContext))
    }

    @Test
    fun `should handle Python project specific excludes`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // Python-specific directories
        val pycacheDir = tempDir.resolve("__pycache__")
        Files.createDirectory(pycacheDir)

        val customProjectType = ProjectType(
            name = "Python",
            markers = setOf("requirements.txt", "setup.py"),
            excludePaths = setOf("__pycache__/", ".pytest_cache/", "*.pyc"),
        )

        val pythonContext = FilterContext(
            rootPath = tempDir,
            relativePath = "__pycache__",
            fileName = "__pycache__",
            extension = "",
            projectTypes = listOf(customProjectType),
        )

        assertTrue(filter.shouldExclude(pycacheDir, true, pythonContext))
    }

    @Test
    fun `should handle Java project specific excludes`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // Java-specific directories
        val gradleDir = tempDir.resolve(".gradle")
        Files.createDirectory(gradleDir)

        val customProjectType = ProjectType(
            name = "Java",
            markers = setOf("build.gradle", "pom.xml"),
            excludePaths = setOf(".gradle/", "target/", "*.class"),
        )

        val javaContext = FilterContext(
            rootPath = tempDir,
            relativePath = ".gradle",
            fileName = ".gradle",
            extension = "",
            projectTypes = listOf(customProjectType),
        )

        assertTrue(filter.shouldExclude(gradleDir, true, javaContext))
    }

    @Test
    fun `should exclude cache and temp directories with project types`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // tmp/ is in Ruby project type
        val rubyProjectType = ProjectType(
            name = "Ruby",
            markers = setOf("Gemfile"),
            excludePaths = setOf("vendor/", ".bundle/", "tmp/", "log/"),
        )

        val tmpDir = tempDir.resolve("tmp")
        Files.createDirectory(tmpDir)

        val tmpContext = FilterContext(
            rootPath = tempDir,
            relativePath = "tmp",
            fileName = "tmp",
            extension = "",
            projectTypes = listOf(rubyProjectType),
        )
        assertTrue(filter.shouldExclude(tmpDir, true, tmpContext))

        // .cache/ is in Node.js project type
        val nodeProjectType = ProjectType(
            name = "Node.js",
            markers = setOf("package.json"),
            excludePaths = setOf("node_modules/", ".cache/", "coverage/"),
        )

        val cacheDir = tempDir.resolve(".cache")
        Files.createDirectory(cacheDir)

        val cacheContext = FilterContext(
            rootPath = tempDir,
            relativePath = ".cache",
            fileName = ".cache",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )
        assertTrue(filter.shouldExclude(cacheDir, true, cacheContext))
    }

    @Test
    fun `should handle empty project types list`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        // With no project types, should only use common excludes
        // commonExcludes contains: .git/, .svn/, .hg/, .idea/, .vscode/, .DS_Store, *.log, *.tmp, *.temp, *.swp, *.bak, .history/

        // Test a directory that IS in commonExcludes
        val ideaDir = tempDir.resolve(".idea")
        Files.createDirectory(ideaDir)

        val context = FilterContext(
            rootPath = tempDir,
            relativePath = ".idea",
            fileName = ".idea",
            extension = "",
            projectTypes = emptyList(), // No project types
        )

        // Should exclude based on common excludes (.idea/ is in commonExcludes)
        assertTrue(filter.shouldExclude(ideaDir, true, context))

        // Test a directory that is NOT in commonExcludes (build/ is project-specific)
        val buildDir = tempDir.resolve("build")
        Files.createDirectory(buildDir)

        val buildContext = FilterContext(
            rootPath = tempDir,
            relativePath = "build",
            fileName = "build",
            extension = "",
            projectTypes = emptyList(),
        )

        // Should NOT exclude (build/ is not in commonExcludes)
        assertFalse(filter.shouldExclude(buildDir, true, buildContext))
    }

    @Test
    fun `should not exclude source directories`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val srcDir = tempDir.resolve("src")
        Files.createDirectory(srcDir)
        assertFalse(filter.shouldExclude(srcDir, true, createContext(srcDir, tempDir)))

        val mainDir = tempDir.resolve("src/main")
        Files.createDirectories(mainDir)
        assertFalse(filter.shouldExclude(mainDir, true, createContext(mainDir, tempDir)))

        val testDir = tempDir.resolve("src/test")
        Files.createDirectories(testDir)
        assertFalse(filter.shouldExclude(testDir, true, createContext(testDir, tempDir)))
    }

    @Test
    fun `should handle pattern with directory in middle of path`(@TempDir tempDir: Path) {
        val filter = ProjectTypeFilter()

        val nodeProjectType = ProjectType(
            name = "Node.js",
            markers = setOf("package.json"),
            excludePaths = setOf("node_modules/", "dist/"),
        )

        // Directory pattern should match when directory is in middle of path
        val nestedFile = tempDir.resolve("src/node_modules/package/index.js")
        Files.createDirectories(nestedFile.parent)
        Files.createFile(nestedFile)

        val nodeModulesDir = tempDir.resolve("src/node_modules")

        val context = FilterContext(
            rootPath = tempDir,
            relativePath = "src/node_modules",
            fileName = "node_modules",
            extension = "",
            projectTypes = listOf(nodeProjectType),
        )

        assertTrue(filter.shouldExclude(nodeModulesDir, true, context))
    }

    private fun createContext(file: Path, root: Path): FilterContext {
        val relativePath = try {
            root.relativize(file).toString().replace('\\', '/')
        } catch (e: Exception) {
            file.fileName.toString()
        }
        return FilterContext(
            rootPath = root,
            relativePath = relativePath,
            fileName = file.fileName.toString(),
            extension = file.fileName.toString().substringAfterLast('.', ""),
            projectTypes = emptyList(), // Default to no specific project types
        )
    }
}
