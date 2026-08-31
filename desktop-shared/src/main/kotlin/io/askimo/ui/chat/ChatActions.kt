/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO

interface ChatActions {
    fun sendOrEditMessage(
        creationMode: CreationMode,
        message: String,
        attachments: List<FileAttachmentDTO> = emptyList(),
        editingMessage: ChatMessageDTO? = null,
        enabledServerIds: Set<String> = emptySet(),
    ): String?
    fun cancelResponse()
    fun loadPrevious()
    fun searchMessages(query: String)
    fun clearSearch()
    fun nextSearchResult()
    fun previousSearchResult()
    fun setDirective(directiveId: String?)
    fun setWebSearchInRag(enabled: Boolean)
    fun updateAIMessage(messageId: String, newContent: String)
    fun retryMessage(messageId: String, enabledServerIds: Set<String> = emptySet())
    fun toggleBookmark(messageId: String)
    fun clearPendingScroll()

    /**
     * Fork the current session from the given AI message, creating a new independent
     * session pre-populated with all active messages up to and including [messageId],
     * then navigate to the new session immediately.
     */
    fun forkFromMessage(messageId: String)

    /**
     * Trigger an aggressive (COMPACT-mode) summarization cycle on the current session's
     * memory. Fire-and-forget — [ChatState.isCompressing] tracks progress reactively.
     * No-op if a cycle is already running.
     */
    fun compressMemory()
}
