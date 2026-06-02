/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.ProcessBuilderExt
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Imports skill packs from external sources into the local skills directory.
 */
object SkillImporter {

    private val log = logger<SkillImporter>()

    /**
     * Result of an import operation.
     */
    sealed class ImportResult {
        /**
         * Import succeeded. [skillCount] skills were found in [targetDir].
         * The directory is a full git clone — users can pull updates, commit changes, and push.
         */
        data class Success(val targetDir: Path, val skillCount: Int) : ImportResult()

        /** Import failed with [message]. */
        data class Failure(val message: String) : ImportResult()
    }

    /**
     * Imports skills from a GitHub repository URL by cloning it directly into the skills directory.
     *
     * The repository is cloned as a **full git clone** (with `.git`) so users can:
     * - Pull upstream updates: `git pull`
     * - Commit local modifications: `git commit`
     * - Push changes back: `git push`
     *
     * Supports:
     * - `https://github.com/user/repo` — clones the whole repo
     * - `https://github.com/user/repo/tree/branch/subdir` — clones repo, uses subdir as skill root
     *   (skills are read from the subdir but the full repo is stored)
     *
     * Requires `git` on `PATH`.
     */
    fun importFromGitHub(repoUrl: String): ImportResult {
        val normalizedUrl = repoUrl.trim().trimEnd('/')
        log.info("Importing skills from GitHub: {}", normalizedUrl)

        val (cloneUrl, subDir) = parseGitHubUrl(normalizedUrl)
            ?: return ImportResult.Failure("Invalid GitHub URL: $normalizedUrl\n\nExpected format: https://github.com/user/repo")

        val repoName = cloneUrl.substringAfterLast('/').removeSuffix(".git")
        val targetDir = AskimoHome.skillsDir().resolve(repoName)

        if (Files.isDirectory(targetDir.resolve(".git"))) {
            return ImportResult.Failure("Repository '$repoName' is already imported at $targetDir.\n\nTo update, run: git -C \"$targetDir\" pull")
        }

        return runCatching {
            log.debug("Cloning {} into {}", cloneUrl, targetDir)
            Files.createDirectories(targetDir.parent)

            val cloneResult = ProcessBuilderExt("git", "clone", cloneUrl, targetDir.toString())
                .redirectErrorStream(true)
                .apply {
                    val env = environment()
                    env["HOME"] = System.getProperty("user.home")
                    val developerDir = listOfNotNull(
                        System.getenv("DEVELOPER_DIR"),
                        System.getenv("XCODE_DEVELOPER_DIR_PATH"),
                        "/Applications/Xcode.app/Contents/Developer",
                        "/Library/Developer/CommandLineTools",
                    ).firstOrNull { File(it).exists() }
                    if (developerDir != null) env["DEVELOPER_DIR"] = developerDir
                    System.getenv("PATH")?.let { env["PATH"] = it }
                }
                .start()
            val cloneOutput = cloneResult.inputStream.bufferedReader().readText().trim()
            val cloneExit = cloneResult.waitFor()
            if (cloneExit != 0) {
                return ImportResult.Failure("git clone failed (exit $cloneExit):\n$cloneOutput")
            }

            val skillRoot = if (subDir != null) targetDir.resolve(subDir) else targetDir
            if (!Files.isDirectory(skillRoot)) {
                return ImportResult.Failure("Subdirectory '$subDir' not found in repository.")
            }

            val skillCount = SkillRepository.countSkills(skillRoot)
            log.info("Cloned {} with {} skill(s) into {}", cloneUrl, skillCount, targetDir)
            ImportResult.Success(targetDir, skillCount)
        }.getOrElse { e ->
            log.error("Failed to import skills from GitHub: {}", e.message, e)
            runCatching { targetDir.toFile().deleteRecursively() }
            ImportResult.Failure("Import failed: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Imports skills from a local `.zip` archive.
     *
     * The archive is extracted to a temporary directory, then skill folders are imported
     * using the same rules as directory imports (folder contains `skill.md`).
     */
    fun importFromZip(zipFile: Path): ImportResult {
        val normalizedZip = zipFile.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalizedZip)) {
            return ImportResult.Failure("ZIP file not found: $normalizedZip")
        }
        if (!normalizedZip.fileName.toString().endsWith(".zip", ignoreCase = true)) {
            return ImportResult.Failure("Selected file is not a .zip archive: ${normalizedZip.fileName}")
        }

        val zipName = normalizedZip.fileName.toString()
        val targetName = if (zipName.endsWith(".zip", ignoreCase = true)) {
            zipName.substring(0, zipName.length - 4)
        } else {
            zipName
        }.ifBlank { "imported-skills" }
        val targetDir = AskimoHome.skillsDir().resolve(targetName)
        if (Files.exists(targetDir)) {
            return ImportResult.Failure("Target folder '$targetName' already exists at $targetDir.")
        }

        val tempDir = Files.createTempDirectory("askimo-skills-zip-")
        return runCatching {
            log.info("Importing skills from ZIP: {}", normalizedZip)
            extractZipSecurely(normalizedZip, tempDir)

            val importedCount = SkillRepository().importFromDirectory(tempDir, targetName)
            if (importedCount <= 0) {
                runCatching { targetDir.toFile().deleteRecursively() }
                return ImportResult.Failure(
                    "No skills found in ZIP archive. Make sure it contains folders with a skill.md file.",
                )
            }

            log.info("Imported {} skill(s) from ZIP {} into {}", importedCount, normalizedZip, targetDir)
            ImportResult.Success(targetDir, importedCount)
        }.getOrElse { e ->
            log.error("Failed to import skills from ZIP {}: {}", normalizedZip, e.message, e)
            runCatching { targetDir.toFile().deleteRecursively() }
            ImportResult.Failure("ZIP import failed: ${e.message ?: "Unknown error"}")
        }.also {
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses a GitHub URL into a (cloneUrl, optionalSubDir) pair.
     *
     * Examples:
     * - `https://github.com/user/repo` → (`https://github.com/user/repo.git`, null)
     * - `https://github.com/user/repo/tree/main/skills` → (`https://github.com/user/repo.git`, `skills`)
     */
    private fun parseGitHubUrl(url: String): Pair<String, String?>? {
        return runCatching {
            val uri = URI.create(url)
            if (uri.host?.contains("github.com") != true) return null

            val parts = uri.path.trimStart('/').split('/')
            if (parts.size < 2) return null

            val user = parts[0]
            val repo = parts[1].removeSuffix(".git")
            val cloneUrl = "https://github.com/$user/$repo.git"

            // https://github.com/user/repo/tree/branch/path/to/dir
            val subDir = if (parts.size > 4 && parts[2] == "tree") {
                parts.drop(4).joinToString("/").ifBlank { null }
            } else {
                null
            }

            Pair(cloneUrl, subDir)
        }.getOrNull()
    }

    /**
     * Extracts [zipFile] into [targetDir] with Zip Slip protection.
     */
    private fun extractZipSecurely(zipFile: Path, targetDir: Path) {
        ZipInputStream(Files.newInputStream(zipFile)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val destination = targetDir.resolve(entry.name).normalize()

                require(destination.startsWith(targetDir)) { "Unsafe ZIP entry path: ${entry.name}" }

                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.newOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }

                input.closeEntry()
            }
        }
    }
}
