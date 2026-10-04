/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.rag.content.Content
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class MetadataAwareContentInjectorTest {

    @Test
    fun `should format citation with absolute path when enabled`() {
        // Given
        val injector = MetadataAwareContentInjector(
            citationStyle = MetadataAwareContentInjector.CitationStyle.COMPACT,
            useAbsolutePaths = true,
        )

        val segment = TextSegment.from(
            "This is sample content from a file.",
            Metadata.from(
                mapOf(
                    "file_name" to "README.md",
                    "file_path" to "/Users/test/project/README.md",
                    "start_line" to 10,
                    "end_line" to 15,
                ),
            ),
        )

        val content = Content.from(segment)
        val userMessage = UserMessage.from("What is in the README?")

        // When
        val result = injector.inject(listOf(content), userMessage) as UserMessage

        // Then
        val resultText = result.singleText()
        assertTrue(resultText.contains("file:///Users/test/project/README.md#L10-L15"))
        assertTrue(resultText.contains("`README.md`"))
        assertTrue(resultText.contains("lines 10-15"))
    }

    @Test
    fun `should format citation with relative path when absolute paths disabled`() {
        // Given
        val injector = MetadataAwareContentInjector(
            citationStyle = MetadataAwareContentInjector.CitationStyle.COMPACT,
            useAbsolutePaths = false,
        )

        val segment = TextSegment.from(
            "This is sample content from a file.",
            Metadata.from(
                mapOf(
                    "file_name" to "README.md",
                    "file_path" to "/Users/test/project/README.md",
                    "start_line" to 10,
                    "end_line" to 15,
                ),
            ),
        )

        val content = Content.from(segment)
        val userMessage = UserMessage.from("What is in the README?")

        // When
        val result = injector.inject(listOf(content), userMessage) as UserMessage

        // Then
        val resultText = result.singleText()
        assertTrue(!resultText.contains("file://"), "Expected no file:// in result when useAbsolutePaths=false")
        assertTrue(resultText.contains("Source: `README.md`"))
        assertTrue(resultText.contains("lines 10-15"))
    }

    @Test
    fun `should handle minimal citation style with absolute paths`() {
        // Given
        val injector = MetadataAwareContentInjector(
            citationStyle = MetadataAwareContentInjector.CitationStyle.MINIMAL,
            useAbsolutePaths = true,
        )

        val segment = TextSegment.from(
            "Content here.",
            Metadata.from(
                mapOf(
                    "file_name" to "config.yml",
                    "file_path" to "/etc/app/config.yml",
                ),
            ),
        )

        val content = Content.from(segment)
        val userMessage = UserMessage.from("What's in config?")

        // When
        val result = injector.inject(listOf(content), userMessage) as UserMessage

        // Then
        val resultText = result.singleText()
        assertTrue(resultText.contains("file:///etc/app/config.yml"))
        assertTrue(resultText.contains("`config.yml`"))
    }

    @Test
    fun `should handle detailed citation style with absolute paths`() {
        // Given
        val injector = MetadataAwareContentInjector(
            citationStyle = MetadataAwareContentInjector.CitationStyle.DETAILED,
            useAbsolutePaths = true,
        )

        val segment = TextSegment.from(
            "Detailed content.",
            Metadata.from(
                mapOf(
                    "file_name" to "Main.kt",
                    "file_path" to "/Users/dev/project/src/Main.kt",
                    "start_line" to 50,
                    "end_line" to 60,
                ),
            ),
        )

        val content = Content.from(segment)
        val userMessage = UserMessage.from("Show me Main.kt")

        // When
        val result = injector.inject(listOf(content), userMessage) as UserMessage

        // Then
        val resultText = result.singleText()
        assertTrue(resultText.contains("file:///Users/dev/project/src/Main.kt#L50-L60"))
        assertTrue(resultText.contains("Path: `/Users/dev/project/src/Main.kt`"))
        assertTrue(resultText.contains("Lines: 50-60"))
    }

    @Test
    fun `should handle multiple contents with different files`() {
        // Given
        val injector = MetadataAwareContentInjector(
            citationStyle = MetadataAwareContentInjector.CitationStyle.COMPACT,
            useAbsolutePaths = true,
        )

        val segment1 = TextSegment.from(
            "Content from first README.",
            Metadata.from(
                mapOf(
                    "file_name" to "README.md",
                    "file_path" to "/project1/README.md",
                    "start_line" to 1,
                    "end_line" to 10,
                ),
            ),
        )

        val segment2 = TextSegment.from(
            "Content from second README.",
            Metadata.from(
                mapOf(
                    "file_name" to "README.md",
                    "file_path" to "/project2/docs/README.md",
                    "start_line" to 20,
                    "end_line" to 30,
                ),
            ),
        )

        val contents = listOf(Content.from(segment1), Content.from(segment2))
        val userMessage = UserMessage.from("What's in the READMEs?")

        // When
        val result = injector.inject(contents, userMessage) as UserMessage

        // Then
        val resultText = result.singleText()
        // Should contain both full paths to distinguish the two README.md files
        assertTrue(resultText.contains("file:///project1/README.md#L1-L10"))
        assertTrue(resultText.contains("file:///project2/docs/README.md#L20-L30"))
    }

    @Test
    fun `should handle missing metadata gracefully`() {
        // Given
        val injector = MetadataAwareContentInjector(
            citationStyle = MetadataAwareContentInjector.CitationStyle.COMPACT,
            useAbsolutePaths = true,
        )

        val segment = TextSegment.from(
            "Content without metadata.",
            Metadata.from(emptyMap<String, Any>()),
        )

        val content = Content.from(segment)
        val userMessage = UserMessage.from("What's this?")

        // When
        val result = injector.inject(listOf(content), userMessage) as UserMessage

        // Then
        val resultText = result.singleText()
        assertTrue(resultText.contains("Source: `unknown`"))
    }
}
