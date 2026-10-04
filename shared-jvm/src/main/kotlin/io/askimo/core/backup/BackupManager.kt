/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.backup

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val log = logger<BackupManager>()

/**
 * Manages backup and restore operations for Askimo data.
 *
 * Supports two types of backups:
 * 1. Auto-backups: Stored in ~/.askimo/backups/ for local safety net
 * 2. Export/Import: User-specified location for cross-machine transfer
 */
object BackupManager {
    private val backupDir = AskimoHome.base().resolve("backups")
    private val logsDir = AskimoHome.base().resolve("logs")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /**
     * Creates an automatic backup (stored in ~/.askimo/backups/)
     * Used for safety net and recovery
     *
     * @return Path to the created backup file
     */
    fun createAutoBackup(): Path {
        backupDir.toFile().mkdirs()
        val timestamp = LocalDateTime.now().format(dateFormat)
        val backupFile = backupDir.resolve("askimo_auto_$timestamp.zip")

        createBackupArchive(backupFile)
        log.info("Auto-backup created: $backupFile (${Files.size(backupFile)} bytes)")
        return backupFile
    }

    /**
     * Exports backup to user-specified location
     * Used for cross-machine transfer or off-site storage
     *
     * @param targetPath User-chosen destination path (can be a directory or .zip file)
     * @return Path to the exported backup file
     */
    fun exportBackup(targetPath: Path): Path {
        val timestamp = LocalDateTime.now().format(dateFormat)
        val exportFile = if (targetPath.toString().endsWith(".zip")) {
            targetPath
        } else {
            // If directory provided, create file inside it
            targetPath.toFile().mkdirs()
            targetPath.resolve("askimo_export_$timestamp.zip")
        }

        exportFile.parent?.toFile()?.mkdirs()
        createBackupArchive(exportFile)
        log.info("Backup exported to: $exportFile (${Files.size(exportFile)} bytes)")
        return exportFile
    }

    /**
     * Imports/restores from any backup file (auto or exported)
     *
     * @param backupFile Path to backup zip file
     * @param createSafetyBackup If true, creates auto-backup before restoring
     * @throws IllegalArgumentException if backup file doesn't exist
     * @throws Exception if restore fails
     */
    fun importBackup(backupFile: Path, createSafetyBackup: Boolean = true) {
        require(Files.exists(backupFile)) { "Backup file not found: $backupFile" }

        if (createSafetyBackup) {
            log.info("Creating safety backup before import...")
            createAutoBackup()
        }

        val askimoDir = AskimoHome.base()

        // Clear existing data (except backups and logs directories)
        log.info("Clearing existing data...")
        Files.walk(askimoDir)
            .filter { !it.startsWith(backupDir) && !it.startsWith(logsDir) && it != askimoDir }
            .sorted(Comparator.reverseOrder())
            .forEach {
                try {
                    Files.deleteIfExists(it)
                } catch (e: Exception) {
                    log.warn("Failed to delete $it: ${e.message}")
                }
            }

        // Extract backup
        log.info("Extracting backup from $backupFile...")
        var entriesExtracted = 0
        ZipInputStream(Files.newInputStream(backupFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val targetPath = askimoDir.resolve(entry.name)

                if (entry.isDirectory) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(zis, targetPath, StandardCopyOption.REPLACE_EXISTING)
                    entriesExtracted++
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        log.info("Import completed from: $backupFile ($entriesExtracted files restored)")
    }

    /**
     * Lists available auto-backups in ~/.askimo/backups/
     *
     * @return List of backup info sorted by timestamp (newest first)
     */
    fun listAutoBackups(): List<BackupInfo> {
        if (!Files.exists(backupDir)) return emptyList()

        return Files.list(backupDir)
            .filter { it.fileName.toString().startsWith("askimo_auto_") }
            .filter { it.fileName.toString().endsWith(".zip") }
            .map { path ->
                BackupInfo(
                    path = path,
                    size = Files.size(path),
                    timestamp = Files.getLastModifiedTime(path).toInstant(),
                    type = BackupType.AUTO,
                )
            }
            .sorted(compareByDescending { it.timestamp })
            .toList()
    }

    /**
     * Cleans old auto-backups, keeping only the most recent N
     *
     * @param keepCount Number of most recent backups to keep (default: 5)
     */
    fun cleanOldAutoBackups(keepCount: Int = 5) {
        val backups = listAutoBackups()
        val deleted = backups.drop(keepCount)

        deleted.forEach { backup ->
            try {
                Files.deleteIfExists(backup.path)
                log.info("Deleted old auto-backup: ${backup.path.fileName}")
            } catch (e: Exception) {
                log.warn("Failed to delete backup ${backup.path.fileName}: ${e.message}")
            }
        }

        if (deleted.isNotEmpty()) {
            log.info("Cleaned ${deleted.size} old backup(s), kept ${minOf(keepCount, backups.size)}")
        }
    }

    /**
     * Core backup creation logic - creates a zip archive of the entire .askimo directory
     * (excluding backups and logs folders to avoid recursion and bloat)
     */
    private fun createBackupArchive(targetFile: Path) {
        val askimoDir = AskimoHome.base()
        var filesBackedUp = 0

        ZipOutputStream(Files.newOutputStream(targetFile)).use { zos ->
            Files.walk(askimoDir)
                .filter { path -> !path.startsWith(backupDir) && !path.startsWith(logsDir) } // Exclude backups and logs folders
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    try {
                        val relativePath = askimoDir.relativize(file)
                        zos.putNextEntry(ZipEntry(relativePath.toString()))
                        Files.copy(file, zos)
                        zos.closeEntry()
                        filesBackedUp++
                    } catch (e: Exception) {
                        log.warn("Failed to backup file $file: ${e.message}")
                    }
                }
        }

        log.debug("Backed up {} files to {}", filesBackedUp, targetFile)
    }

    /**
     * Gets human-readable size string from bytes
     */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }

    /**
     * Formats timestamp to human-readable string
     */
    fun formatTimestamp(instant: Instant): String {
        val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}

/**
 * Type of backup
 */
enum class BackupType {
    /** Automatic periodic backup stored in ~/.askimo/backups/ */
    AUTO,

    /** User-initiated export to custom location */
    EXPORT,
}

/**
 * Information about a backup file
 */
data class BackupInfo(
    val path: Path,
    val size: Long,
    val timestamp: Instant,
    val type: BackupType,
) {
    val sizeFormatted: String
        get() = BackupManager.formatSize(size)

    val timestampFormatted: String
        get() = BackupManager.formatTimestamp(timestamp)

    val fileName: String
        get() = path.fileName.toString()
}
