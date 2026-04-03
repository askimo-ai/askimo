/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2025 Hai Nguyen
 */
package io.askimo.ui.service

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages the custom background image chosen by the user.
 *
 * When the user picks an image file from anywhere on disk, this service **copies**
 * it into `~/.askimo/personal/backgrounds/` (resolved via [AskimoHome]) so the
 * background remains available regardless of whether the original file is later
 * moved, deleted, or was on a removable drive.
 *
 * Only one custom background is kept at a time — the stored file is replaced on
 * every [saveCustomBackground] call to avoid accumulating large image files.
 */
object BackgroundImageService {

    private val log = logger<BackgroundImageService>()

    /**
     * Directory where the managed custom background copy lives.
     * Resolves to `~/.askimo/personal/backgrounds/`.
     * Evaluated lazily on each access so it respects any [AskimoHome] reconfiguration.
     */
    private val backgroundsDir: File
        get() = AskimoHome.base().resolve("backgrounds").toFile()

    /** Base filename (without extension) of the stored custom background file. */
    private const val STORED_NAME = "custom_background"

    private val VALID_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

    /**
     * Copies the image at [sourcePath] into the managed `backgrounds/` directory,
     * replacing any previously stored custom background file.
     *
     * @param sourcePath Absolute path to the image file the user picked.
     * @return The absolute path of the stored copy inside `~/.askimo/personal/backgrounds/`,
     *         or `null` if the source file does not exist, has an unsupported format, or
     *         the copy operation fails.
     */
    fun saveCustomBackground(sourcePath: String): String? {
        val sourceFile = File(sourcePath)

        if (!sourceFile.exists()) {
            log.error("Custom background source file does not exist: $sourcePath")
            return null
        }

        val ext = sourceFile.extension.lowercase()
        if (ext !in VALID_EXTENSIONS) {
            log.error("Unsupported image format '$ext' for custom background: $sourcePath")
            return null
        }

        return try {
            val dir = backgroundsDir
            dir.mkdirs()

            // Remove any previously stored file (extension may differ from the new pick)
            dir.listFiles()
                ?.filter { it.nameWithoutExtension == STORED_NAME }
                ?.forEach { it.delete() }

            val destFile = File(dir, "$STORED_NAME.$ext")
            Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            log.info("Custom background saved to: ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            log.error("Failed to save custom background from $sourcePath", e)
            null
        }
    }

    /**
     * Deletes the stored custom background file, if one exists.
     * Call this when the user switches away from a custom background to keep
     * the `backgrounds/` directory clean.
     */
    fun removeCustomBackground() {
        try {
            backgroundsDir.listFiles()
                ?.filter { it.nameWithoutExtension == STORED_NAME }
                ?.forEach { it.delete() }
            log.info("Custom background removed")
        } catch (e: Exception) {
            log.error("Failed to remove custom background", e)
        }
    }
}
