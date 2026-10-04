/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core

import java.util.Properties

object VersionInfo {
    private val props: Properties by lazy {
        Properties().apply {
            VersionInfo::class.java.getResourceAsStream("/about.properties")?.use { load(it) }
        }
    }

    val name = props.getProperty("name", "Askimo")
    val version = props.getProperty("version", "unknown")
    val author = props.getProperty("author", "unknown")
    val buildDate = props.getProperty("buildDate", "unknown")
    val license = props.getProperty("license", "unknown")
    val homepage = props.getProperty("homepage", "unknown")
    val buildJdk = props.getProperty("buildJdk", "unknown")

    // Runtime info (useful for native vs jar runs)
    val runtimeVm: String = System.getProperty("java.vm.name") ?: "unknown"
    val runtimeVersion: String = System.getProperty("java.version") ?: "unknown"
}
