/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import dev.langchain4j.data.segment.TextSegment
import io.askimo.core.context.AppContext
import io.askimo.core.rag.extraction.FileResourceIdentifier
import io.askimo.core.rag.extraction.LocalFileContentExtractor
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Handles file processing by coordinating content extraction and text processing.
 * Delegates to:
 * - ContentExtractor: for extracting text from files
 * - TextProcessor: for chunking and creating text segments
 *
 * This class acts as a facade that maintains backward compatibility with existing code.
 */
class ResourceContentProcessor(
    private val appContext: AppContext,
) {

    /**
     * Content extractor for extracting text from local files.
     */
    private val localFileContentExtractor = LocalFileContentExtractor()

    /**
     * Text processor for chunking and creating segments.
     */
    private val textProcessor = TextProcessor(appContext)

    /**
     * Extract text from a file using ContentExtractor.
     * Supports text files, PDF, DOCX, and other formats via Apache Tika.
     */
    fun extractTextFromFile(filePath: Path): String? {
        val resourceIdentifier = FileResourceIdentifier(filePath)
        return localFileContentExtractor.extractContent(resourceIdentifier)
    }

    /**
     * Check if a file is a text-based file where line numbers are meaningful.
     * Returns false for binary files like PDF, DOCX, etc.
     */
    fun isTextFile(filePath: Path): Boolean {
        val resourceIdentifier = FileResourceIdentifier(filePath)
        return localFileContentExtractor.isTextFile(resourceIdentifier)
    }

    /**
     * Chunk text into segments using dynamically calculated chunk size and overlap.
     * Delegates to TextProcessor.
     */
    fun chunkText(text: String): List<String> = textProcessor.chunkText(text)

    /**
     * Chunk text with line number tracking for text files.
     * Delegates to TextProcessor.
     */
    fun chunkTextWithLineNumbers(text: String): List<TextProcessor.ChunkWithLineNumbers> = textProcessor.chunkTextWithLineNumbers(text)

    /**
     * Create a TextSegment with file-specific metadata including line numbers.
     * This should be used for text files where line numbers are meaningful.
     * Note: Caller must ensure chunk is not blank.
     */
    fun createTextSegmentWithMetadata(
        chunk: String,
        filePath: Path,
        chunkIndex: Int,
        totalChunks: Int,
        startLine: Int? = null,
        endLine: Int? = null,
    ): TextSegment {
        val absolutePath = filePath.toAbsolutePath()

        // Build file-specific metadata
        val metadata = mutableMapOf(
            "file_path" to absolutePath.toString().replace('\\', '/'),
            "file_name" to filePath.fileName.toString(),
            "extension" to filePath.extension,
            "chunk_index" to chunkIndex.toString(),
            "chunk_total" to totalChunks.toString(),
        )

        // Add line number metadata if provided
        if (startLine != null && endLine != null) {
            metadata["start_line"] = startLine.toString()
            metadata["end_line"] = endLine.toString()
        }

        return textProcessor.createTextSegment(chunk, metadata)
    }

    /**
     * Create all TextSegments for a file, choosing line-aware or plain chunking automatically.
     * Returns null if the file has no extractable content or produces no valid chunks.
     */
    fun createSegmentsForFile(filePath: Path): List<TextSegment>? {
        val text = extractTextFromFile(filePath) ?: return null
        if (text.isBlank()) return null

        return if (isTextFile(filePath)) {
            val chunks = chunkTextWithLineNumbers(text)
            if (chunks.isEmpty()) return null
            chunks.mapIndexed { idx, chunkData ->
                createTextSegmentWithMetadata(
                    chunk = chunkData.text,
                    filePath = filePath,
                    chunkIndex = idx,
                    totalChunks = chunks.size,
                    startLine = chunkData.startLine,
                    endLine = chunkData.endLine,
                )
            }
        } else {
            val chunks = chunkText(text)
            if (chunks.isEmpty()) return null
            chunks.mapIndexed { idx, chunk ->
                createTextSegmentWithMetadata(
                    chunk = chunk,
                    filePath = filePath,
                    chunkIndex = idx,
                    totalChunks = chunks.size,
                )
            }
        }
    }

    /**
     * Process web content and create text segments with metadata.
     * Chunks the HTML/text content and creates segments with URL metadata.
     */
    fun processWebContent(
        url: String,
        content: String,
        metadata: Map<String, String>,
    ): List<TextSegment> {
        // Extract text from HTML if needed (basic implementation)
        val textContent = extractTextFromHtml(content)

        if (textContent.isBlank()) {
            return emptyList()
        }

        // Chunk the text
        val chunks = chunkText(textContent)

        // Create text segments with metadata
        return chunks.mapIndexed { index, chunk ->
            val segmentMetadata = metadata.toMutableMap()
            segmentMetadata["chunk_index"] = index.toString()
            segmentMetadata["chunk_total"] = chunks.size.toString()
            segmentMetadata["url"] = url

            textProcessor.createTextSegment(chunk, segmentMetadata)
        }
    }

    /**
     * Basic HTML text extraction.
     * Removes HTML tags and extracts text content.
     */
    private fun extractTextFromHtml(html: String): String {
        // Simple regex-based HTML tag removal
        // For production, consider using a proper HTML parser like JSoup
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&[a-z]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
