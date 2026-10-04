/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GitignoreFilterTest {

    @Test
    fun `should not exclude files when no gitignore exists`(@TempDir tempDir: Path) {
        // No .git directory, so no filtering should occur
        val testFile = tempDir.resolve("README.md")
        Files.createFile(testFile)

        val filter = GitignoreFilter()

        assertFalse(filter.shouldExclude(testFile, false, createContext(testFile, tempDir)))
    }

    @Test
    fun `should exclude files matching simple patterns`(@TempDir tempDir: Path) {
        // Setup: Create a git repo with .gitignore
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            *.log
            temp.txt
            """.trimIndent(),
        )

        val logFile = tempDir.resolve("debug.log")
        Files.createFile(logFile)

        val tempFile = tempDir.resolve("temp.txt")
        Files.createFile(tempFile)

        val readmeFile = tempDir.resolve("README.md")
        Files.createFile(readmeFile)

        val filter = GitignoreFilter()

        // Should exclude .log files
        assertTrue(filter.shouldExclude(logFile, false, createContext(logFile, tempDir)))

        // Should exclude temp.txt
        assertTrue(filter.shouldExclude(tempFile, false, createContext(tempFile, tempDir)))

        // Should NOT exclude README.md
        assertFalse(filter.shouldExclude(readmeFile, false, createContext(readmeFile, tempDir)))
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
        )
    }

    @Test
    fun `should handle wildcard patterns correctly`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            *.log
            *.tmp
            test-*
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude .log files
        val logFile = tempDir.resolve("debug.log")
        Files.createFile(logFile)
        assertTrue(filter.shouldExclude(logFile, false, createContext(logFile, tempDir)))

        // Should exclude .tmp files
        val tmpFile = tempDir.resolve("cache.tmp")
        Files.createFile(tmpFile)
        assertTrue(filter.shouldExclude(tmpFile, false, createContext(tmpFile, tempDir)))

        // Should exclude test-* files
        val testFile = tempDir.resolve("test-data.txt")
        Files.createFile(testFile)
        assertTrue(filter.shouldExclude(testFile, false, createContext(testFile, tempDir)))

        // Should NOT exclude other files
        val readmeFile = tempDir.resolve("README.md")
        Files.createFile(readmeFile)
        assertFalse(filter.shouldExclude(readmeFile, false, createContext(readmeFile, tempDir)))
    }

    @Test
    fun `should exclude directories`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            node_modules/
            build/
            .idea
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude node_modules/ directory
        val nodeModules = tempDir.resolve("node_modules")
        Files.createDirectory(nodeModules)
        assertTrue(filter.shouldExclude(nodeModules, true, createContext(nodeModules, tempDir)))

        // Should exclude build/ directory
        val buildDir = tempDir.resolve("build")
        Files.createDirectory(buildDir)
        assertTrue(filter.shouldExclude(buildDir, true, createContext(buildDir, tempDir)))

        // Should exclude .idea (both file and directory)
        val ideaDir = tempDir.resolve(".idea")
        Files.createDirectory(ideaDir)
        assertTrue(filter.shouldExclude(ideaDir, true, createContext(ideaDir, tempDir)))
    }

    @Test
    fun `should handle nested gitignore files`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        // Root .gitignore
        val rootGitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            rootGitignore,
            """
            *.log
            """.trimIndent(),
        )

        // Subdirectory with its own .gitignore
        val subDir = tempDir.resolve("subdir")
        Files.createDirectory(subDir)
        val subGitignore = subDir.resolve(".gitignore")
        Files.writeString(
            subGitignore,
            """
            *.tmp
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Root .log file should be excluded
        val rootLog = tempDir.resolve("root.log")
        Files.createFile(rootLog)
        assertTrue(filter.shouldExclude(rootLog, false, createContext(rootLog, tempDir)))

        // Subdir .log file should be excluded (inherited from root)
        val subLog = subDir.resolve("sub.log")
        Files.createFile(subLog)
        assertTrue(filter.shouldExclude(subLog, false, createContext(subLog, tempDir)))

        // Subdir .tmp file should be excluded (from subdir .gitignore)
        val subTmp = subDir.resolve("sub.tmp")
        Files.createFile(subTmp)
        assertTrue(filter.shouldExclude(subTmp, false, createContext(subTmp, tempDir)))

        // Root .tmp file should NOT be excluded (not in root .gitignore)
        val rootTmp = tempDir.resolve("root.tmp")
        Files.createFile(rootTmp)
        assertFalse(filter.shouldExclude(rootTmp, false, createContext(rootTmp, tempDir)))
    }

    @Test
    fun `should handle negation patterns`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            *.log
            !important.log
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude .log files
        val debugLog = tempDir.resolve("debug.log")
        Files.createFile(debugLog)
        assertTrue(filter.shouldExclude(debugLog, false, createContext(debugLog, tempDir)))

        // Should NOT exclude important.log (negation)
        val importantLog = tempDir.resolve("important.log")
        Files.createFile(importantLog)
        assertFalse(filter.shouldExclude(importantLog, false, createContext(importantLog, tempDir)))
    }

    @Test
    fun `should handle double asterisk patterns`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            **/node_modules
            **/*.log
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude node_modules at any level
        val rootNodeModules = tempDir.resolve("node_modules")
        Files.createDirectory(rootNodeModules)
        assertTrue(filter.shouldExclude(rootNodeModules, true, createContext(rootNodeModules, tempDir)))

        val deepNodeModules = tempDir.resolve("src/vendor/node_modules")
        Files.createDirectories(deepNodeModules)
        assertTrue(filter.shouldExclude(deepNodeModules, true, createContext(deepNodeModules, tempDir)))

        // Should exclude .log files at any level
        val rootLog = tempDir.resolve("app.log")
        Files.createFile(rootLog)
        assertTrue(filter.shouldExclude(rootLog, false, createContext(rootLog, tempDir)))

        val deepLog = tempDir.resolve("src/main/logs/debug.log")
        Files.createDirectories(deepLog.parent)
        Files.createFile(deepLog)
        assertTrue(filter.shouldExclude(deepLog, false, createContext(deepLog, tempDir)))
    }

    @Test
    fun `should handle rooted patterns`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            /build
            /temp.txt
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude /build at root
        val rootBuild = tempDir.resolve("build")
        Files.createDirectory(rootBuild)
        assertTrue(filter.shouldExclude(rootBuild, true, createContext(rootBuild, tempDir)))

        // Should NOT exclude build in subdirectory
        val subBuild = tempDir.resolve("src/build")
        Files.createDirectories(subBuild)
        assertFalse(filter.shouldExclude(subBuild, true, createContext(subBuild, tempDir)))

        // Should exclude /temp.txt at root
        val rootTemp = tempDir.resolve("temp.txt")
        Files.createFile(rootTemp)
        assertTrue(filter.shouldExclude(rootTemp, false, createContext(rootTemp, tempDir)))

        // Should NOT exclude temp.txt in subdirectory
        val subTemp = tempDir.resolve("src/temp.txt")
        Files.createDirectories(subTemp.parent)
        Files.createFile(subTemp)
        assertFalse(filter.shouldExclude(subTemp, false, createContext(subTemp, tempDir)))
    }

    @Test
    fun `should ignore comments and blank lines`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            # This is a comment
            *.log

            # Another comment
            temp.txt

            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude .log files
        val logFile = tempDir.resolve("test.log")
        Files.createFile(logFile)
        assertTrue(filter.shouldExclude(logFile, false, createContext(logFile, tempDir)))

        // Should exclude temp.txt
        val tempFile = tempDir.resolve("temp.txt")
        Files.createFile(tempFile)
        assertTrue(filter.shouldExclude(tempFile, false, createContext(tempFile, tempDir)))
    }

    @Test
    fun `should not exclude README md file`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            *.log
            build/
            node_modules/
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should NOT exclude README.md
        val readmeFile = tempDir.resolve("README.md")
        Files.createFile(readmeFile)
        assertFalse(filter.shouldExclude(readmeFile, false, createContext(readmeFile, tempDir)))

        // Should NOT exclude any .md files
        val docsFile = tempDir.resolve("CONTRIBUTING.md")
        Files.createFile(docsFile)
        assertFalse(filter.shouldExclude(docsFile, false, createContext(docsFile, tempDir)))
    }

    @Test
    fun `should handle directory-only patterns correctly`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            xyzTestDir123/
            abcOutputDir456/
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude xyzTestDir123 directory
        val testDir = tempDir.resolve("xyzTestDir123")
        Files.createDirectory(testDir)
        assertTrue(filter.shouldExclude(testDir, true, createContext(testDir, tempDir)))

        // Should exclude abcOutputDir456 directory
        val outputDir = tempDir.resolve("abcOutputDir456")
        Files.createDirectory(outputDir)
        assertTrue(filter.shouldExclude(outputDir, true, createContext(outputDir, tempDir)))

        // Should NOT exclude a file with similar name (directory-only pattern doesn't match files)
        val testFile = tempDir.resolve("xyzTestDir123.kt")
        Files.createFile(testFile)
        assertFalse(filter.shouldExclude(testFile, false, createContext(testFile, tempDir)))

        // Should NOT exclude another file with similar name
        val outputFile = tempDir.resolve("abcOutputDir456.java")
        Files.createFile(outputFile)
        assertFalse(filter.shouldExclude(outputFile, false, createContext(outputFile, tempDir)))
    }

    @Test
    fun `should handle complex real-world gitignore`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            # Build outputs
            /build/
            /dist/
            *.class

            # Dependencies
            node_modules/
            vendor/

            # IDE
            .idea/
            .vscode/
            *.iml

            # Logs
            *.log
            logs/

            # Temp files
            *.tmp
            *.swp
            *~

            # OS files
            .DS_Store
            Thumbs.db

            # But keep important files
            !important.log
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Test various file types
        val sourceFile = tempDir.resolve("Main.kt")
        Files.createFile(sourceFile)
        assertFalse(filter.shouldExclude(sourceFile, false, createContext(sourceFile, tempDir)))

        val classFile = tempDir.resolve("Main.class")
        Files.createFile(classFile)
        assertTrue(filter.shouldExclude(classFile, false, createContext(classFile, tempDir)))

        val logFile = tempDir.resolve("app.log")
        Files.createFile(logFile)
        assertTrue(filter.shouldExclude(logFile, false, createContext(logFile, tempDir)))

        val importantLog = tempDir.resolve("important.log")
        Files.createFile(importantLog)
        assertFalse(filter.shouldExclude(importantLog, false, createContext(importantLog, tempDir)))

        val nodeModules = tempDir.resolve("node_modules")
        Files.createDirectory(nodeModules)
        assertTrue(filter.shouldExclude(nodeModules, true, createContext(nodeModules, tempDir)))

        val ideaDir = tempDir.resolve(".idea")
        Files.createDirectory(ideaDir)
        assertTrue(filter.shouldExclude(ideaDir, true, createContext(ideaDir, tempDir)))

        val readme = tempDir.resolve("README.md")
        Files.createFile(readme)
        assertFalse(filter.shouldExclude(readme, false, createContext(readme, tempDir)))
    }

    @Test
    fun `should match simple patterns only at gitignore directory level`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            README.md
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude README.md at root (same directory as .gitignore)
        val rootReadme = tempDir.resolve("README.md")
        Files.createFile(rootReadme)
        assertTrue(filter.shouldExclude(rootReadme, false, createContext(rootReadme, tempDir)))

        // Should NOT exclude README.md in subdirectory (simple pattern matches only at .gitignore level)
        val desktopReadme = tempDir.resolve("desktop/README.md")
        Files.createDirectories(desktopReadme.parent)
        Files.createFile(desktopReadme)
        assertFalse(filter.shouldExclude(desktopReadme, false, createContext(desktopReadme, tempDir)))

        // Should NOT exclude README.md in nested subdirectory
        val nestedReadme = tempDir.resolve("src/main/docs/README.md")
        Files.createDirectories(nestedReadme.parent)
        Files.createFile(nestedReadme)
        assertFalse(filter.shouldExclude(nestedReadme, false, createContext(nestedReadme, tempDir)))
    }

    @Test
    fun `should match patterns at any level with double asterisk prefix`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectory(gitDir)

        val gitignore = tempDir.resolve(".gitignore")
        Files.writeString(
            gitignore,
            """
            **/README.md
            """.trimIndent(),
        )

        val filter = GitignoreFilter()

        // Should exclude README.md at root
        val rootReadme = tempDir.resolve("README.md")
        Files.createFile(rootReadme)
        assertTrue(filter.shouldExclude(rootReadme, false, createContext(rootReadme, tempDir)))

        // Should exclude README.md in subdirectory (with **/)
        val desktopReadme = tempDir.resolve("desktop/README.md")
        Files.createDirectories(desktopReadme.parent)
        Files.createFile(desktopReadme)
        assertTrue(filter.shouldExclude(desktopReadme, false, createContext(desktopReadme, tempDir)))

        // Should exclude README.md in nested subdirectory
        val nestedReadme = tempDir.resolve("src/main/docs/README.md")
        Files.createDirectories(nestedReadme.parent)
        Files.createFile(nestedReadme)
        assertTrue(filter.shouldExclude(nestedReadme, false, createContext(nestedReadme, tempDir)))
    }
}
