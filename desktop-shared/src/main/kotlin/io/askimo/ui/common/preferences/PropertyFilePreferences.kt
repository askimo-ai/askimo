/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.preferences

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Small thread-safe preference store backed by a plain Java properties file. */
internal class PropertyFilePreferences(private val path: Path) {
    private val lock = locks.computeIfAbsent(path.toAbsolutePath().normalize()) { Any() }

    fun get(key: String, default: String?): String? = synchronized(lock) {
        load().getProperty(key, default)
    }

    fun put(key: String, value: String) = synchronized(lock) {
        val properties = load()
        properties.setProperty(key, value)
        save(properties)
    }

    fun remove(key: String) = synchronized(lock) {
        val properties = load()
        properties.remove(key)
        save(properties)
    }

    fun clear() = synchronized(lock) {
        save(Properties())
    }

    private fun load(): Properties = Properties().also { properties ->
        if (Files.isRegularFile(path)) {
            Files.newInputStream(path).buffered().use(properties::load)
        }
    }

    private fun save(properties: Properties) {
        val parent = path.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}", ".tmp")
        try {
            Files.newOutputStream(temporary).buffered().use { output ->
                properties.store(output, "Askimo preferences")
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<Path, Any>()
    }
}
