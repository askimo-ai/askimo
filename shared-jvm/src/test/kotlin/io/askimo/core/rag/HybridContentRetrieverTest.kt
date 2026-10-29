/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.rag.content.Content
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.query.Query
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.KnowledgeSourcePriority
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class HybridContentRetrieverTest {

    @TempDir
    lateinit var root: Path

    private val code get() = root.resolve("code")
    private val docs get() = root.resolve("docs")

    /** A chunk named [name] indexed from `<folder>/<name>.txt`. */
    private fun chunk(name: String, folder: Path = docs): Content = Content.from(
        TextSegment.from(
            name,
            Metadata.from(mapOf("file_path" to folder.resolve("$name.txt").toAbsolutePath().toString().replace('\\', '/'))),
        ),
    )

    private fun fixed(vararg results: Content) = ContentRetriever { results.toList() }

    private fun retrieve(
        vector: List<Content>,
        keyword: List<Content>,
        vararg sources: KnowledgeSourceConfig,
        withResolver: Boolean = true,
    ): List<String> = HybridContentRetriever(
        vectorRetriever = fixed(*vector.toTypedArray()),
        keywordRetriever = fixed(*keyword.toTypedArray()),
        maxResults = 10,
        k = 60,
        priorityResolver = if (withResolver) KnowledgeSourcePriorityResolver { sources.toList() } else null,
    ).retrieve(Query.from("q")).map { it.textSegment().text() }

    private fun folder(path: Path, priority: KnowledgeSourcePriority) = LocalFoldersKnowledgeSourceConfig(path.toString(), priority = priority.weight)

    @Test
    fun `without a resolver the ranking is plain RRF`() {
        // a = 1/61 + 1/62, c = 1/63 + 1/61, b = 1/62
        val ranked = retrieve(listOf(chunk("a"), chunk("b"), chunk("c")), listOf(chunk("c"), chunk("a")), withResolver = false)

        assertEquals(listOf("a", "c", "b"), ranked)
    }

    @Test
    fun `normal priority everywhere keeps today's ranking`() {
        val vector = listOf(chunk("a"), chunk("b"), chunk("c", code))
        val keyword = listOf(chunk("c", code), chunk("a"))

        val plain = retrieve(vector, keyword, withResolver = false)
        val prioritized = retrieve(vector, keyword, folder(docs, KnowledgeSourcePriority.NORMAL), folder(code, KnowledgeSourcePriority.NORMAL))

        assertEquals(plain, prioritized)
    }

    @Test
    fun `high priority lifts a chunk but a result found by both retrievers still leads`() {
        val vector = listOf(chunk("a"), chunk("b"))
        val keyword = listOf(chunk("a"), chunk("c", code))

        // Unweighted, b and c tie at 1/62 and b stays first.
        assertEquals(listOf("a", "b", "c"), retrieve(vector, keyword, withResolver = false))
        // c = 2 * 1/62 overtakes b, but stays below a = 2/61.
        assertEquals(listOf("a", "c", "b"), retrieve(vector, keyword, folder(code, KnowledgeSourcePriority.HIGH)))
    }

    @Test
    fun `always prioritize puts a source on top from deep in a list`() {
        val vector = listOf(chunk("a"), chunk("b"), chunk("d"))
        val keyword = listOf(chunk("a"), chunk("b"), chunk("d"), chunk("e"), chunk("c", code))

        val ranked = retrieve(vector, keyword, folder(code, KnowledgeSourcePriority.ALWAYS_PRIORITIZE))

        assertEquals("c", ranked.first())
    }

    @Test
    fun `low priority pushes a source down`() {
        val vector = listOf(chunk("old", code), chunk("a"))
        val keyword = listOf(chunk("old", code), chunk("a"))

        assertEquals(listOf("old", "a"), retrieve(vector, keyword, withResolver = false))
        // old = 0.5 * 2/61 falls below a = 2/62.
        assertEquals(listOf("a", "old"), retrieve(vector, keyword, folder(code, KnowledgeSourcePriority.LOW)))
    }

    @Test
    fun `priority also applies when only one retriever returns results`() {
        val vector = listOf(chunk("a"), chunk("b"), chunk("c", code))

        assertEquals(listOf("a", "b", "c"), retrieve(vector, emptyList(), folder(code, KnowledgeSourcePriority.NORMAL)))
        // c = 2/63 beats a = 1/61.
        assertEquals(listOf("c", "a", "b"), retrieve(vector, emptyList(), folder(code, KnowledgeSourcePriority.HIGH)))
    }
}
