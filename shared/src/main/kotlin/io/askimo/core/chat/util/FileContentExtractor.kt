/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

import io.askimo.core.config.AppConfig
import io.askimo.core.logging.logger
import io.askimo.core.util.formatFileSize
import org.apache.tika.Tika
import org.apache.tika.exception.TikaException
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import java.io.File
import java.io.FileInputStream

/**
 * Exception thrown when a file exceeds the maximum allowed size for chat attachments.
 */
class FileSizeExceededException(val fileSize: Long, val maxAllowedSize: Long) : RuntimeException("File size exceeds the maximum allowed limit of ${formatFileSize(maxAllowedSize)}")

/**
 * Exception thrown when a file cannot be parsed (e.g. corrupted PDF or unsupported format variant).
 */
class FileParseException(filePath: String, cause: Throwable) : RuntimeException("Failed to parse file: $filePath", cause)

/**
 * Utility for extracting text content from various file types.
 * Uses Apache Tika to detect actual file content types (not just extensions).
 * Supports text-based files, PDF, DOCX, and more.
 */
object FileContentExtractor {

    private val log = logger<FileContentExtractor>()

    private val tika = Tika()
    private val parser = AutoDetectParser()

    /**
     * Maximum file size for chat attachments (from AppConfig).
     */
    private val maxFileSizeBytes: Long = AppConfig.indexing.maxFileBytes

    /**
     * Validate that a file does not exceed the maximum allowed size.
     *
     * @param file The file to check
     * @throws FileSizeExceededException if the file exceeds the maximum allowed size
     */
    fun validateFileSize(file: File) {
        val fileSize = file.length()
        if (fileSize > maxFileSizeBytes) {
            throw FileSizeExceededException(fileSize, maxFileSizeBytes)
        }
    }

    /**
     * Extract text content from a file.
     * Supports text files, PDF, DOCX, XLSX, PPTX, OpenDocument, emails, and other formats supported by Tika.
     *
     * @param file The file to extract content from
     * @return The extracted text content
     * @throws Exception if the file cannot be read or the format is unsupported
     * @throws FileSizeExceededException if the file exceeds the maximum allowed size
     */
    fun extractContent(file: File): String {
        // Validate file size first
        validateFileSize(file)

        val extension = FileTypeSupport.getExtension(file.name)
        if ((
                FileTypeSupport.isTextExtractable(extension) &&
                    extension in (FileTypeSupport.TEXT_EXTENSIONS + FileTypeSupport.CODE_EXTENSIONS)
                ) ||
            file.name.lowercase() in FileTypeSupport.CONFIG_EXTENSIONS
        ) {
            return ContentSanitizer.sanitizeTemplateVariables(file.readText())
        }

        val mimeType = detectMimeType(file)

        return when {
            // Documents
            mimeType.startsWith("application/pdf") ||
                mimeType.contains("word") ||
                mimeType.contains("wordprocessingml") ||
                mimeType.contains("msword") ||
                mimeType.contains("spreadsheet") ||
                mimeType.contains("excel") ||
                mimeType.contains("ms-excel") ||
                // Presentations
                mimeType.contains("presentation") ||
                mimeType.contains("powerpoint") ||
                mimeType.contains("ms-powerpoint") ||
                // OpenDocument formats
                mimeType.startsWith("application/vnd.oasis.opendocument") ||
                // Email formats
                mimeType.contains("message/rfc822") ||
                mimeType.contains("application/vnd.ms-outlook") ||
                // RTF
                mimeType.contains("rtf") -> {
                ContentSanitizer.sanitizeTemplateVariables(extractUsingTika(file))
            }

            // Plain text files (includes CSV, TSV, etc.)
            mimeType.startsWith("text/") ||
                mimeType in SUPPORTED_APPLICATION_TYPES -> {
                ContentSanitizer.sanitizeTemplateVariables(file.readText())
            }

            // Fallback: Extension-based check for files Tika misdetects
            // This handles .md, .gradle.kts, .gitignore, and other text files
            mimeType == "application/octet-stream" ||
                mimeType.startsWith("application/x-") -> {
                if (extension in FileTypeSupport.DOCUMENT_EXTENSIONS) {
                    ContentSanitizer.sanitizeTemplateVariables(extractUsingTika(file))
                } else {
                    throw UnsupportedOperationException("Cannot extract content from: $mimeType (extension: .$extension)")
                }
            }

            else -> throw UnsupportedOperationException("Cannot extract content from: $mimeType")
        }
    }

    /**
     * Extract content using Tika parser (for PDFs, DOCX, etc.).
     */
    private fun extractUsingTika(file: File): String = try {
        FileInputStream(file).use { stream ->
            val handler = BodyContentHandler(-1) // -1 = no character limit
            val metadata = Metadata()
            metadata.set("resourceName", file.name)
            parser.parse(stream, handler, metadata)
            handler.toString().trim()
        }
    } catch (e: TikaException) {
        throw FileParseException(file.path, e)
    }

    /**
     * Check if a file type is supported for content extraction.
     * Uses content-type detection (magic bytes) with extension-based fallback.
     *
     * @param file The file to check
     * @return true if the file type is supported, false otherwise
     */
    fun isSupported(file: File): Boolean {
        val mimeType = detectMimeType(file)

        // First, check if MIME type is supported
        if (isSupportedMimeType(mimeType)) {
            return true
        }

        // Fallback: Check file extension for text files that Tika misdetects
        // This handles cases like .md, .gradle.kts, .gitignore, etc.
        if (mimeType == "application/octet-stream" || mimeType.startsWith("application/x-")) {
            val extension = FileTypeSupport.getExtension(file.name)
            return extension in (FileTypeSupport.TEXT_EXTENSIONS + FileTypeSupport.CODE_EXTENSIONS) ||
                file.name.lowercase() in FileTypeSupport.CONFIG_EXTENSIONS
        }

        return false
    }

    /**
     * Check if a file name (without needing the file on disk) refers to a plain-text file.
     * Uses extension and known config-file names — no MIME detection.
     * Suitable for UI decisions (e.g. whether to show an inline preview) where the file
     * may not yet be read.
     *
     * @param fileName The file name (basename, with or without path)
     * @return true if the extension or filename maps to a text/code file
     */
    fun isTextFile(fileName: String): Boolean {
        val ext = FileTypeSupport.getExtension(fileName).lowercase()
        return ext in (FileTypeSupport.TEXT_EXTENSIONS + FileTypeSupport.CODE_EXTENSIONS) ||
            fileName.lowercase() in FileTypeSupport.CONFIG_EXTENSIONS
    }

    /**
     * Check if a file is a text-based file where line numbers are meaningful.
     * Returns false for binary formats like PDF, DOCX, etc.
     *
     * @param file The file to check
     * @return true if the file is a text file, false for binary formats
     */
    fun isTextFile(file: File): Boolean {
        val mimeType = detectMimeType(file)

        // Text files based on MIME type
        if (mimeType.startsWith("text/") || mimeType in SUPPORTED_APPLICATION_TYPES) {
            return true
        }

        // Fallback to extension check for files Tika misdetects
        if (mimeType == "application/octet-stream" || mimeType.startsWith("application/x-")) {
            val extension = FileTypeSupport.getExtension(file.name)
            return extension in (FileTypeSupport.TEXT_EXTENSIONS + FileTypeSupport.CODE_EXTENSIONS) ||
                file.name.lowercase() in FileTypeSupport.CONFIG_EXTENSIONS
        }

        return false
    }

    /**
     * Detect MIME type using Tika (reads file content, not just extension).
     */
    private fun detectMimeType(file: File): String = try {
        tika.detect(file)
    } catch (e: Exception) {
        log.warn("Failed to detect MIME type for file: ${e.message}", e)
        "application/octet-stream"
    }

    /**
     * Check if a MIME type represents supported content.
     */
    private fun isSupportedMimeType(mimeType: String): Boolean = when {
        mimeType.startsWith("text/") -> true
        mimeType in SUPPORTED_APPLICATION_TYPES -> true
        mimeType.startsWith("application/pdf") -> true
        mimeType.contains("word") || mimeType.contains("wordprocessingml") || mimeType.contains("msword") -> true
        mimeType.contains("spreadsheet") || mimeType.contains("excel") || mimeType.contains("ms-excel") -> true
        mimeType.contains("presentation") || mimeType.contains("powerpoint") || mimeType.contains("ms-powerpoint") -> true
        mimeType.startsWith("application/vnd.oasis.opendocument") -> true
        mimeType.contains("message/rfc822") || mimeType.contains("application/vnd.ms-outlook") -> true
        mimeType.contains("rtf") -> true
        else -> false
    }

    /**
     * Get a user-friendly message for unsupported file types.
     */
    fun getUnsupportedMessage(file: File): String {
        val mimeType = detectMimeType(file)
        return when {
            mimeType.startsWith("image/") -> "Image OCR support coming soon"
            mimeType.startsWith("video/") -> "Video files are not supported"
            mimeType.startsWith("audio/") -> "Audio files are not supported"
            else -> "Unsupported file type: $mimeType"
        }
    }

    // Application MIME types that contain text
    private val SUPPORTED_APPLICATION_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/typescript",
        "application/x-sh",
        "application/x-shellscript",
        "application/sql",
        "application/x-yaml",
        "application/x-httpd-php",
        "application/rtf",
        "application/x-tex",
    )

    /**
     * Get all supported file extensions for text extraction (text + documents, but NOT images).
     */
    val ALL_SUPPORTED_EXTENSIONS: Set<String> = FileTypeSupport.TEXT_EXTRACTABLE_EXTENSIONS
}
