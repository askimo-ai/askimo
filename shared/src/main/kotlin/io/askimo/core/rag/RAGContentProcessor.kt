/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.rag.content.Content
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.query.Query
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatClient
import io.askimo.core.telemetry.TelemetryCollector
import io.askimo.core.util.ProcessBuilderExt
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Decorator that adds intelligent RAG routing to content retrieval.
 *
 * Routes to one of three strategies based on [RAGIntentClassifier]:
 * - [RAGIntent.RAG]    → semantic hybrid search (HybridContentRetriever)
 * - [RAGIntent.SEARCH] → ripgrep over [knowledgeSourcePaths] for pattern/structural queries
 * - [RAGIntent.SKIP]   → empty list, no retrieval
 *
 * @property delegate              The wrapped semantic retriever (HybridContentRetriever).
 * @property knowledgeSourcePaths  Root paths of the project's knowledge sources, used for grep search.
 * @property telemetry             Optional telemetry collector.
 */
class RAGContentProcessor(
    private val delegate: ContentRetriever,
    private val classifierChatClient: ChatClient,
    private val telemetry: TelemetryCollector? = null,
    private val knowledgeSourcePaths: List<String> = emptyList(),
) : ContentRetriever {

    private val log = logger<RAGContentProcessor>()
    private val classifier = RAGIntentClassifier(classifierChatClient)

    companion object {
        /** Max characters of grep output returned as a single Content chunk. */
        private const val GREP_OUTPUT_LIMIT = 8_000
    }

    override fun retrieve(query: Query): List<Content> {
        val userMessage = query.text()
        val metadata = query.metadata()
        val rawChatMemory = metadata?.chatMemory() ?: emptyList()
        val conversationHistory = rawChatMemory.filterNot { it is SystemMessage }

        log.debug(
            "Evaluating RAG intent for query: ${userMessage.take(100)}... " +
                "(${conversationHistory.size} conversation messages)",
        )

        val classificationStartTime = System.currentTimeMillis()
        val intent = runBlocking {
            classifier.classify(userMessage, conversationHistory, knowledgeSourcePaths)
        }
        val classificationDuration = System.currentTimeMillis() - classificationStartTime

        telemetry?.recordRAGClassification(intent != RAGIntent.SKIP, classificationDuration)

        return when (intent) {
            RAGIntent.RAG -> {
                log.info("RAG triggered — semantic retrieval")
                val retrievalStartTime = System.currentTimeMillis()
                val results = delegate.retrieve(query)
                telemetry?.recordRAGRetrieval(results.size, System.currentTimeMillis() - retrievalStartTime)
                results
            }

            RAGIntent.SEARCH -> {
                log.info("SEARCH triggered — grep over ${knowledgeSourcePaths.size} path(s)")
                grepSearch(userMessage, knowledgeSourcePaths)
            }

            RAGIntent.SKIP -> {
                log.info("RAG skipped — direct chat without retrieval")
                emptyList()
            }
        }
    }

    /**
     * Searches [paths] for [userMessage]'s key identifier.
     *
     * Strategy (in order):
     * 1. ripgrep (`rg`) via [ProcessBuilderExt] — fastest, cross-platform PATH resolution
     * 2. `grep` via [ProcessBuilderExt] — universal fallback
     * 3. Pure-Kotlin file walk — works even when no external tools are available
     *       (e.g. sandboxed environment, Windows without grep)
     */
    private fun grepSearch(userMessage: String, paths: List<String>): List<Content> {
        if (paths.isEmpty()) return emptyList()

        val searchTerm = extractSearchTerm(userMessage)
        if (searchTerm.isBlank()) return emptyList()

        log.debug("SEARCH: term='$searchTerm' paths=${paths.size}")

        // Try external tools first (rg → grep), fall back to pure Kotlin
        val output = runExternalSearch(searchTerm, paths)
            ?: kotlinSearch(searchTerm, paths)

        return if (output.isBlank()) {
            log.debug("SEARCH: no matches found for '$searchTerm'")
            emptyList()
        } else {
            log.debug("SEARCH: returning ${output.lines().size} match line(s) for '$searchTerm'")
            listOf(Content.from("Search results for \"$searchTerm\" across project files:\n\n$output"))
        }
    }

    /**
     * Attempts to run `rg` or `grep` via [ProcessBuilderExt].
     * Returns stdout on success, null if the tool is unavailable or execution fails.
     */
    private fun runExternalSearch(searchTerm: String, paths: List<String>): String? {
        // Prefer ripgrep; fall back to grep
        val (tool, args) = if (toolAvailable("rg")) {
            "rg" to listOf("--no-heading", "-n", "-i", "--max-count", "5", "--max-filesize", "1M", searchTerm)
        } else if (toolAvailable("grep")) {
            "grep" to listOf("-rn", "-i", "-m", "5", searchTerm)
        } else {
            return null
        }

        return try {
            val cmd = listOf(tool) + args + paths
            val process = ProcessBuilderExt(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().take(GREP_OUTPUT_LIMIT)
            process.waitFor()
            output.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.debug("SEARCH: external tool '$tool' failed: ${e.message}")
            null
        }
    }

    /** Checks whether [toolName] is on PATH using [ProcessBuilderExt]'s resolver. */
    private fun toolAvailable(toolName: String): Boolean = runCatching {
        val resolved = ProcessBuilderExt.resolveCommand(listOf(toolName)).first()
        File(resolved).exists() || resolved == toolName // resolveCommand may return the plain name as fallback
    }.getOrDefault(false)

    /**
     * Pure-Kotlin recursive file search — no external processes.
     * Walks each root in [paths], reads every file (skipping binaries and large
     * files), and collects lines containing [searchTerm] (case-insensitive).
     * Caps at [GREP_OUTPUT_LIMIT] characters and 200 match lines.
     */
    private fun kotlinSearch(searchTerm: String, paths: List<String>): String {
        val termLower = searchTerm.lowercase()
        val matchLines = mutableListOf<String>()
        var totalChars = 0

        outer@ for (rootPath in paths) {
            val root = File(rootPath)
            if (!root.exists()) continue
            val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile } else sequenceOf(root)

            for (file in files) {
                // Skip binaries and large files (> 1 MB)
                if (file.length() > 1_048_576L) continue
                if (isBinaryFile(file)) continue

                try {
                    file.bufferedReader().useLines { lines ->
                        lines.forEachIndexed { lineIdx, line ->
                            if (line.lowercase().contains(termLower)) {
                                val entry = "${file.absolutePath}:${lineIdx + 1}: $line"
                                matchLines.add(entry)
                                totalChars += entry.length + 1
                                if (matchLines.size >= 200 || totalChars >= GREP_OUTPUT_LIMIT) {
                                    return@useLines
                                }
                            }
                        }
                    }
                } catch (_: Exception) { /* skip unreadable files */ }

                if (matchLines.size >= 200 || totalChars >= GREP_OUTPUT_LIMIT) break@outer
            }
        }

        return matchLines.joinToString("\n")
    }

    /** Heuristic binary detection: check first 512 bytes for null bytes. */
    private fun isBinaryFile(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val buf = ByteArray(512)
            val read = stream.read(buf)
            buf.take(read).any { it == 0.toByte() }
        }
    }.getOrDefault(false)

    /**
     * Extracts a compact search term from the user's natural language message.
     * Prefers camelCase/PascalCase identifiers, annotation names, or quoted strings.
     * Falls back to the longest word if none found.
     */
    private fun extractSearchTerm(message: String): String {
        // 1. Quoted string: "groupId" or 'groupId'
        val quoted = Regex("""["']([^"']{2,60})["']""").find(message)?.groupValues?.get(1)
        if (!quoted.isNullOrBlank()) return quoted

        // 2. @Annotation or annotation-like token
        val annotation = Regex("""@(\w{2,40})""").find(message)?.groupValues?.get(1)
        if (!annotation.isNullOrBlank()) return annotation

        // 3. CamelCase / PascalCase identifier (e.g. KafkaConsumer, groupId)
        val camel = Regex("""\b([A-Z][a-z]+[A-Za-z0-9]{1,40}|[a-z]+[A-Z][A-Za-z0-9]{1,40})\b""")
            .findAll(message).maxByOrNull { it.value.length }?.value
        if (!camel.isNullOrBlank()) return camel

        // 4. Longest plain word ≥ 4 chars
        return message.split(Regex("\\W+"))
            .filter { it.length >= 4 }
            .maxByOrNull { it.length }
            ?: ""
    }
}
