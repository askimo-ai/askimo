/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import java.util.Properties

/**
 * Default message resolver that reads from English properties file.
 * Used by CLI to get English strings without needing the desktop i18n system.
 */
object DefaultMessageResolver {
    private val properties: Properties by lazy {
        val props = Properties()
        try {
            // Load English properties file from classpath
            DefaultMessageResolver::class.java.classLoader
                .getResourceAsStream("i18n/messages.properties")?.use { stream ->
                    stream.reader(Charsets.UTF_8).use { reader ->
                        props.load(reader)
                    }
                }
        } catch (_: Exception) {
            // Properties not found - return empty properties
        }
        props
    }

    /**
     * Resolve a message key to its English string.
     * Returns the key itself if not found.
     */
    val resolver: (String) -> String = { key ->
        properties.getProperty(key) ?: key
    }
}
