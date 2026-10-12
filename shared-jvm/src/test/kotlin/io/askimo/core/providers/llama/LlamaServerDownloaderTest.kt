/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class LlamaServerDownloaderTest {

    @Test
    fun `prepareCleanDestDir removes a stale file left by a previous release`(
        @TempDir tempDir: Path,
    ) {
        val destDir = tempDir.resolve("macos")
        Files.createDirectories(destDir)
        val staleFile = destDir.resolve("libold-dependency.dylib")
        Files.writeString(staleFile, "stale bytes from a previous release")

        LlamaServerDownloader.prepareCleanDestDir(destDir)

        assertTrue(Files.exists(destDir), "destDir should exist (recreated) after cleanup")
        assertTrue(
            Files.list(destDir).use { it.findAny().isEmpty },
            "destDir should be empty, but stale file survived",
        )
    }

    @Test
    fun `prepareCleanDestDir is safe to call when destDir does not exist yet`(
        @TempDir tempDir: Path,
    ) {
        val destDir = tempDir.resolve("does-not-exist-yet")

        LlamaServerDownloader.prepareCleanDestDir(destDir)

        assertTrue(Files.isDirectory(destDir), "destDir should be created even if absent beforehand")
    }
}
