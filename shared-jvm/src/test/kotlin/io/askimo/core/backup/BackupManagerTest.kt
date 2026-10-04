/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.backup

import io.askimo.core.util.AskimoHome
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@AskimoTestHome
class BackupManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        // Create some test data
        val dataDir = AskimoHome.base().resolve("data")
        dataDir.toFile().mkdirs()
        Files.writeString(dataDir.resolve("test1.txt"), "Test content 1")
        Files.writeString(dataDir.resolve("test2.txt"), "Test content 2")

        val configDir = AskimoHome.base().resolve("config")
        configDir.toFile().mkdirs()
        Files.writeString(configDir.resolve("config.yml"), "test: true")
    }

    @Test
    fun `createAutoBackup should create backup file`() {
        val backupFile = BackupManager.createAutoBackup()

        assertTrue(Files.exists(backupFile))
        assertTrue(backupFile.fileName.toString().startsWith("askimo_auto_"))
        assertTrue(backupFile.fileName.toString().endsWith(".zip"))
        assertTrue(Files.size(backupFile) > 0)
    }

    @Test
    fun `exportBackup should create backup at specified location`() {
        val exportPath = tempDir.resolve("exports").resolve("my-backup.zip")

        val backupFile = BackupManager.exportBackup(exportPath)

        assertEquals(exportPath, backupFile)
        assertTrue(Files.exists(backupFile))
        assertTrue(Files.size(backupFile) > 0)
    }

    @Test
    fun `exportBackup to directory should create timestamped file`() {
        val exportDir = tempDir.resolve("exports")

        val backupFile = BackupManager.exportBackup(exportDir)

        assertTrue(Files.exists(backupFile))
        assertTrue(backupFile.fileName.toString().startsWith("askimo_export_"))
        assertTrue(backupFile.fileName.toString().endsWith(".zip"))
    }

    @Test
    fun `cleanOldAutoBackups should keep only recent backups`() {
        // Clean any existing backups first
        BackupManager.cleanOldAutoBackups(keepCount = 0)

        // Create 5 backups
        repeat(5) {
            BackupManager.createAutoBackup()
            Thread.sleep(1500) // Ensure different timestamps
        }

        val initialBackups = BackupManager.listAutoBackups()
        assertEquals(5, initialBackups.size, "Should have created 5 backups")

        // Keep only 2
        BackupManager.cleanOldAutoBackups(keepCount = 2)

        val remaining = BackupManager.listAutoBackups()
        assertEquals(2, remaining.size, "Should have kept only 2 backups")
    }

    @Test
    fun `importBackup should restore data correctly`() {
        // Create backup
        val backupFile = BackupManager.createAutoBackup()

        // Modify data
        val dataDir = AskimoHome.base().resolve("data")
        Files.writeString(dataDir.resolve("test1.txt"), "Modified content")
        Files.writeString(dataDir.resolve("test3.txt"), "New file")

        // Restore from backup
        BackupManager.importBackup(backupFile, createSafetyBackup = false)

        // Verify original data restored
        assertEquals("Test content 1", Files.readString(dataDir.resolve("test1.txt")))
        assertEquals("Test content 2", Files.readString(dataDir.resolve("test2.txt")))
        assertFalse(Files.exists(dataDir.resolve("test3.txt")))
    }

    @Test
    fun `importBackup should create safety backup by default`() {
        val backupFile = BackupManager.createAutoBackup()
        Thread.sleep(1500) // Ensure different timestamp

        // Modify data
        val dataDir = AskimoHome.base().resolve("data")
        Files.writeString(dataDir.resolve("modified.txt"), "Some changes")

        val backupsBefore = BackupManager.listAutoBackups().size

        // Import with safety backup (default)
        BackupManager.importBackup(backupFile)

        // Should have one more backup (the safety backup)
        val backupsAfter = BackupManager.listAutoBackups().size
        assertEquals(backupsBefore + 1, backupsAfter)
    }

    @Test
    fun `importBackup with non-existent file should throw exception`() {
        val nonExistentFile = tempDir.resolve("non-existent.zip")

        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.importBackup(nonExistentFile)
        }
    }

    @Test
    fun `backup should exclude backups and logs directories`() {
        // Create some auto backups
        BackupManager.createAutoBackup()
        Thread.sleep(1500)
        BackupManager.createAutoBackup()

        // Create some log files
        val logsDir = AskimoHome.base().resolve("logs")
        logsDir.toFile().mkdirs()
        Files.writeString(logsDir.resolve("test.log"), "Test log content")

        // Export backup
        val exportFile = tempDir.resolve("export.zip")
        BackupManager.exportBackup(exportFile)

        // Import to fresh directory
        val freshDir = tempDir.resolve("fresh")
        freshDir.toFile().mkdirs()

        AskimoHome.withTestBase(freshDir).use {
            BackupManager.importBackup(exportFile, createSafetyBackup = false)

            // Verify backups directory was not included
            val backupsDir = AskimoHome.base().resolve("backups")
            val hasBackups = Files.exists(backupsDir) &&
                Files.list(backupsDir).use { it.findAny().isPresent }
            assertFalse(hasBackups, "Backups directory should not be included in backup")

            // Verify logs directory was not included
            val logsDir = AskimoHome.base().resolve("logs")
            val hasLogs = Files.exists(logsDir) &&
                Files.list(logsDir).use { it.findAny().isPresent }
            assertFalse(hasLogs, "Logs directory should not be included in backup")
        }
    }

    @Test
    fun `formatSize should return human readable size`() {
        assertEquals("100 B", BackupManager.formatSize(100))
        assertEquals("5 KB", BackupManager.formatSize(5 * 1024))
        assertEquals("3 MB", BackupManager.formatSize(3 * 1024 * 1024))
        assertEquals("2 GB", BackupManager.formatSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `BackupInfo should provide formatted properties`() {
        val backupFile = BackupManager.createAutoBackup()
        val backups = BackupManager.listAutoBackups()

        val info = backups[0]
        assertNotNull(info.sizeFormatted)
        assertNotNull(info.timestampFormatted)
        assertTrue(info.fileName.endsWith(".zip"))
    }
}
