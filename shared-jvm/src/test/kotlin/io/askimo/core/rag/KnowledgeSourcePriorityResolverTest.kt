/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.rag.content.Content
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.KnowledgeSourcePriority
import io.askimo.core.chat.domain.LocalFilesKnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.rag.indexing.ResourceContentProcessor
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

class KnowledgeSourcePriorityResolverTest {

    @TempDir
    lateinit var root: Path

    private val high = KnowledgeSourcePriority.HIGH.weight
    private val low = KnowledgeSourcePriority.LOW.weight
    private val normal = KnowledgeSourcePriority.NORMAL.weight

    private fun content(metadata: Map<String, Any>): Content = Content.from(TextSegment.from("chunk", Metadata.from(metadata)))

    private fun fileChunk(path: Path): Content = content(mapOf("file_path" to path.toAbsolutePath().toString().replace('\\', '/')))

    private fun resolver(vararg sources: KnowledgeSourceConfig, ignoreCase: Boolean = false) = KnowledgeSourcePriorityResolver(ignoreCase) { sources.toList() }

    @Test
    fun `chunks from a folder get that folder's priority`() {
        val code = root.resolve("code")
        val weightOf = resolver(LocalFoldersKnowledgeSourceConfig(code.toString(), priority = KnowledgeSourcePriority.HIGH)).snapshot()

        assertEquals(high, weightOf(fileChunk(code.resolve("src/Main.kt"))))
    }

    @Test
    fun `folder prefix only matches on a path boundary`() {
        val docs = root.resolve("docs")
        val weightOf = resolver(LocalFoldersKnowledgeSourceConfig(docs.toString(), priority = KnowledgeSourcePriority.HIGH)).snapshot()

        assertEquals(normal, weightOf(fileChunk(root.resolve("docs-old/readme.md"))))
    }

    @Test
    fun `nested folder wins over its parent`() {
        val project = root.resolve("project")
        val weightOf = resolver(
            LocalFoldersKnowledgeSourceConfig(project.toString(), priority = KnowledgeSourcePriority.HIGH),
            LocalFoldersKnowledgeSourceConfig(project.resolve("generated").toString(), priority = KnowledgeSourcePriority.LOW),
        ).snapshot()

        assertEquals(low, weightOf(fileChunk(project.resolve("generated/Api.kt"))))
        assertEquals(high, weightOf(fileChunk(project.resolve("src/Main.kt"))))
    }

    @Test
    fun `file sources match their exact path`() {
        val spec = root.resolve("spec.pdf")
        val weightOf = resolver(LocalFilesKnowledgeSourceConfig(spec.toString(), priority = KnowledgeSourcePriority.HIGH)).snapshot()

        assertEquals(high, weightOf(fileChunk(spec)))
        assertEquals(normal, weightOf(fileChunk(root.resolve("other.pdf"))))
    }

    @Test
    fun `url sources match their exact url and web results stay normal`() {
        val weightOf = resolver(UrlKnowledgeSourceConfig("https://example.com/docs", priority = KnowledgeSourcePriority.LOW)).snapshot()

        assertEquals(low, weightOf(content(mapOf("source_type" to "url", "url" to "https://example.com/docs"))))
        assertEquals(normal, weightOf(content(mapOf("source" to "https://example.com/docs", "type" to "web"))))
    }

    @Test
    fun `file paths with backslashes still match`() {
        val code = root.resolve("code")
        val weightOf = resolver(LocalFoldersKnowledgeSourceConfig(code.toString(), priority = KnowledgeSourcePriority.HIGH)).snapshot()
        val backslashed = code.toAbsolutePath().toString().replace('/', '\\') + "\\Main.kt"

        assertEquals(high, weightOf(content(mapOf("file_path" to backslashed))))
    }

    @Test
    fun `path case only matters when the file system is case sensitive`() {
        val folder = root.resolve("Docs")
        val chunk = content(mapOf("file_path" to root.resolve("docs/a.md").toAbsolutePath().toString().replace('\\', '/')))
        val source = LocalFoldersKnowledgeSourceConfig(folder.toString(), priority = KnowledgeSourcePriority.HIGH)

        assertEquals(high, resolver(source, ignoreCase = true).snapshot()(chunk))
        assertEquals(normal, resolver(source, ignoreCase = false).snapshot()(chunk))
    }

    @Test
    fun `chunks without a known source stay normal`() {
        val weightOf = resolver(LocalFoldersKnowledgeSourceConfig(root.resolve("code").toString(), priority = KnowledgeSourcePriority.HIGH)).snapshot()

        assertEquals(normal, weightOf(fileChunk(root.resolve("elsewhere/a.kt"))))
        assertEquals(normal, weightOf(content(emptyMap())))
    }

    @Test
    fun `matches segments produced by indexing`() {
        val code = root.resolve("code")
        val file = code.resolve("src/Main.kt")
        // A folder as a file chooser might hand it over: platform separators, trailing separator.
        val source = LocalFoldersKnowledgeSourceConfig(code.toString() + File.separator, priority = KnowledgeSourcePriority.HIGH)

        AskimoHome.withTestBase(root.resolve("home")).use {
            AppContext.reset()
            try {
                val processor = ResourceContentProcessor(AppContext.initialize(ExecutionMode.STATELESS_MODE))
                val segment = processor.createTextSegmentWithMetadata("fun main() {}", file, chunkIndex = 0, totalChunks = 1)

                assertEquals(high, resolver(source).snapshot()(Content.from(segment)))
            } finally {
                AppContext.reset()
            }
        }
    }

    @Test
    fun `each snapshot reads the current sources`() {
        val code = root.resolve("code")
        var sources = listOf<KnowledgeSourceConfig>(LocalFoldersKnowledgeSourceConfig(code.toString()))
        val resolver = KnowledgeSourcePriorityResolver { sources }
        val chunk = fileChunk(code.resolve("Main.kt"))

        assertEquals(normal, resolver.snapshot()(chunk))
        sources = listOf(LocalFoldersKnowledgeSourceConfig(code.toString(), priority = KnowledgeSourcePriority.HIGH))
        assertEquals(high, resolver.snapshot()(chunk))
    }
}
