/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Contract for resolving all Askimo home-related paths.
 *
 * The personal edition implements this with [PersonalAskimoHome] (profileDirName = "personal").
 * The team edition (closed source) provides its own implementation with profileDirName = "team".
 *
 * Registered at startup via [AskimoHome.register] so all shared/CLI code can call
 * [AskimoHome] statically without knowing which edition is running.
 */
interface AskimoHomeBase {

    /** The subdirectory under rootBase() where this edition stores its data. */
    val profileDirName: String

    /**
     * Returns the root ~/.askimo directory.
     * Respects the ASKIMO_HOME environment variable override.
     */
    fun rootBase(): Path {
        val override = System.getenv("ASKIMO_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        val userHome = System.getProperty("user.home")
        val base = override?.let { Paths.get(it) } ?: Paths.get(userHome).resolve(".askimo")
        return base.toAbsolutePath().normalize()
    }

    /**
     * Returns the profile home directory (e.g. ~/.askimo/personal).
     * All app data paths resolve under this directory.
     */
    fun base(): Path = rootBase().resolve(profileDirName).also { it.toFile().mkdirs() }

    fun userHome(): Path = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()

    fun expandTilde(raw: String): Path = when {
        raw == "~" -> userHome()
        raw.startsWith("~/") -> userHome().resolve(raw.removePrefix("~/"))
        else -> Paths.get(raw)
    }.toAbsolutePath().normalize()
}
