/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Static facade over [AskimoHomeBase] — all shared, desktop, and CLI code calls this object.
 *
 * An [AskimoHomeBase] implementation must be registered once at startup via [register]
 * before any path methods are used:
 *
 *   // desktop-personal (via Koin bridge in Main.kt):
 *   AskimoHome.register(get<AskimoHomeBase>())
 *
 *   // CLI:
 *   AskimoHome.register(PersonalAskimoHome)
 *
 * For tests: use [withTestBase] to redirect paths to a temp directory without
 * touching the registered impl or other threads.
 */
object AskimoHome {

    @Volatile
    private var impl: AskimoHomeBase? = null

    // Thread-local override for testing — does not affect other threads or the registered impl
    private val testBaseOverride = ThreadLocal<AskimoHomeBase?>()

    /**
     * Registers the [AskimoHomeBase] implementation for this edition.
     * Must be called once at startup before any path methods are used.
     */
    fun register(home: AskimoHomeBase) {
        impl = home
    }

    /**
     * Single point of truth - checks test override first, then registered impl.
     * All path methods delegate to this.
     */
    private fun get(): AskimoHomeBase = testBaseOverride.get()
        ?: impl
        ?: error("AskimoHome not registered. Call AskimoHome.register() at startup.")

    // ── Path resolution ──────────────────────────────────────────────────────

    /**
     * Returns the profile home directory (e.g. ~/.askimo/personal).
     * All app data paths resolve under this directory.
     */
    fun base(): Path = get().base()

    fun recipesDir(): Path = base().resolve("recipes")
    fun plansDir(): Path = base().resolve("plans")
    fun skillsDir(): Path = base().resolve("skills")
    fun skillsWorkspaceDir(): Path = base().resolve("skills-workspace")
    fun projectsDir(): Path = base().resolve("projects")
    fun sessionFile(): Path = base().resolve("session")
    fun encryptionKeyFile(): Path = base().resolve(".key")

    fun userHome(): Path = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()

    fun expandTilde(raw: String): Path = get().expandTilde(raw)

    // ── Test support ─────────────────────────────────────────────────────────

    /**
     * Temporarily overrides the base directory for the current thread.
     * Returns a [TestBaseScope] that restores the original base when closed.
     *
     * Usage:
     * ```
     * AskimoHome.withTestBase(tempDir).use {
     *     // AskimoHome.base() resolves under tempDir/personal/ on this thread
     * }
     * ```
     */
    fun withTestBase(testBase: Path, profileName: String = "personal"): TestBaseScope {
        val normalizedBase = testBase.toAbsolutePath().normalize()
        val testImpl = object : AskimoHomeBase {
            override val profileDirName = profileName
            override fun rootBase() = normalizedBase
        }
        testBaseOverride.set(testImpl)
        return TestBaseScope()
    }

    class TestBaseScope : AutoCloseable {
        override fun close() {
            testBaseOverride.remove()
        }
    }
}
