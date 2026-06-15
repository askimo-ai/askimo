/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.mapper

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.FileAttachment
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.context.MessageRole

/**
 * Mapper for converting between domain objects and DTOs.
 * Centralizes all mapping logic for better maintainability and testability.
 */
object ChatMessageMapper {

    /**
     * Convert a ChatMessage domain object to DTO.
     */
    fun ChatMessage.toDTO(): ChatMessageDTO = ChatMessageDTO(
        id = this.id,
        content = this.content,
        isUser = this.role == MessageRole.USER,
        timestamp = this.createdAt,
        isOutdated = this.isOutdated,
        editParentId = this.editParentId,
        isEdited = this.isEdited,
        attachments = this.attachments.map { it.toDTO() },
        isFailed = this.isFailed,
        inputTokens = this.inputTokens,
        outputTokens = this.outputTokens,
        totalTokens = this.totalTokens,
        durationMs = this.durationMs,
    )

    /**
     * Convert a FileAttachment domain object to DTO.
     */
    fun FileAttachment.toDTO(): FileAttachmentDTO = FileAttachmentDTO(
        id = this.id,
        messageId = this.messageId,
        sessionId = this.sessionId,
        fileName = this.fileName,
        mimeType = this.mimeType,
        size = this.size,
        createdAt = this.createdAt,
        content = this.content,
    )

    /**
     * Convert a list of ChatMessage domain objects to DTOs.
     */
    fun List<ChatMessage>.toDTOs(): List<ChatMessageDTO> = this.map { it.toDTO() }

    /**
     * Convert a FileAttachmentDTO to domain object.
     *
     * @param sessionId The session ID to associate with the attachment
     */
    fun FileAttachmentDTO.toDomain(sessionId: String): FileAttachment = FileAttachment(
        id = this.id.takeIf { it.isNotEmpty() } ?: "", // Will be auto-generated
        messageId = this.messageId,
        sessionId = sessionId,
        fileName = this.fileName,
        mimeType = this.mimeType,
        size = this.size,
        createdAt = this.createdAt,
        content = this.content,
    )

    /**
     * Convert a list of FileAttachmentDTOs to domain objects.
     *
     * @param sessionId The session ID to associate with the attachments
     */
    fun List<FileAttachmentDTO>.toDomain(sessionId: String): List<FileAttachment> = this.map { it.toDomain(sessionId) }
}
