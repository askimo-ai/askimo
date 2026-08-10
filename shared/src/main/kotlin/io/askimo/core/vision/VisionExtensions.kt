/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.vision

import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.util.FileTypeSupport
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64

/**
 * Check if a file attachment is an image based on MIME type or file extension.
 */
fun FileAttachmentDTO.isImage(): Boolean {
    // Check MIME type first
    if (mimeType.startsWith("image/", ignoreCase = true)) {
        return true
    }

    // Fallback to extension check using FileTypeSupport
    val extension = FileTypeSupport.getExtension(fileName)
    return FileTypeSupport.isImageExtension(extension)
}

/**
 * Check if a chat message needs vision capabilities.
 * Returns true if the message contains any image attachments.
 */
fun ChatMessageDTO.needsVision(): Boolean = attachments.any { it.isImage() }

/**
 * Convert a ChatMessageDTO to a LangChain4j UserMessage.
 * If the message contains images, creates a multi-modal message with both text and images.
 * Otherwise, creates a simple text-only message.
 */
fun ChatMessageDTO.toUserMessage(): List<Content> {
    if (!needsVision()) {
        // Simple text message
        return listOf(TextContent(content))
    }

    // Multi-modal message with text and images
    val contents = mutableListOf<Content>()

    // Add text content if present
    if (content.isNotBlank()) {
        contents.add(TextContent(content))
    }

    // Add image content from attachments (process and compress images)
    attachments.filter { it.isImage() }.forEach { attachment ->
        when {
            // If attachment has content (base64 or data), use it
            attachment.content != null -> {
                val imageBytes = if (isBase64(attachment.content)) {
                    Base64.getDecoder().decode(attachment.content)
                } else {
                    attachment.content.toByteArray()
                }

                // Process image to reduce token usage
                val processed = ImageProcessor.process(imageBytes, attachment.mimeType)
                val base64Content = Base64.getEncoder().encodeToString(processed.bytes)
                contents.add(ImageContent(base64Content, processed.mimeType))
            }

            // If attachment has file path, read and encode it
            attachment.filePath != null -> {
                val fileBytes = Files.readAllBytes(
                    Paths.get(attachment.filePath),
                )

                // Process image to reduce token usage
                val processed = ImageProcessor.process(fileBytes, attachment.mimeType)
                val base64 = Base64.getEncoder().encodeToString(processed.bytes)
                contents.add(ImageContent(base64, processed.mimeType))
            }

            else -> {
                // Skip attachments without content or path
                // Could log a warning here
            }
        }
    }

    // Ensure we have at least some content
    if (contents.isEmpty()) {
        return listOf(TextContent(content.ifBlank { "Please analyze this image" }))
    }

    return contents
}

/**
 * Simple check if a string appears to be base64 encoded.
 */
private fun isBase64(str: String): Boolean = str.matches(Regex("^[A-Za-z0-9+/]*={0,2}$")) && str.length % 4 == 0
