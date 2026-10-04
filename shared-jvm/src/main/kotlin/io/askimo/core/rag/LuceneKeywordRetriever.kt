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
import io.askimo.core.logging.logger
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.Directory
import org.apache.lucene.store.NIOFSDirectory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lucene-based keyword search retriever using BM25 ranking.
 * Complements vector similarity search by catching exact keyword matches.
 *
 * NOTE: This retriever reads from the index only. Use LuceneIndexer for indexing operations.
 *
 * GraalVM native-image notes:
 * - Disable Lucene MemorySegments (prevents native-image link errors on some setups).
 * - Use NIOFSDirectory to avoid mmap/MemorySegment-related paths.
 */
class LuceneKeywordRetriever(
    private val projectId: String,
    private val maxResults: Int = 10,
) : ContentRetriever {

    private val log = logger<LuceneKeywordRetriever>()
    private val analyzer = StandardAnalyzer()
    private val directory: Directory

    private val indexPath: Path
        get() = RagUtils.getProjectLuceneIndexDir(projectId)

    init {
        // 1) Disable Lucene's MemorySegment-backed mmap optimizations (native-image linker friendly)
        // Must be set BEFORE any Lucene directory/index classes are initialized.
        System.setProperty("org.apache.lucene.store.MMapDirectory.enableMemorySegments", "false")

        Files.createDirectories(indexPath)

        // 2) Avoid mmap entirely: NIOFSDirectory uses NIO FileChannel read (portable & native-image friendly)
        directory = NIOFSDirectory(indexPath)
    }

    override fun retrieve(query: Query): List<Content> {
        if (!DirectoryReader.indexExists(directory)) {
            log.debug("Keyword index does not exist yet at $indexPath")
            return emptyList()
        }

        return try {
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader)
                val queryParser = QueryParser(LuceneIndexer.FIELD_CONTENT, analyzer)

                val normalizedQuery = QueryParser.escape(query.text())
                val luceneQuery = queryParser.parse(normalizedQuery)

                val topDocs = searcher.search(luceneQuery, maxResults)
                log.trace("Keyword search found ${topDocs.scoreDocs.size} results for query: ${query.text()}")

                topDocs.scoreDocs.mapNotNull { scoreDoc ->
                    val doc = searcher.storedFields().document(scoreDoc.doc)
                    val content = doc.get(LuceneIndexer.FIELD_CONTENT) ?: return@mapNotNull null

                    // Reconstruct metadata from stored fields
                    val metadataMap = mutableMapOf<String, Any>()
                    for (field in doc.fields) {
                        val name = field.name()
                        if (name.startsWith(LuceneIndexer.FIELD_META_PREFIX)) {
                            val key = name.removePrefix(LuceneIndexer.FIELD_META_PREFIX)
                            metadataMap[key] = field.stringValue()
                        }
                    }

                    val textSegment = TextSegment.from(content, Metadata.from(metadataMap))
                    Content.from(textSegment)
                }
            }
        } catch (e: Exception) {
            log.error("Unexpected error during keyword retrieval for query: '${query.text()}'", e)
            emptyList()
        }
    }
}
