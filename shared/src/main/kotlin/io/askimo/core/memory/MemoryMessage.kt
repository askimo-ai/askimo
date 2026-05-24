/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.memory

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.askimo.core.context.MessageRole
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Custom serializer for Instant to support Kotlinx Serialization.
 * Serializes as ISO-8601 UTC string (e.g. "2026-05-24T10:15:30.123456Z").
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * Represents a chat message in memory with serialization support.
 * This is the persistent representation of a chat message that can be stored in the database.
 *
 * @property content The text content of the message
 * @property type The message type (USER, ASSISTANT, SYSTEM, TOOL_EXECUTION_RESULT_MESSAGE)
 * @property createdAt Timestamp when the message was created
 */
@Serializable
data class MemoryMessage(
    val content: String,
    val type: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
) {
    /**
     * Convert this MemoryMessage to a LangChain4j ChatMessage
     */
    fun toChatMessage(): ChatMessage = when (this.type) {
        MessageRole.USER.value -> UserMessage.from(this.content)

        MessageRole.ASSISTANT.value -> AiMessage.from(this.content)

        MessageRole.SYSTEM.value -> SystemMessage.from(this.content)

        MessageRole.TOOL_EXECUTION_RESULT_MESSAGE.value ->
            ToolExecutionResultMessage.builder().text(this.content).build()

        else -> UserMessage.from(this.content) // fallback
    }

    companion object {
        /**
         * Regex that matches a Markdown image whose src is a base64 data URL.
         * Captures the alt text so we can replace the whole tag with a short placeholder.
         *
         * Pattern: ![alt](data:image/...;base64,<data>)
         */
        private val BASE64_IMAGE_REGEX =
            Regex("""!\[([^]]*?)]\(data:image/[^;]+;base64,[A-Za-z0-9+/=\r\n]+\)""")

        /**
         * Remove all inline base64 images from [text], replacing each with a short
         * human-readable placeholder.  Any surrounding blank lines are collapsed to
         * a single blank line so the rest of the message still reads naturally.
         */
        fun stripBase64Images(text: String): String {
            val stripped = BASE64_IMAGE_REGEX.replace(text) { match ->
                val alt = match.groupValues[1].trim()
                if (alt.isNotEmpty()) "[Image: $alt]" else "[Image]"
            }
            // Collapse runs of blank lines left behind by the removal
            return stripped.replace(Regex("\n{3,}"), "\n\n").trim()
        }

        /**
         * Create a MemoryMessage from a LangChain4j ChatMessage.
         * Base64 image data is stripped and replaced with a short placeholder so
         * images are never persisted to the database or re-sent on every API call.
         */
        fun from(chatMessage: ChatMessage): MemoryMessage {
            val rawContent = chatMessage.getTextContent()
            val content = stripBase64Images(rawContent)
            val type = when (chatMessage) {
                is UserMessage -> MessageRole.USER.value
                is AiMessage -> MessageRole.ASSISTANT.value
                is SystemMessage -> MessageRole.SYSTEM.value
                is ToolExecutionResultMessage -> MessageRole.TOOL_EXECUTION_RESULT_MESSAGE.value
                else -> MessageRole.USER.value // fallback
            }
            return MemoryMessage(
                content = content,
                type = type,
                createdAt = Instant.now(),
            )
        }
    }
}

/**
 * Extension function to extract text content from ChatMessage
 */
fun ChatMessage.getTextContent(): String = when (this) {
    is UserMessage -> this.singleText() ?: ""
    is AiMessage -> this.text() ?: ""
    is SystemMessage -> this.text() ?: ""
    is ToolExecutionResultMessage -> this.text() ?: ""
    else -> ""
}

/**
 * Returns a copy of this [ChatMessage] with all inline base64 images stripped.
 * The message type and all other attributes (tool calls, tool execution id, name, etc.)
 * are preserved — only base64 image data in text content is replaced with placeholders.
 */
fun ChatMessage.stripImages(): ChatMessage = when (this) {
    is AiMessage -> {
        val strippedText = text()?.let { MemoryMessage.stripBase64Images(it) }
        toBuilder().text(strippedText).build()
    }

    is UserMessage -> {
        // Filter out ImageContent parts with base64 src; keep all other content parts
        val filteredContents = contents().filter { content ->
            content !is dev.langchain4j.data.message.ImageContent ||
                content.image().url() != null // keep URL-based images, drop base64-only
        }
        // Strip base64 image markdown from any TextContent parts
        val sanitisedContents = filteredContents.map { content ->
            if (content is TextContent) {
                TextContent.from(
                    MemoryMessage.stripBase64Images(content.text()),
                )
            } else {
                content
            }
        }
        toBuilder().contents(sanitisedContents).build()
    }

    is SystemMessage -> SystemMessage.from(MemoryMessage.stripBase64Images(text()))

    is ToolExecutionResultMessage ->
        ToolExecutionResultMessage.from(
            id(),
            toolName(),
            MemoryMessage.stripBase64Images(text() ?: ""),
        )

    else -> this
}

/**
 * Extension function to convert ChatMessage to MemoryMessage
 * This is the reverse operation of MemoryMessage.toChatMessage()
 */
fun ChatMessage.toMemoryMessage(): MemoryMessage = MemoryMessage.from(this)
