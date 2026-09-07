/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
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
 * conversations don't support.
 *
 * Takes [AgentTurnMessageDTO] and adapts each one to a `ChatMessageDTO` via [toRenderableMessage]
 * so it can reuse the shared bubble/timeline composables.
 */
@Composable
fun agentMessageList(
    messages: List<AgentTurnMessageDTO>,
    isRunning: Boolean = false,
    isWaitingForFirstEvent: Boolean = false,
    thinkingElapsedSeconds: Int = 0,
    liveTimelineGroups: List<TurnTimelineGroup> = emptyList(),
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

        if (isRunning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (messages.isEmpty()) Spacing.extraLarge else 0.dp, bottom = Spacing.small),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.Start,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape)
                        .border(width = 2.dp, color = AppColors.codeBlockBorderColor(), shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (aiAvatarPainter != null) {
                        Icon(
                            painter = aiAvatarPainter,
                            contentDescription = "AI",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (isWaitingForFirstEvent || liveTimelineGroups.isEmpty()) {
                        Text(
                            text = stringResource("message.thinking", thinkingElapsedSeconds),
                            style = AppTextStyles.bodySecondary,
                            color = AppColors.secondaryIconColor(),
                            modifier = Modifier.padding(top = Spacing.medium),
                        )
                    } else {
                        turnTimelineView(liveTimelineGroups, isStreaming = true)
                    }
                }
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
