/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.repository.SkillRepository
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the user's local skills directory as a `.zip` archive — the counterpart to
 * [SkillImporter.importFromZip], so a pack exported here can be re-imported verbatim
 * on another machine (or into another Askimo profile).
 */
object SkillExporter {

    private val log = logger<SkillExporter>()

    /** Result of an export operation. */
    sealed class ExportResult {
        /** Export succeeded. [skillCount] skill folders were written to [zipFile]. */
        data class Success(val zipFile: Path, val skillCount: Int) : ExportResult()

        /** Export failed with [message]. */
        data class Failure(val message: String) : ExportResult()
    }

    /**
     * Zips every skill folder under [AskimoHome.skillsDir] into [targetZip].
     *
     * `.git` directories (from GitHub-imported packs) are excluded — re-importing an
     * exported zip goes through [SkillImporter.importFromZip], which doesn't need them,
     * and they can be large/irrelevant to the skill content itself.
     *
     * @return [ExportResult.Failure] if there are no skills to export, or on any I/O error.
     */
    fun exportAll(targetZip: Path): ExportResult {
        val skillsDir = AskimoHome.skillsDir()
        if (!Files.isDirectory(skillsDir)) {
            return ExportResult.Failure("No skills directory found at $skillsDir")
        }

        val skillCount = SkillRepository.countSkills(skillsDir)
        if (skillCount == 0) {
            return ExportResult.Failure("No skills found to export.")
        }

        return runCatching {
            Files.createDirectories(targetZip.parent)
            ZipOutputStream(Files.newOutputStream(targetZip)).use { zos ->
                Files.walk(skillsDir)
                    .filter { Files.isRegularFile(it) }
                    .filter { path -> path.none { seg -> seg.toString() == ".git" } }
                    .sorted()
                    .forEach { file ->
                        val entryName = skillsDir.relativize(file).toString().replace("\\", "/")
                        zos.putNextEntry(ZipEntry(entryName))
                        Files.copy(file, zos)
                        zos.closeEntry()
                    }
            }
            log.info("Exported {} skill(s) from {} into {}", skillCount, skillsDir, targetZip)
            ExportResult.Success(targetZip, skillCount)
        }.getOrElse { e ->
            log.error("Failed to export skills to {}: {}", targetZip, e.message, e)
            runCatching { Files.deleteIfExists(targetZip) }
            ExportResult.Failure("Export failed: ${e.message ?: "Unknown error"}")
        }
    }
}
