/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Canonicalizes index path keys so persisted and UI paths match reliably.
 */
object IndexPathNormalizer {
    fun normalize(path: String): String = try {
        val nioPath = Paths.get(path)
        val normalized: Path = if (Files.exists(nioPath)) {
            // Resolve symlinks for existing paths to avoid duplicate logical keys.
            nioPath.toRealPath()
        } else {
            nioPath.toAbsolutePath().normalize()
        }
        normalized.toString().replace('\\', '/')
    } catch (_: Exception) {
        path.trim().replace('\\', '/')
    }
}
