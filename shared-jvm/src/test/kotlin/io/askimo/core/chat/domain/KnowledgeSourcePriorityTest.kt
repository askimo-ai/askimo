/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgeSourcePriorityTest {

    @Test
    fun `sources saved before priority existed decode as normal`() {
        val saved = KnowledgeSourceSerializer.serialize(
            listOf(
                LocalFoldersKnowledgeSourceConfig("/repo/src", watchForChanges = false),
                LocalFilesKnowledgeSourceConfig("/repo/spec.pdf"),
                UrlKnowledgeSourceConfig("https://example.com/docs"),
            ),
        )
        val withoutPriority = saved.replace(Regex(""","priority":[0-9.]+"""), "")
        assertFalse(withoutPriority.contains("priority"))

        val sources = KnowledgeSourceSerializer.deserialize(withoutPriority)

        assertEquals(3, sources.size)
        assertTrue(sources.all { it.priority == KnowledgeSourcePriority.NORMAL.weight })
        assertEquals(false, (sources[0] as LocalFoldersKnowledgeSourceConfig).watchForChanges)
    }

    @Test
    fun `legacy path array decodes as normal`() {
        val sources = KnowledgeSourceSerializer.deserialize("""["/repo/src", "/repo/docs"]""")

        assertEquals(listOf(1.0, 1.0), sources.map { it.priority })
    }

    @Test
    fun `priority survives a save and load`() {
        val sources = listOf(
            LocalFoldersKnowledgeSourceConfig("/repo/src", priority = KnowledgeSourcePriority.HIGH.weight),
            UrlKnowledgeSourceConfig("https://example.com/docs", priority = KnowledgeSourcePriority.LOW.weight),
        )

        assertEquals(sources, KnowledgeSourceSerializer.deserialize(KnowledgeSourceSerializer.serialize(sources)))
    }

    @Test
    fun `withPriority keeps the source type and its other fields`() {
        val folder = LocalFoldersKnowledgeSourceConfig("/repo/src", watchForChanges = false, config = mapOf("excludePatterns" to "build"))

        val updated = folder.withPriority(KnowledgeSourcePriority.ALWAYS_PRIORITIZE.weight)

        assertEquals(folder.copy(priority = KnowledgeSourcePriority.ALWAYS_PRIORITIZE.weight), updated)
    }

    @Test
    fun `fromWeight maps a stored weight to the nearest level`() {
        assertEquals(KnowledgeSourcePriority.NORMAL, KnowledgeSourcePriority.fromWeight(1.0))
        assertEquals(KnowledgeSourcePriority.HIGH, KnowledgeSourcePriority.fromWeight(2.0))
        assertEquals(KnowledgeSourcePriority.LOW, KnowledgeSourcePriority.fromWeight(0.6))
        assertEquals(KnowledgeSourcePriority.ALWAYS_PRIORITIZE, KnowledgeSourcePriority.fromWeight(80.0))
    }
}
