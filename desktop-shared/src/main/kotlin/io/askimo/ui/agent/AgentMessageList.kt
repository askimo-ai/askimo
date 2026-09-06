/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import io.askimo.core.agent.dto.AgentTurnMessageDTO
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.TurnTimelineGroup
import io.askimo.ui.chat.messageBubble
import io.askimo.ui.chat.turnTimelineView
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

/**
 * Slim transcript renderer for agentic runs, built on the same shared [messageBubble]/
 * [turnTimelineView] blocks as `ui.chat`'s `chatMessageList` but without any of the chat-only
 * features (search/edit/retry/bookmark/fork/attachments/day-separators/voice-autoplay) agent
 * conversations don't support. The *currently streaming* turn's timeline is rendered separately
 * by the caller; this only needs [completedGroupsByMessageId] to redraw *finalized* turns.
 *
 * Takes [AgentTurnMessageDTO] and adapts each one to a `ChatMessageDTO` via [toRenderableMessage]
 * so it can reuse the shared bubble/timeline composables.
 */
@Composable
fun agentMessageList(
    messages: List<AgentTurnMessageDTO>,
    isThinking: Boolean = false,
    thinkingElapsedSeconds: Int = 0,
    completedGroupsByMessageId: Map<String, List<TurnTimelineGroup>> = emptyMap(),
    userAvatarPainter: BitmapPainter? = null,
    aiAvatarPainter: BitmapPainter? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
    ) {
        messages.forEachIndexed { index, turn ->
            val message = turn.toRenderableMessage()
            val isStreamingMessage = !message.isUser && message.id == null
            val resolvedGroups: List<TurnTimelineGroup> = if (message.isUser) {
                emptyList()
            } else {
                message.id?.let { completedGroupsByMessageId[it] } ?: emptyList()
            }
            val hasToolCalls = resolvedGroups.any { it is TurnTimelineGroup.ToolGroup }
            val fallbackThinkingContent = if (!hasToolCalls) {
                resolvedGroups
                    .filterIsInstance<TurnTimelineGroup.ThinkingGroup>()
                    .joinToString("") { it.text }
            } else {
                ""
            }
            messageBubble(
                message = message,
                userAvatarPainter = userAvatarPainter,
                aiAvatarPainter = aiAvatarPainter,
                addTopPadding = index == 0,
                isStreaming = isStreamingMessage,
                thinkingContent = fallbackThinkingContent,
                customBody = if (!message.isUser && hasToolCalls) {
                    { turnTimelineView(resolvedGroups, isStreaming = isStreamingMessage) }
                } else {
                    null
                },
            )
        }

        // Show "Thinking..." indicator when the agent is processing but hasn't returned a first
        // token/tool-call/thinking event yet.
        if (isThinking) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = stringResource("message.thinking", thinkingElapsedSeconds),
                    style = AppTextStyles.bodySecondary,
                    color = AppColors.secondaryIconColor(),
                )
            }
        }
    }
}

/**
 * Adapts an [AgentTurnMessageDTO] to a [ChatMessageDTO] purely for reuse of the shared
 * `messageBubble`/`turnTimelineView` rendering — every chat-only field (attachments, edit/
 * bookmark/fork/outdated-branch state, persisted content blocks) is left at its default since
 * agent turns never populate them; only the fields both types share are copied across.
 */
private fun AgentTurnMessageDTO.toRenderableMessage(): ChatMessageDTO = ChatMessageDTO(
    id = id,
    content = content,
    isUser = isUser,
    timestamp = timestamp,
    isFailed = isFailed,
    isCancelled = isCancelled,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    totalTokens = totalTokens,
    durationMs = durationMs,
)
