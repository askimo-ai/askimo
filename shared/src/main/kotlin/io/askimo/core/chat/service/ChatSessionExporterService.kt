/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.PaginationDirection
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Service for exporting chat session history to various file formats.
 *
 * This service handles exporting entire chat sessions including all messages
 * and metadata using cursor-based pagination to efficiently handle large sessions.
 *
 * Supported formats:
 * - Markdown: Human-readable format for documentation and sharing
 * - JSON: Machine-readable format for programmatic access and re-importing
 * - HTML: Formatted view for browser viewing
 */
class ChatSessionExporterService(
    private val sessionRepository: ChatSessionRepository = DatabaseManager.getInstance().getChatSessionRepository(),
    private val messageRepository: ChatMessageRepository = DatabaseManager.getInstance().getChatMessageRepository(),
) {
    private val log = logger<ChatSessionExporterService>()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Markdown parser and HTML renderer for assistant messages
    private val markdownParser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()
    private val htmlRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()

    companion object {
        // HTML Template Parts
        private const val HTML_DOCTYPE = "<!DOCTYPE html>"
        private const val HTML_OPEN = "<html lang=\"en\">"
        private const val HTML_CLOSE = "</html>"
        private const val BODY_OPEN = "<body>"
        private const val BODY_CLOSE = "</body>"

        private const val HTML_CSS = """
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                max-width: 900px;
                margin: 0 auto;
                padding: 20px;
                background-color: #f5f5f5;
                line-height: 1.6;
            }
            .header {
                background-color: white;
                padding: 20px;
                border-radius: 8px;
                margin-bottom: 20px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            }
            h1 {
                margin-top: 0;
                color: #333;
            }
            .metadata {
                color: #666;
                font-size: 0.9em;
            }
            .message {
                background-color: white;
                padding: 15px;
                margin-bottom: 15px;
                border-radius: 8px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            }
            .message-header {
                display: flex;
                justify-content: space-between;
                margin-bottom: 10px;
                padding-bottom: 10px;
                border-bottom: 1px solid #eee;
            }
            .role {
                font-weight: bold;
                text-transform: uppercase;
                font-size: 0.85em;
            }
            .role.user {
                color: #2563eb;
            }
            .role.assistant {
                color: #059669;
            }
            .role.system {
                color: #7c3aed;
            }
            .timestamp {
                color: #999;
                font-size: 0.85em;
            }
            .content {
                word-wrap: break-word;
            }
            .content.user-message {
                white-space: pre-wrap;
            }
            .content pre {
                background-color: #f6f8fa;
                padding: 12px;
                border-radius: 6px;
                overflow-x: auto;
                border: 1px solid #e1e4e8;
                margin: 12px 0;
            }
            .content code {
                background-color: #f6f8fa;
                padding: 2px 6px;
                border-radius: 3px;
                font-family: 'Courier New', Courier, monospace;
                font-size: 0.9em;
                border: 1px solid #e1e4e8;
            }
            .content pre code {
                background-color: transparent;
                padding: 0;
                border: none;
            }
            .content h1, .content h2, .content h3, .content h4, .content h5, .content h6 {
                margin-top: 16px;
                margin-bottom: 8px;
                font-weight: 600;
                line-height: 1.25;
            }
            .content h1 { font-size: 2em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }
            .content h2 { font-size: 1.5em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }
            .content h3 { font-size: 1.25em; }
            .content h4 { font-size: 1em; }
            .content h5 { font-size: 0.875em; }
            .content h6 { font-size: 0.85em; color: #6a737d; }
            .content ul, .content ol {
                margin: 12px 0;
                padding-left: 24px;
            }
            .content li {
                margin: 4px 0;
            }
            .content li > p {
                margin: 4px 0;
            }
            .content blockquote {
                margin: 12px 0;
                padding: 0 16px;
                border-left: 4px solid #dfe2e5;
                color: #6a737d;
            }
            .content table {
                border-collapse: collapse;
                width: 100%;
                margin: 16px 0;
                display: block;
                overflow-x: auto;
            }
            .content th, .content td {
                border: 1px solid #dfe2e5;
                padding: 8px 12px;
                text-align: left;
            }
            .content th {
                background-color: #f6f8fa;
                font-weight: 600;
            }
            .content tr:nth-child(even) {
                background-color: #f6f8fa;
            }
            .content p {
                margin: 8px 0;
            }
            .content a {
                color: #0366d6;
                text-decoration: none;
            }
            .content a:hover {
                text-decoration: underline;
            }
            .footer {
                text-align: center;
                margin-top: 40px;
                color: #999;
                font-size: 0.9em;
            }
        """
    }

    /**
     * Export a chat session to a Markdown file.
     *
     * Uses streaming approach to write messages to file as they are loaded from pagination,
     * avoiding loading all messages into memory at once.
     *
     * @param sessionId The ID of the session to export
     * @param filename The path to the output file
     * @return Result indicating success or failure with error message
     */
    fun exportToMarkdown(sessionId: String, filename: String): Result<Unit> {
        return try {
            val session = sessionRepository.getSession(sessionId)
                ?: return Result.failure(Exception("Session not found: $sessionId"))

            val file = File(filename)
            file.parentFile?.mkdirs()

            file.bufferedWriter().use { writer ->
                writeMarkdownHeader(writer, session)
                streamMessagesToMarkdownFile(writer, sessionId)
                writeMarkdownFooter(writer)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Failed to export session to Markdown", e)
            Result.failure(Exception("Failed to export session: ${e.message}", e))
        }
    }

    /**
     * Export a chat session to a JSON file.
     *
     * Uses streaming approach to write messages to file as they are loaded from pagination,
     * avoiding loading all messages into memory at once. Constructs JSON manually for efficiency.
     *
     * @param sessionId The ID of the session to export
     * @param filename The path to the output file
     * @return Result indicating success or failure with error message
     */
    fun exportToJson(sessionId: String, filename: String): Result<Unit> {
        return try {
            val session = sessionRepository.getSession(sessionId)
                ?: return Result.failure(Exception("Session not found: $sessionId"))

            val file = File(filename)
            file.parentFile?.mkdirs()

            file.bufferedWriter().use { writer ->
                writeJsonHeader(writer, session)
                streamMessagesToJsonFile(writer, sessionId)
                writeJsonFooter(writer)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Failed to export session to JSON", e)
            Result.failure(Exception("Failed to export session: ${e.message}", e))
        }
    }

    /**
     * Export a chat session to an HTML file.
     *
     * Uses streaming approach to write messages to file as they are loaded from pagination,
     * creating a formatted HTML document suitable for viewing in a browser.
     *
     * @param sessionId The ID of the session to export
     * @param filename The path to the output file
     * @return Result indicating success or failure with error message
     */
    fun exportToHtml(sessionId: String, filename: String): Result<Unit> {
        return try {
            val session = sessionRepository.getSession(sessionId)
                ?: return Result.failure(Exception("Session not found: $sessionId"))

            val file = File(filename)
            file.parentFile?.mkdirs()

            file.bufferedWriter().use { writer ->
                writeHtmlHeader(writer, session)
                streamMessagesToHtmlFile(writer, sessionId)
                writeHtmlFooter(writer)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Failed to export session to HTML", e)
            Result.failure(Exception("Failed to export session: ${e.message}", e))
        }
    }

    /**
     * Write the header section of the JSON file with session metadata.
     *
     * @param writer The buffered writer to write to
     * @param session The chat session metadata
     */
    private fun writeJsonHeader(writer: BufferedWriter, session: ChatSession) {
        writer.apply {
            appendLine("{")
            appendLine("  \"sessionId\": \"${escapeJson(session.id)}\",")
            appendLine("  \"title\": \"${escapeJson(session.title)}\",")
            appendLine("  \"createdAt\": \"${session.createdAt.atOffset(ZoneOffset.UTC).format(timestampFormatter)}\",")
            appendLine("  \"lastUpdated\": \"${session.updatedAt.atOffset(java.time.ZoneOffset.UTC).format(timestampFormatter)}\",")
            appendLine("  \"directiveId\": ${if (session.directiveId != null) "\"${escapeJson(session.directiveId)}\"" else "null"},")
            appendLine("  \"messages\": [")
        }
    }

    /**
     * Stream messages to JSON file using cursor-based pagination.
     * Writes each batch of messages immediately without storing all in memory.
     *
     * @param writer The buffered writer to write to
     * @param sessionId The ID of the session
     */
    private fun streamMessagesToJsonFile(writer: BufferedWriter, sessionId: String) {
        var cursor: Instant? = null
        val pageSize = 100
        var messageCounter = 0
        var isFirstMessage = true

        do {
            val (messages, nextCursor) = messageRepository.getMessagesPaginated(
                sessionId = sessionId,
                limit = pageSize,
                cursor = cursor,
                direction = PaginationDirection.FORWARD,
            )

            messages.forEach { message ->
                messageCounter++
                writeJsonMessage(writer, message, messageCounter, isFirstMessage)
                isFirstMessage = false
            }

            cursor = nextCursor
        } while (nextCursor != null)

        totalMessageCount = messageCounter
    }

    /**
     * Write a single message to the JSON file.
     *
     * @param writer The buffered writer to write to
     * @param message The message to write
     * @param index The message number (1-based)
     * @param isFirst Whether this is the first message (no leading comma)
     */
    private fun writeJsonMessage(writer: BufferedWriter, message: ChatMessage, index: Int, isFirst: Boolean) {
        writer.apply {
            if (!isFirst) {
                appendLine(",")
            }
            appendLine("    {")
            appendLine("      \"index\": $index,")
            appendLine("      \"role\": \"${message.role.value.uppercase()}\",")
            appendLine("      \"content\": \"${escapeJson(message.content)}\",")
            appendLine("      \"timestamp\": \"${message.createdAt.atOffset(java.time.ZoneOffset.UTC).format(timestampFormatter)}\"")
            append("    }")
        }
    }

    /**
     * Write the footer section of the JSON file.
     *
     * @param writer The buffered writer to write to
     */
    private fun writeJsonFooter(writer: BufferedWriter) {
        val exportTime = Instant.now().atOffset(ZoneOffset.UTC).format(timestampFormatter)
        writer.apply {
            appendLine()
            appendLine("  ],")
            appendLine("  \"exportedAt\": \"$exportTime\"")
            appendLine("}")
        }
    }

    /**
     * Escape JSON special characters to ensure valid JSON output.
     *
     * @param text The text to escape
     * @return The escaped text
     */
    private fun escapeJson(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\b", "\\b")
        .replace("\u000C", "\\f")

    /**
     * Write the header section of the markdown file.
     *
     * @param writer The buffered writer to write to
     * @param session The chat session metadata
     */
    private fun writeMarkdownHeader(writer: BufferedWriter, session: ChatSession) {
        writer.appendLine("# Chat Session: ${session.title}")
        writer.appendLine()
        writer.appendLine("**Session ID**: ${session.id}")
        writer.appendLine("**Created**: ${session.createdAt.atOffset(ZoneOffset.UTC).format(timestampFormatter)}")
        writer.appendLine("**Last Updated**: ${session.updatedAt.atOffset(java.time.ZoneOffset.UTC).format(timestampFormatter)}")
        if (session.directiveId != null) {
            writer.appendLine("**Directive**: ${session.directiveId}")
        }
        writer.appendLine()
        writer.appendLine("---")
        writer.appendLine()
    }

    /**
     * Stream messages to markdown file using cursor-based pagination.
     * Writes each batch of messages immediately without storing all in memory.
     *
     * @param writer The buffered writer to write to
     * @param sessionId The ID of the session
     */
    private fun streamMessagesToMarkdownFile(writer: BufferedWriter, sessionId: String) {
        var cursor: Instant? = null
        val pageSize = 100
        var messageCounter = 0

        do {
            val (messages, nextCursor) = messageRepository.getMessagesPaginated(
                sessionId = sessionId,
                limit = pageSize,
                cursor = cursor,
                direction = PaginationDirection.FORWARD,
            )

            messages.forEach { message ->
                messageCounter++
                writeMarkdownMessage(writer, message, messageCounter)
            }

            cursor = nextCursor
        } while (nextCursor != null)

        totalMessageCount = messageCounter
    }

    /**
     * Write a single message to the markdown file.
     *
     * @param writer The buffered writer to write to
     * @param message The message to write
     * @param index The message number (1-based)
     */
    private fun writeMarkdownMessage(writer: BufferedWriter, message: ChatMessage, index: Int) {
        writer.appendLine("## Message $index")
        writer.appendLine("**Role**: ${message.role.value.uppercase()}")
        writer.appendLine("**Timestamp**: ${message.createdAt.atOffset(java.time.ZoneOffset.UTC).format(timestampFormatter)}")
        writer.appendLine()
        writer.appendLine(message.content)
        writer.appendLine()
        writer.appendLine("---")
        writer.appendLine()
    }

    /**
     * Write the footer section of the markdown file.
     *
     * @param writer The buffered writer to write to
     */
    private fun writeMarkdownFooter(writer: BufferedWriter) {
        val exportTime = Instant.now().atOffset(ZoneOffset.UTC).format(timestampFormatter)
        writer.appendLine("[End of chat session - Total messages: $totalMessageCount]")
        writer.appendLine()
        writer.appendLine("*Exported on: $exportTime*")
    }

    /**
     * Write the HTML header section with styles.
     *
     * @param writer The buffered writer to write to
     * @param session The chat session metadata
     */
    private fun writeHtmlHeader(writer: BufferedWriter, session: ChatSession) {
        writer.appendLine(buildHtmlDocumentStart(session))
    }

    /**
     * Build the HTML document start (DOCTYPE, head, and header section).
     *
     * @param session The chat session metadata
     * @return The HTML string for document start
     */
    private fun buildHtmlDocumentStart(session: ChatSession): String = buildString {
        appendLine(HTML_DOCTYPE)
        appendLine(HTML_OPEN)
        appendLine(buildHtmlHead(session.title))
        appendLine(BODY_OPEN)
        append(buildHeaderDiv(session))
    }

    /**
     * Build the HTML head section with title and styles.
     *
     * @param title The page title
     * @return The HTML string for the head section
     */
    private fun buildHtmlHead(title: String): String = buildString {
        appendLine("<head>")
        appendLine("    <meta charset=\"UTF-8\">")
        appendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        appendLine("    <title>Chat Session: ${escapeHtml(title)}</title>")
        appendLine("    <style>$HTML_CSS</style>")
        appendLine("</head>")
    }

    /**
     * Build the header div HTML with session metadata.
     *
     * @param session The chat session metadata
     * @return The HTML string for the header section
     */
    private fun buildHeaderDiv(session: ChatSession): String = buildString {
        appendLine("    <div class=\"header\">")
        appendLine("        <h1>${escapeHtml(session.title)}</h1>")
        appendLine("        <div class=\"metadata\">")
        appendLine("            <p><strong>Session ID:</strong> ${session.id}</p>")
        appendLine("            <p><strong>Created:</strong> ${session.createdAt.atOffset(ZoneOffset.UTC).format(timestampFormatter)}</p>")
        appendLine("            <p><strong>Last Updated:</strong> ${session.updatedAt.atOffset(java.time.ZoneOffset.UTC).format(timestampFormatter)}</p>")
        if (session.directiveId != null) {
            appendLine("            <p><strong>Directive:</strong> ${escapeHtml(session.directiveId)}</p>")
        }
        appendLine("        </div>")
        appendLine("    </div>")
    }

    /**
     * Stream messages to HTML file using cursor-based pagination.
     * Writes each batch of messages immediately without storing all in memory.
     *
     * @param writer The buffered writer to write to
     * @param sessionId The ID of the session
     */
    private fun streamMessagesToHtmlFile(writer: BufferedWriter, sessionId: String) {
        var cursor: Instant? = null
        val pageSize = 100
        var messageCounter = 0

        do {
            val (messages, nextCursor) = messageRepository.getMessagesPaginated(
                sessionId = sessionId,
                limit = pageSize,
                cursor = cursor,
                direction = PaginationDirection.FORWARD,
            )

            messages.forEach { message ->
                messageCounter++
                writeHtmlMessage(writer, message, messageCounter)
            }

            cursor = nextCursor
        } while (nextCursor != null)

        totalMessageCount = messageCounter
    }

    /**
     * Write a single message to the HTML file.
     *
     * @param writer The buffered writer to write to
     * @param message The message to write
     * @param index The message number (1-based)
     */
    private fun writeHtmlMessage(writer: BufferedWriter, message: ChatMessage, index: Int) {
        writer.appendLine(buildMessageDiv(message))
    }

    /**
     * Build the message div HTML with markdown rendering for assistant messages.
     *
     * @param message The message to build HTML for
     * @return The HTML string for the message
     */
    private fun buildMessageDiv(message: ChatMessage): String = buildString {
        val roleClass = message.role.value.lowercase()
        appendLine("    <div class=\"message\">")
        appendLine("        <div class=\"message-header\">")
        appendLine("            <span class=\"role $roleClass\">${message.role.value.uppercase()}</span>")
        appendLine("            <span class=\"timestamp\">${message.createdAt.atOffset(java.time.ZoneOffset.UTC).format(timestampFormatter)}</span>")
        appendLine("        </div>")

        // Render markdown for assistant messages, keep plain text for user/system
        val contentHtml = if (roleClass == "assistant") {
            renderMarkdownToHtml(message.content)
        } else {
            escapeHtml(message.content)
        }

        val contentClass = if (roleClass == "assistant") "content" else "content user-message"
        appendLine("        <div class=\"$contentClass\">$contentHtml</div>")
        appendLine("    </div>")
    }

    /**
     * Parse markdown content and render it as HTML.
     *
     * @param markdown The markdown text to render
     * @return The rendered HTML
     */
    private fun renderMarkdownToHtml(markdown: String): String {
        val document = markdownParser.parse(markdown)
        return htmlRenderer.render(document).trim()
    }

    /**
     * Write the HTML footer section.
     *
     * @param writer The buffered writer to write to
     */
    private fun writeHtmlFooter(writer: BufferedWriter) {
        writer.appendLine(buildHtmlDocumentEnd())
    }

    /**
     * Build the HTML document end (footer and closing tags).
     *
     * @return The HTML string for document end
     */
    private fun buildHtmlDocumentEnd(): String = buildString {
        append(buildFooterDiv())
        appendLine(BODY_CLOSE)
        appendLine(HTML_CLOSE)
    }

    /**
     * Build the footer div HTML.
     *
     * @return The HTML string for the footer section
     */
    private fun buildFooterDiv(): String = buildString {
        val exportTime = Instant.now().atOffset(ZoneOffset.UTC).format(timestampFormatter)
        appendLine("    <div class=\"footer\">")
        appendLine("        <p>End of chat session - Total messages: $totalMessageCount</p>")
        appendLine("        <p>Exported on: $exportTime</p>")
        appendLine("    </div>")
    }

    /**
     * Escape HTML special characters to prevent XSS and formatting issues.
     *
     * @param text The text to escape
     * @return The escaped text
     */
    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")

    private var totalMessageCount = 0
}
