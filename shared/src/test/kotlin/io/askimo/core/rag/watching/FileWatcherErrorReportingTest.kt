/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.watching

import io.askimo.core.util.AskimoHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.assertEquals

/**
 * Regression test for issue #619's "silent failure" problem: registerDirectoryTree() used to
 * swallow registration failures with only a log.warn, so callers had no way to know watching
 * was broken. This verifies onWatchError actually fires, and fires only once even when many
 * directories fail the same way (e.g. an exhausted inotify limit would fail every directory
 * registered after the limit is hit — one toast, not hundreds).
 */
class FileWatcherErrorReportingTest {

    @Test
    fun `unreadable directories are reported through onWatchError exactly once`() {
        val root = createTempDirectory("askimo-watch-error-test")
        val askimoHomeDir = createTempDirectory("askimo-watch-error-home")
        val blockedDirs = (1..5).map { i ->
            root.resolve("blocked_$i").also { it.createDirectories() }
        }

        val errors = CopyOnWriteArrayList<Pair<Path, Exception>>()
        val watcher = FileWatcher(
            projectId = "watch-error-test",
            onFileChange = { _, _ -> },
            onWatchError = { path, e -> errors.add(path to e) },
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            blockedDirs.forEach { it.setPosixFilePermissions(PosixFilePermissions.fromString("---------")) }

            AskimoHome.withTestBase(askimoHomeDir).use {
                watcher.startWatching(root, scope)
                Thread.sleep(500)
            }

            assertEquals(
                1,
                errors.size,
                "Expected exactly one reported error despite ${blockedDirs.size} directories failing " +
                    "registration independently, got: $errors",
            )
        } finally {
            watcher.stopWatching()
            scope.cancel()
            blockedDirs.forEach { it.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------")) }
            root.toFile().deleteRecursively()
            askimoHomeDir.toFile().deleteRecursively()
        }
    }
}
