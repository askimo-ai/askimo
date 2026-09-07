/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.TurnTimelineGroup
import io.askimo.core.chat.dto.grouped
import io.askimo.core.config.AppConfig
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun chatMessageList(
    messages: List<ChatMessageDTO>,
    isThinking: Boolean = false,
    thinkingElapsedSeconds: Int = 0,
    spinnerFrame: String = "",
    isLoadingPrevious: Boolean = false,
    searchQuery: String = "",
    currentSearchResultIndex: Int = 0,
    onMessageClick: ((String, Instant) -> Unit)? = null,
    onEditMessage: ((ChatMessageDTO) -> Unit)? = null,
    onDownloadAttachment: ((FileAttachmentDTO) -> Unit)? = null,
    userAvatarPainter: BitmapPainter? = null,
    aiAvatarPainter: BitmapPainter? = null,
    onRetryMessage: ((String) -> Unit)? = null,
    viewportTopY: Float? = null,
    projectId: String? = null,
    // Ordered tool-call/text/thinking timeline for the *currently streaming/last* AI turn
    // (this session only) — replaces the old activeToolCalls/activeThinkingContent pair so
    // true chronological interleaving can be rendered. See SessionManager.StreamingThread.
    activeTimeline: List<TurnTimelineEntry> = emptyList(),
    // Session-only per-message lookup so completed AI messages (this session, or reloaded from
    // persisted `ChatMessageDTO.contentBlocks`) can show their ordered tool/thinking/text
    // timeline — kept visible for every past turn, not just the current "last" message (which
    // instead uses activeTimeline above).
    completedGroupsByMessageId: Map<String, List<TurnTimelineGroup>> = emptyMap(),
    bookmarkedMessageIds: Set<String> = emptySet(),
    onToggleBookmark: ((String) -> Unit)? = null,
    onForkFromMessage: ((String) -> Unit)? = null,
) {
    // Retry confirmation dialog state
    var showRetryConfirmDialog by remember { mutableStateOf(false) }
    var retryMessageId by remember { mutableStateOf<String?>(null) }

    // Cache voice-output enabled flag once for the whole list — AppConfig.voice resolves a key
    // from the OS keychain, so read it off the UI thread (same pattern as chatInputField's
    // voiceInputEnabled). Hidden entirely per-bubble when disabled (default) — zero UI impact
    // for existing users.
    var voiceOutputEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        voiceOutputEnabled = withContext(Dispatchers.IO) { AppConfig.voice.enabled }
    }

    // ── Auto-play AI responses (🔊) — opt-in "conversation mode" ──────────────────────
    // Cache the flag the same way as voiceOutputEnabled above (keychain-adjacent config read).
    var autoPlayResponses by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        autoPlayResponses = withContext(Dispatchers.IO) { AppConfig.voice.autoPlayResponses }
    }
    val voiceErrorTitle = stringResource("chat.voice.error.title")
    val autoPlayScope = rememberCoroutineScope()

    // Detect "a response just finished streaming" by watching the last AI message's id
    // transition from null (in-flight placeholder — see ChatViewModel.upsertStreamingAiMessage)
    // to non-null (persisted). Deliberately NOT keyed on `isThinking`: that flag flips to false
    // as soon as the *first* token arrives (see ChatViewModel.subscribeToThread), not when the
    // response actually completes, so it can't be used as a completion signal here.
    val lastAiMessage = messages.lastOrNull { !it.isUser }
    val lastAiMessageKey = when {
        lastAiMessage == null -> "none"

        lastAiMessage.id != null -> "id:${lastAiMessage.id}"

        // Same key for every token update while streaming (id stays null, list size is stable)
        // so this effect only re-fires on an actual streaming→persisted transition, not per token.
        else -> "streaming:${messages.size}"
    }
    var sawStreamingForCurrentTurn by remember { mutableStateOf(false) }
    var lastAutoPlayedMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastAiMessageKey) {
        val message = lastAiMessage ?: return@LaunchedEffect
        val id = message.id
        if (id == null) {
            // Actively streaming a new AI turn — remember this so the *next* transition to a
            // real id is treated as "just completed" (not stale history from opening a session).
            sawStreamingForCurrentTurn = true
            return@LaunchedEffect
        }
        if (!sawStreamingForCurrentTurn) return@LaunchedEffect
        sawStreamingForCurrentTurn = false
        if (lastAutoPlayedMessageId == id) return@LaunchedEffect
        lastAutoPlayedMessageId = id
        if (!voiceOutputEnabled || !autoPlayResponses) return@LaunchedEffect
        if (message.content.isBlank()) return@LaunchedEffect
        VoicePlaybackController.toggle(id, message.content, autoPlayScope) { errorMessage ->
            EventBus.post(AppErrorEvent(title = voiceErrorTitle, message = errorMessage))
        }
    }

    // Current date — re-evaluated at midnight so "Today"/"Yesterday" labels stay accurate
    // when the user keeps the app open across a day boundary.
    val zone = ZoneId.systemDefault()
    var currentDate by remember { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            val millisUntilMidnight = Duration.between(now, nextMidnight).toMillis()
            delay(millisUntilMidnight.milliseconds)
            currentDate = LocalDate.now(zone)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
    ) {
        // Show loading indicator when loading previous messages
        if (isLoadingPrevious) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource("message.loading.previous"),
                    style = AppTextStyles.bodySecondary,
                    color = AppColors.secondaryIconColor(),
                )
            }
        }

        // Group messages into active and outdated branches
        val messageGroups = groupMessagesWithOutdatedBranches(messages)
        var messageIndex = 0
        var isFirstMessage = true
        var lastDayLabel: String? = null
        messageGroups.forEach { group ->
            when (group) {
                is MessageGroup.ActiveMessage -> {
                    // Day separator — show when the message date is different from the previous one
                    val ts = group.message.timestamp
                    if (ts != null) {
                        val dayLabel = LocalizationManager.formatDayLabel(ts, currentDate)
                        if (dayLabel != lastDayLabel) {
                            val timeLabel = LocalizationManager.formatMessageTime(ts)
                            messageDaySeparator(label = "$dayLabel · $timeLabel")
                            lastDayLabel = dayLabel
                        }
                    }

                    val isActiveResult = searchQuery.isNotBlank() && messageIndex == currentSearchResultIndex
                    // A message is streaming when it's the last AI message still without a persisted ID.
                    val isStreamingMessage = !group.message.isUser && group.message.id == null
                    // "Last AI message" — used for the *live* activeTimeline. ChatViewModel
                    // deliberately keeps this populated even after the message is finalized (real
                    // id assigned) "until the user sends the next message" — so this must match by
                    // id once finalized, not just while streaming (id == null).
                    val lastAiMessageId = messages.lastOrNull { !it.isUser }?.id
                    val isLastAiMsg = !group.message.isUser && (
                        (group.message.id != null && group.message.id == lastAiMessageId) ||
                            (group.message.id == null && lastAiMessageId == null)
                        )
                    // Resolve the ordered timeline for this AI message, preferring (in order):
                    // 1. the live in-progress timeline, if this is the current/last turn
                    // 2. the session-only completed-timeline cache (keyed by message id)
                    // 3. the persisted tool/text blocks on the message itself (survives restarts)
                    val resolvedGroups: List<TurnTimelineGroup> = if (group.message.isUser) {
                        emptyList()
                    } else if (isLastAiMsg && activeTimeline.isNotEmpty()) {
                        activeTimeline.grouped()
                    } else {
                        group.message.id?.let { completedGroupsByMessageId[it] }
                            ?: group.message.contentBlocks.grouped()
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
                        message = group.message,
                        searchQuery = searchQuery,
                        isActiveSearchResult = isActiveResult,
                        onMessageClick = onMessageClick,
                        onEditMessage = onEditMessage,
                        onDownloadAttachment = onDownloadAttachment,
                        userAvatarPainter = userAvatarPainter,
                        aiAvatarPainter = aiAvatarPainter,
                        onRetryMessage = onRetryMessage,
                        addTopPadding = isFirstMessage,
                        viewportTopY = viewportTopY,
                        allMessages = messages,
                        onShowRetryConfirmDialog = { messageId ->
                            retryMessageId = messageId
                            showRetryConfirmDialog = true
                        },
                        isStreaming = isStreamingMessage,
                        projectId = projectId,
                        toolCalls = emptyList(),
                        thinkingContent = fallbackThinkingContent,
                        // Any AI message with a resolved tool-call timeline (live, session-cached,
                        // or persisted) renders the ordered timeline instead of the fixed
                        // thinking-then-tools-then-text layout — for every past turn, not just
                        // the last one.
                        customBody = if (!group.message.isUser && hasToolCalls) {
                            { turnTimelineView(resolvedGroups, isStreaming = isStreamingMessage) }
                        } else {
                            null
                        },
                        bookmarkedMessageIds = bookmarkedMessageIds,
                        onToggleBookmark = onToggleBookmark,
                        onForkFromMessage = onForkFromMessage,
                        voiceOutputEnabled = voiceOutputEnabled,
                    )
                    isFirstMessage = false
                    messageIndex++
                }

                is MessageGroup.OutdatedBranch -> {
                    outdatedBranchComponent(
                        messages = group.messages,
                        userAvatarPainter = userAvatarPainter,
                        aiAvatarPainter = aiAvatarPainter,
                    )
                    isFirstMessage = false
                    messageIndex += group.messages.size
                }
            }
        }

        if (isThinking) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.small),
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
                Text(
                    text = "$spinnerFrame ${stringResource("message.thinking", thinkingElapsedSeconds)}",
                    style = AppTextStyles.bodySecondary,
                    color = AppColors.secondaryIconColor(),
                    modifier = Modifier.padding(top = Spacing.medium),
                )
            }
        }
    }

    // Retry confirmation dialog
    if (showRetryConfirmDialog) {
        AppComponents.alertDialog(
            onDismissRequest = {
                showRetryConfirmDialog = false
                retryMessageId = null
            },
            title = {
                Text(stringResource("message.ai.try.again.confirm.title"))
            },
            text = {
                Text(stringResource("message.ai.try.again.confirm.message"))
            },
            confirmButton = {
                primaryButton(
                    onClick = {
                        retryMessageId?.let { messageId ->
                            onRetryMessage?.invoke(messageId)
                        }
                        showRetryConfirmDialog = false
                        retryMessageId = null
                    },
                ) {
                    Text(stringResource("message.ai.try.again.confirm.confirm"))
                }
            },
            dismissButton = {
                secondaryButton(
                    onClick = {
                        showRetryConfirmDialog = false
                        retryMessageId = null
                    },
                ) {
                    Text(stringResource("message.ai.try.again.confirm.cancel"))
                }
            },
        )
    }
}
