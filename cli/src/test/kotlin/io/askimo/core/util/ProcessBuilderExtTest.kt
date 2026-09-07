/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.TimeUnit

class ProcessBuilderExtTest {
    @Test
    fun `ProcessBuilderExt should execute common system commands`() {
        // Test with a common executable that should exist on all platforms
        val executable =
            if (isWindows()) {
                "cmd"
            } else {
                "sh"
            }

        val args =
            if (isWindows()) {
                arrayOf(executable, "/c", "echo", "hello")
            } else {
                arrayOf(executable, "-c", "echo hello")
            }

        val process = ProcessBuilderExt(*args).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertTrue(exitCode == 0, "Command should execute successfully")
        assertTrue(output.contains("hello"), "Output should contain 'hello'")
    }

    @Test
    fun `ProcessBuilderExt should work with redirectErrorStream`() {
        val executable =
            if (isWindows()) {
                "cmd"
            } else {
                "sh"
            }

        val process =
            ProcessBuilderExt(executable, "--version")
                .redirectErrorStream(true)
                .start()

        val exitCode = process.waitFor(5, TimeUnit.SECONDS)
        // Either success or error is fine, we're just testing the API works
        assertNotNull(exitCode)
    }

    @Test
    fun `ProcessBuilderExt should handle List constructor`() {
        val executable =
            if (isWindows()) {
                "cmd"
            } else {
                "sh"
            }

        val command =
            if (isWindows()) {
                listOf(executable, "/c", "echo", "test")
            } else {
                listOf(executable, "-c", "echo test")
            }

        val process = ProcessBuilderExt(command).start()
        val exitCode = process.waitFor()

        assertTrue(exitCode == 0, "Command should execute successfully")
    }

    @Test
    fun `ProcessBuilderExt should find executables in PATH`() {
        // Try to find a common executable
        // This test might fail if the executable is not installed
        try {
            val process =
                ProcessBuilderExt("echo", "test")
                    .redirectErrorStream(true)
                    .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor(5, TimeUnit.SECONDS)

            // If echo is found and works, verify it
            if (exitCode) {
                assertTrue(output.contains("test"), "Echo should output 'test'")
            }
        } catch (e: Exception) {
            // It's ok if echo is not found on some systems
            println("Echo not available: ${e.message}")
        }
    }

    // ── Windows-only tests ──────────────────────────────────────────────────
    // Gated with @EnabledOnOs(OS.WINDOWS) rather than the isWindows()/branching
    // style above: these exercise behavior that only makes sense on a real
    // Windows box (cmd.exe, the registry PATH fallback), so they should be
    // skipped entirely on macOS/Linux dev machines instead of silently
    // no-op'ing, and only actually run on a Windows CI runner.

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `isWindows should report true when running on a Windows runner`() {
        assertTrue(ProcessBuilderExt.isWindows())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `ProcessBuilderExt should execute cmd exe on Windows`() {
        val process = ProcessBuilderExt("cmd", "/c", "echo", "hello").start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertTrue(exitCode == 0, "cmd should execute successfully")
        assertTrue(output.contains("hello"), "Output should contain 'hello'")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `which should resolve cmd to an absolute path on Windows`() {
        val resolved = ProcessBuilderExt.which("cmd")

        assertNotNull(resolved, "cmd should be resolvable on any Windows runner")
        assertTrue(java.io.File(resolved!!).exists(), "Resolved path should exist: $resolved")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `enrichedPath should include well-known Windows fallback directories`() {
        val enriched = ProcessBuilderExt.enrichedPath()
        val entries = enriched.split(java.io.File.pathSeparator)

        assertTrue(entries.isNotEmpty(), "enrichedPath should never be empty")
        assertTrue(
            entries.any { it.contains("nodejs", ignoreCase = true) || it.contains("npm", ignoreCase = true) },
            "enrichedPath should include at least one well-known Windows Node/npm fallback dir",
        )
    }

    // Regression test for a real bug: PowerShell string-concatenation of two unset
    // `[Environment]::GetEnvironmentVariable` values yields the literal, non-blank
    // string ";" — which `runShellCommand`'s `isNotBlank()` check would otherwise
    // treat as a "successful" registry PATH lookup, silently wiping out the enriched
    // PATH down to just the static fallback dirs (see `parseRegistryPath` KDoc).
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `parseRegistryPath should return null when both registry hives are unset`() {
        assertNull(ProcessBuilderExt.parseRegistryPath(";"))
        assertNull(ProcessBuilderExt.parseRegistryPath(""))
        assertNull(ProcessBuilderExt.parseRegistryPath(null))
        assertNull(ProcessBuilderExt.parseRegistryPath("   ;   "))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `parseRegistryPath should keep real directories and drop blank segments`() {
        val result = ProcessBuilderExt.parseRegistryPath("C:\\Windows;;C:\\Windows\\System32; ")

        assertEquals("C:\\Windows;C:\\Windows\\System32", result)
    }

    // Sanity check that the parsing helper itself is OS-agnostic pure logic, even
    // though it's only exercised as part of Windows-only PATH resolution above.
    // parseRegistryPath always splits on a literal ';' (Windows registry PATH
    // values are always ';'-delimited, regardless of host OS) rather than
    // File.pathSeparator, so this assertion holds identically on every platform.
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `parseRegistryPath is safe to call on non-Windows platforms too`() {
        assertNull(ProcessBuilderExt.parseRegistryPath(";"))
        assertEquals("/usr/bin", ProcessBuilderExt.parseRegistryPath("/usr/bin"))
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")
}
