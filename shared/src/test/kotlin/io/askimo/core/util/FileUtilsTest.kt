/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileUtilsTest {

    @Test
    fun `parseFileUrl should parse standard unix file url with range`() {
        val parsed = parseFileUrl("file:///Users/dev/project/Main.kt#L10-L20")

        assertEquals("/Users/dev/project/Main.kt", parsed.filePath)
        assertEquals(10..20, parsed.lineRange)
    }

    @Test
    fun `parseFileUrl should parse non-standard file url missing third slash`() {
        val parsed = parseFileUrl(
            "file://Users/hainguye/projects/pdh-assortment/pdh-assortment-preview-generator-service/src/main/java/com/sbux/pdh/assortment/previewgenerator/service/consumer/KafkaConsumer.java#L1-L96",
        )

        assertEquals(
            "/Users/hainguye/projects/pdh-assortment/pdh-assortment-preview-generator-service/src/main/java/com/sbux/pdh/assortment/previewgenerator/service/consumer/KafkaConsumer.java",
            parsed.filePath,
        )
        assertEquals(1..96, parsed.lineRange)
    }

    @Test
    fun `parseFileUrl should parse single-line fragment`() {
        val parsed = parseFileUrl("file:///Users/dev/project/Main.kt#L42")

        assertEquals("/Users/dev/project/Main.kt", parsed.filePath)
        assertEquals(42..42, parsed.lineRange)
    }

    @Test
    fun `parseFileUrl should decode url encoded path`() {
        val parsed = parseFileUrl("file:///Users/dev/My%20Project/README.md#L5-L7")

        assertEquals("/Users/dev/My Project/README.md", parsed.filePath)
        assertEquals(5..7, parsed.lineRange)
    }

    @Test
    fun `parseFileUrl should return null range when fragment is absent or unsupported`() {
        val noFragment = parseFileUrl("file:///Users/dev/project/Main.kt")
        val unsupported = parseFileUrl("file:///Users/dev/project/Main.kt#section-1")

        assertEquals("/Users/dev/project/Main.kt", noFragment.filePath)
        assertNull(noFragment.lineRange)

        assertEquals("/Users/dev/project/Main.kt", unsupported.filePath)
        assertNull(unsupported.lineRange)
    }

    @Test
    fun `parseFileUrl should parse standard windows file url with range`() {
        val parsed = parseFileUrl("file:///C:/Users/dev/project/Main.kt#L3-L9")

        assertEquals("C:/Users/dev/project/Main.kt", parsed.filePath)
        assertEquals(3..9, parsed.lineRange)
    }

    @Test
    fun `parseFileUrl should parse non-standard windows file url missing third slash`() {
        val parsed = parseFileUrl("file://C:/Users/dev/project/Main.kt#L12")

        assertEquals("C:/Users/dev/project/Main.kt", parsed.filePath)
        assertEquals(12..12, parsed.lineRange)
    }

    @Test
    fun `parseFileUrl should decode url encoded windows path`() {
        val parsed = parseFileUrl("file:///C:/Users/dev/My%20Project/README.md#L5-L7")

        assertEquals("C:/Users/dev/My Project/README.md", parsed.filePath)
        assertEquals(5..7, parsed.lineRange)
    }
}
