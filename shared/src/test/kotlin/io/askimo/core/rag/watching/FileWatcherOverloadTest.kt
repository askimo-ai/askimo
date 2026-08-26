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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * Simulates "overloading" the watch list (issue #619) by watching a directory tree with
 * many nested subdirectories, then touching a file in each one. On macOS, java.nio's
 * default WatchService is poll-based and has no hard registration limit (unlike Linux
 * inotify's fs.inotify.max_user_watches), so this is expected to succeed cleanly rather
 * than reproduce the OS-level failure the issue describes.
 */
class FileWatcherOverloadTest {

    @Test
    fun `watching many nested directories does not throw and detects changes in all of them`() {
        val root = createTempDirectory("askimo-watch-overload-test")
        val askimoHomeDir = createTempDirectory("askimo-watch-overload-home")
        val dirCount = 200
        val dirs = (1..dirCount).map { i ->
            root.resolve("dir_$i").also { it.createDirectories() }
        }

        val detected = CopyOnWriteArrayList<Path>()
        val latch = CountDownLatch(dirCount)

        val watcher = FileWatcher(
            projectId = "watch-overload-test",
            onFileChange = { path, _ ->
                detected.add(path)
                latch.countDown()
            },
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            AskimoHome.withTestBase(askimoHomeDir).use {
                watcher.startWatching(root, scope)

                // Let registerDirectoryTree() finish registering all 200 dirs and the poll loop start.
                Thread.sleep(1_000)
            }

            dirs.forEach { it.resolve("touched.txt").writeText("hi") }

            val allDetected = latch.await(20, TimeUnit.SECONDS)

            assertTrue(
                allDetected,
                "Expected changes in all $dirCount directories to be detected, " +
                    "only saw ${dirCount - latch.count} before timeout",
            )
        } finally {
            watcher.stopWatching()
            scope.cancel()
            root.toFile().deleteRecursively()
            askimoHomeDir.toFile().deleteRecursively()
        }
    }
}
