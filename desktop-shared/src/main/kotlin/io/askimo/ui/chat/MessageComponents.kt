/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.dto.ToolCallInfo
import io.askimo.core.chat.dto.ToolCallStatus
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.TurnTimelineGroup
import io.askimo.core.chat.dto.grouped
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.RunCodeEvent
import io.askimo.core.event.internal.parseFilePreviewRequestEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.util.TimeUtil
import io.askimo.core.util.formatFileSize
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.markdownText
import io.askimo.ui.common.ui.revealingMarkdownText
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.common.ui.util.highlightSearchText
import io.askimo.ui.common.ui.util.markdownToPlainText
import io.askimo.ui.service.MessageExportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun messageList(
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
    // persisted `AgentRunRecord.contentBlocks`/`ChatMessageDTO.contentBlocks`) can show their
    // ordered tool/thinking/text timeline — e.g. an agentic run (or regular chat) keeping this
    // visible for every past turn, not just the current "last" message (which instead uses
    // activeTimeline above).
    completedGroupsByMessageId: Map<String, List<TurnTimelineGroup>> = emptyMap(),
    bookmarkedMessageIds: Set<String> = emptySet(),
    onToggleBookmark: ((String) -> Unit)? = null,
    onForkFromMessage: ((String) -> Unit)? = null,
) {
    // Retry confirmation dialog state
    var showRetryConfirmDialog by remember { mutableStateOf(false) }
    var retryMessageId by remember { mutableStateOf<String?>(null) }

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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                            messageDaySeparator(label = dayLabel)
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
                    // Only switch to the ordered-timeline renderer when there's an actual tool
                    // call to show — otherwise keep the existing rich-text rendering path
                    // (streaming reveal, run-code dialog, link clicks, attachments) for the
                    // common tool-free case.
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

        // Show "Thinking..." indicator when AI is processing but hasn't returned first token
        if (isThinking) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = "$spinnerFrame ${stringResource("message.thinking", thinkingElapsedSeconds)}",
                    style = AppTextStyles.bodySecondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun messageBubble(
    message: ChatMessageDTO,
    searchQuery: String = "",
    isActiveSearchResult: Boolean = false,
    onMessageClick: ((String, Instant) -> Unit)? = null,
    onEditMessage: ((ChatMessageDTO) -> Unit)? = null,
    onDownloadAttachment: ((FileAttachmentDTO) -> Unit)? = null,
    userAvatarPainter: BitmapPainter? = null,
    aiAvatarPainter: BitmapPainter? = null,
    onRetryMessage: ((String) -> Unit)? = null,
    addTopPadding: Boolean = false,
    viewportTopY: Float? = null,
    allMessages: List<ChatMessageDTO> = emptyList(),
    onShowRetryConfirmDialog: ((String) -> Unit)? = null,
    isOutdatedMessage: Boolean = false,
    isStreaming: Boolean = false,
    projectId: String? = null,
    toolCalls: List<ToolCallInfo> = emptyList(),
    thinkingContent: String = "",
    // When non-null, rendered instead of the built-in thinking/toolCalls/text sections —
    // used to show an ordered (chronological) tool/thinking/text timeline for a message
    // instead of the fixed thinking-then-tools-then-text layout. See `turnTimelineView`.
    customBody: (@Composable () -> Unit)? = null,
    bookmarkedMessageIds: Set<String> = emptySet(),
    onToggleBookmark: ((String) -> Unit)? = null,
    onForkFromMessage: ((String) -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (addTopPadding) Modifier.padding(top = 20.dp) else Modifier),
    ) {
        if (message.isUser) {
            userMessageBubble(
                message = message,
                searchQuery = searchQuery,
                isActiveSearchResult = isActiveSearchResult,
                onMessageClick = onMessageClick,
                onEditMessage = onEditMessage,
                onDownloadAttachment = onDownloadAttachment,
                userAvatarPainter = userAvatarPainter,
                isOutdatedMessage = isOutdatedMessage,
                isBookmarked = message.id != null && message.id in bookmarkedMessageIds,
                onToggleBookmark = if (message.id != null) onToggleBookmark else null,
            )
        } else {
            aiMessageBubble(
                message = message,
                searchQuery = searchQuery,
                isActiveSearchResult = isActiveSearchResult,
                onMessageClick = onMessageClick,
                onEditMessage = onEditMessage,
                onDownloadAttachment = onDownloadAttachment,
                aiAvatarPainter = aiAvatarPainter,
                onRetryMessage = onRetryMessage,
                viewportTopY = viewportTopY,
                allMessages = allMessages,
                onShowRetryConfirmDialog = onShowRetryConfirmDialog,
                isOutdatedMessage = isOutdatedMessage,
                isStreaming = isStreaming,
                projectId = projectId,
                toolCalls = toolCalls,
                thinkingContent = thinkingContent,
                customBody = customBody,
                isBookmarked = message.id != null && message.id in bookmarkedMessageIds,
                onToggleBookmark = if (message.id != null) onToggleBookmark else null,
                onForkFromMessage = if (message.id != null) onForkFromMessage else null,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun userMessageBubble(
    message: ChatMessageDTO,
    searchQuery: String = "",
    isActiveSearchResult: Boolean = false,
    onMessageClick: ((String, Instant) -> Unit)? = null,
    onEditMessage: ((ChatMessageDTO) -> Unit)? = null,
    onDownloadAttachment: ((FileAttachmentDTO) -> Unit)? = null,
    userAvatarPainter: BitmapPainter? = null,
    isOutdatedMessage: Boolean = false,
    isBookmarked: Boolean = false,
    onToggleBookmark: ((String) -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    var isHovered by remember { mutableStateOf(false) }
    var showCopyFeedback by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val isClickable = onMessageClick != null && message.id != null && message.timestamp != null

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
    ) {
        val maxUserBubbleWidth = when {
            maxWidth < 600.dp -> (maxWidth * 0.9f).coerceAtLeast(200.dp)
            maxWidth < 1200.dp -> (maxWidth * 0.70f).coerceAtMost(700.dp)
            else -> (maxWidth * 0.55f).coerceAtMost(850.dp)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top,
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(max = maxUserBubbleWidth)
                        .then(
                            if (isClickable) {
                                Modifier
                                    .clickable { onMessageClick.invoke(message.id!!, message.timestamp!!) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            } else {
                                Modifier
                            },
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOutdatedMessage) {
                            AppComponents.userMessageBackground().copy(alpha = 0.5f)
                        } else {
                            AppComponents.userMessageBackground()
                        },
                        contentColor = if (isOutdatedMessage) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        } else {
                            AppComponents.userMessageContentColor()
                        },
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column {
                        if (message.attachments.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, top = Spacing.medium),
                                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                            ) {
                                message.attachments.forEach { attachment ->
                                    fileAttachmentChip(attachment = attachment, onDownload = onDownloadAttachment)
                                }
                            }
                        }

                        SelectionContainer {
                            if (searchQuery.isNotBlank()) {
                                Text(
                                    text = highlightSearchText(
                                        text = message.content,
                                        query = searchQuery,
                                        highlightColor = Color(0xFFFFD54F), // amber-300 — visible on any bg
                                        isActiveResult = isActiveSearchResult,
                                        activeHighlightColor = Color(0xFFFF8F00), // amber-800 — bold active match
                                    ),
                                    modifier = Modifier.padding(Spacing.medium),
                                    style = AppTextStyles.body,
                                )
                            } else {
                                Text(
                                    text = message.content,
                                    modifier = Modifier.padding(Spacing.medium),
                                    style = AppTextStyles.body,
                                )
                            }
                        }

                        if (isOutdatedMessage) {
                            Text(
                                text = stringResource("outdated.label"),
                                style = AppTextStyles.hint,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.small, top = Spacing.extraSmall),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                        .border(width = 2.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (userAvatarPainter != null) {
                        Image(
                            painter = userAvatarPainter,
                            contentDescription = "User",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(32.dp).clip(CircleShape),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Action controls — reserve space, show on hover
            Spacer(modifier = Modifier.height(Spacing.small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCopyFeedback) {
                    Text(
                        text = stringResource("mermaid.feedback.copied"),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small)
                            .padding(horizontal = Spacing.large, vertical = Spacing.small),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = AppTextStyles.groupTitle,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Box(
                    modifier = Modifier.height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isHovered) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                themedTooltip(text = stringResource("message.copy")) {
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(message.content))
                                            showCopyFeedback = true
                                            coroutineScope.launch {
                                                delay(2000.milliseconds)
                                                showCopyFeedback = false
                                            }
                                        },
                                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = stringResource("message.copy.description"),
                                            modifier = Modifier.size(16.dp),
                                            tint = AppTextStyles.secondaryContent,
                                        )
                                    }
                                }

                                if (onEditMessage != null) {
                                    themedTooltip(text = stringResource("message.edit")) {
                                        IconButton(
                                            onClick = { onEditMessage.invoke(message) },
                                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = stringResource("message.edit.description"),
                                                modifier = Modifier.size(16.dp),
                                                tint = AppTextStyles.secondaryContent,
                                            )
                                        }
                                    }
                                }

                                if (onToggleBookmark != null && message.id != null) {
                                    bookmarkToggleButton(
                                        msgId = message.id!!,
                                        isBookmarked = isBookmarked,
                                        onToggleBookmark = onToggleBookmark,
                                    )
                                }

                                // Timestamp — visible on hover, right side of action bar
                                message.timestamp?.let { ts ->
                                    Spacer(modifier = Modifier.width(Spacing.small))
                                    themedTooltip(text = TimeUtil.formatFullDateTime(ts, LocalizationManager.getCurrentLocale())) {
                                        Text(
                                            text = LocalizationManager.formatMessageTime(ts),
                                            style = AppTextStyles.hint,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(40.dp))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun aiMessageBubble(
    message: ChatMessageDTO,
    searchQuery: String = "",
    isActiveSearchResult: Boolean = false,
    onMessageClick: ((String, Instant) -> Unit)? = null,
    onEditMessage: ((ChatMessageDTO) -> Unit)? = null,
    onDownloadAttachment: ((FileAttachmentDTO) -> Unit)? = null,
    aiAvatarPainter: BitmapPainter? = null,
    onRetryMessage: ((String) -> Unit)? = null,
    viewportTopY: Float? = null,
    allMessages: List<ChatMessageDTO> = emptyList(),
    onShowRetryConfirmDialog: ((String) -> Unit)? = null,
    isOutdatedMessage: Boolean = false,
    isStreaming: Boolean = false,
    projectId: String? = null,
    toolCalls: List<ToolCallInfo> = emptyList(),
    thinkingContent: String = "",
    customBody: (@Composable () -> Unit)? = null,
    isBookmarked: Boolean = false,
    onToggleBookmark: ((String) -> Unit)? = null,
    onForkFromMessage: ((String) -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    var showCopyFeedback by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var pendingRunRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val isClickable = onMessageClick != null && message.id != null && message.timestamp != null
    val aiContentColor = if (isOutdatedMessage) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Tool call collapsible state — auto-expand when any tool starts running,
    // auto-collapse when all tools are done to keep history clean.
    var toolCallsExpanded by remember { mutableStateOf(false) }
    val hasRunningTool = toolCalls.any { it.status == ToolCallStatus.RUNNING }
    LaunchedEffect(hasRunningTool) {
        toolCallsExpanded = hasRunningTool
    }

    // Thinking section collapsible state — auto-expand when thinking tokens start arriving,
    // auto-collapse once streaming completes to reduce visual noise in history.
    var thinkingExpanded by remember { mutableStateOf(false) }
    val hasThinkingContent = thinkingContent.isNotEmpty()
    LaunchedEffect(hasThinkingContent) {
        if (hasThinkingContent) thinkingExpanded = true
    }
    LaunchedEffect(isStreaming) {
        if (!isStreaming) thinkingExpanded = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            // AI avatar — top padding aligns the circle with the first text line
            Box(
                modifier = Modifier
                    .padding(top = Spacing.medium)
                    .size(32.dp)
                    .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape)
                    .border(width = 2.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape = CircleShape),
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

            // AI message content
            Box {
                CompositionLocalProvider(LocalContentColor provides aiContentColor) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isClickable) {
                                    Modifier
                                        .clickable { onMessageClick.invoke(message.id!!, message.timestamp!!) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Column {
                            if (customBody != null) {
                                customBody()
                            } else {
                                // Thinking/reasoning collapsible section — shown when the model exposes reasoning
                                if (thinkingContent.isNotEmpty()) {
                                    thinkingSection(
                                        thinkingContent = thinkingContent,
                                        isStreaming = isStreaming,
                                        isExpanded = thinkingExpanded,
                                        onToggle = { thinkingExpanded = !thinkingExpanded },
                                    )
                                }

                                // Tool call collapsible section — shown only during streaming
                                if (toolCalls.isNotEmpty()) {
                                    toolCallsSection(
                                        toolCalls = toolCalls,
                                        isExpanded = toolCallsExpanded,
                                        onToggle = { toolCallsExpanded = !toolCallsExpanded },
                                    )
                                }

                                if (message.attachments.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, top = Spacing.medium),
                                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                    ) {
                                        message.attachments.forEach { attachment ->
                                            fileAttachmentChip(attachment = attachment, onDownload = onDownloadAttachment)
                                        }
                                    }
                                }

                                if (searchQuery.isNotBlank()) {
                                    SelectionContainer {
                                        Text(
                                            text = highlightSearchText(
                                                text = markdownToPlainText(message.content),
                                                query = searchQuery,
                                                highlightColor = Color(0xFFFFD54F),
                                                isActiveResult = isActiveSearchResult,
                                                activeHighlightColor = Color(0xFFFF8F00),
                                            ),
                                            modifier = Modifier.padding(start = Spacing.medium, end = 48.dp, top = Spacing.medium, bottom = Spacing.medium),
                                            style = AppTextStyles.body,
                                        )
                                    }
                                } else {
                                    val onLinkClickHandler: (String) -> Unit = { url ->
                                        if (url.startsWith("file://")) {
                                            if (projectId != null) {
                                                // Project chat — let the side panel handle it in the file viewer
                                                EventBus.post(parseFilePreviewRequestEvent(url))
                                            } else {
                                                // Non-project chat — fall back to OS file browser
                                                try {
                                                    val filePath = parseFilePreviewRequestEvent(url).filePath
                                                    val file = File(filePath)
                                                    if (file.exists() && Desktop.isDesktopSupported()) {
                                                        Desktop.getDesktop().open(file)
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                    if (isStreaming) {
                                        markdownText(
                                            markdown = message.content,
                                            modifier = Modifier.padding(start = Spacing.medium, end = 48.dp, top = Spacing.medium, bottom = Spacing.medium),
                                            viewportTopY = viewportTopY,
                                            isStreaming = true,
                                            onRunRequest = { cmd, lang -> pendingRunRequest = Pair(cmd, lang) },
                                            messageId = message.id,
                                            onLinkClick = onLinkClickHandler,
                                        )
                                    } else {
                                        revealingMarkdownText(
                                            markdown = message.content,
                                            modifier = Modifier.padding(start = Spacing.medium, end = 48.dp, top = Spacing.medium, bottom = Spacing.medium),
                                            onRunRequest = { cmd, lang -> pendingRunRequest = Pair(cmd, lang) },
                                            messageId = message.id,
                                            onLinkClick = onLinkClickHandler,
                                        )
                                    }
                                }

                                if (isOutdatedMessage) {
                                    Text(
                                        text = stringResource("outdated.label"),
                                        style = AppTextStyles.hint,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontStyle = FontStyle.Italic,
                                        modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.small, top = Spacing.extraSmall),
                                    )
                                }
                            }
                        }
                    }
                }

                // Failed retry icon at bottom-right corner
                if (message.isFailed && message.id != null && onRetryMessage != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 4.dp, end = 4.dp),
                    ) {
                        themedTooltip(text = stringResource("action.retry")) {
                            IconButton(
                                onClick = { onRetryMessage(message.id!!) },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource("action.retry"),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                // Bookmark badge — always visible on pinned messages
                if (isBookmarked) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = stringResource("message.bookmark.description"),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 4.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Action controls bar
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(40.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    themedTooltip(text = stringResource("message.copy")) {
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(message.content))
                                showCopyFeedback = true
                                coroutineScope.launch {
                                    delay(2000.milliseconds)
                                    showCopyFeedback = false
                                }
                            },
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource("message.copy.description"),
                                modifier = Modifier.size(16.dp),
                                tint = AppTextStyles.secondaryContent,
                            )
                        }
                    }

                    if (onEditMessage != null) {
                        themedTooltip(text = stringResource("message.ai.edit")) {
                            IconButton(
                                onClick = { onEditMessage.invoke(message) },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource("message.ai.edit.description"),
                                    modifier = Modifier.size(16.dp),
                                    tint = AppTextStyles.secondaryContent,
                                )
                            }
                        }
                    }

                    if (onRetryMessage != null) {
                        themedTooltip(text = stringResource("message.ai.try.again")) {
                            IconButton(
                                onClick = {
                                    message.id?.let { messageId ->
                                        val isLatestMessage = allMessages.lastOrNull { !it.isUser }?.id == messageId
                                        if (isLatestMessage) {
                                            onRetryMessage.invoke(messageId)
                                        } else {
                                            onShowRetryConfirmDialog?.invoke(messageId)
                                        }
                                    }
                                },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource("message.ai.try.again.description"),
                                    modifier = Modifier.size(16.dp),
                                    tint = AppTextStyles.secondaryContent,
                                )
                            }
                        }
                    }

                    // Export to PDF button
                    val exportPdfDialogTitle = stringResource("message.ai.export.pdf.dialog.title")
                    themedTooltip(text = stringResource("message.ai.export.pdf")) {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isExporting = true
                                    withContext(Dispatchers.IO) {
                                        val file = FileDialogUtils.pickSavePath(
                                            suggestedName = "ai-response",
                                            extension = "pdf",
                                            title = exportPdfDialogTitle,
                                        )
                                        if (file != null) {
                                            MessageExportService.export(
                                                content = message.content,
                                                targetFile = file,
                                                format = MessageExportService.ExportFormat.PDF,
                                            )
                                        }
                                    }
                                    isExporting = false
                                }
                            },
                            enabled = !isExporting,
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource("message.ai.export.pdf"),
                                modifier = Modifier.size(16.dp),
                                tint = if (isExporting) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    // Bookmark toggle
                    if (onToggleBookmark != null && message.id != null) {
                        val msgId = message.id!!
                        themedTooltip(
                            text = if (isBookmarked) stringResource("message.bookmark.remove") else stringResource("message.bookmark"),
                        ) {
                            IconButton(
                                onClick = { onToggleBookmark.invoke(msgId) },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = stringResource("message.bookmark.description"),
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isBookmarked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }

                    // Fork session from here
                    if (onForkFromMessage != null && message.id != null && !isStreaming) {
                        val msgId = message.id!!
                        themedTooltip(text = stringResource("message.ai.fork")) {
                            IconButton(
                                onClick = { onForkFromMessage.invoke(msgId) },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                                    contentDescription = stringResource("message.ai.fork.description"),
                                    modifier = Modifier.size(16.dp),
                                    tint = AppTextStyles.secondaryContent,
                                )
                            }
                        }
                    }
                }
            }

            if (showCopyFeedback) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource("mermaid.feedback.copied"),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = AppTextStyles.groupTitle,
                )
            }

            // Token usage — inline after action icons, visible on hover only
            val total = message.totalTokens
            val input = message.inputTokens
            val output = message.outputTokens
            val durationMs = message.durationMs
            if (total != null && total > 0 && !isStreaming && isHovered) {
                Spacer(modifier = Modifier.width(Spacing.small))
                themedTooltip(text = stringResource("message.token.usage.tooltip")) {
                    Text(
                        text = buildString {
                            if (input != null && output != null) {
                                append(
                                    stringResource(
                                        "message.token.usage",
                                        LocalizationManager.formatNumber(total),
                                        LocalizationManager.formatNumber(input),
                                        LocalizationManager.formatNumber(output),
                                    ),
                                )
                            } else {
                                append("${LocalizationManager.formatNumber(total)} tokens")
                            }
                            if (durationMs != null && durationMs >= 0) {
                                val durationLabel = when {
                                    durationMs < 1000L -> "${durationMs}ms"
                                    else -> "${"%.1f".format(durationMs / 1000.0)}s"
                                }
                                append(" · ")
                                append(durationLabel)
                            }
                        },
                        style = AppTextStyles.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }

            if (isHovered && !isStreaming) {
                message.timestamp?.let { ts ->
                    Spacer(modifier = Modifier.width(Spacing.small))
                    val timestampPrefix = if (total != null && total > 0) "· " else ""
                    themedTooltip(text = TimeUtil.formatFullDateTime(ts, LocalizationManager.getCurrentLocale())) {
                        Text(
                            text = "$timestampPrefix${LocalizationManager.formatMessageTime(ts)}",
                            style = AppTextStyles.hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        // Edited indicator
        if (message.isEdited) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = stringResource("message.edited.indicator"),
                    style = AppTextStyles.hint,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }

    // Run code dialog — outside SelectionContainer
    val runRequest = pendingRunRequest
    if (runRequest != null) {
        AppComponents.alertDialog(
            onDismissRequest = { pendingRunRequest = null },
            title = {
                Text(
                    text = stringResource("code.run.dialog.title"),
                    style = AppTextStyles.sectionTitle,
                )
            },
            text = {
                Text(
                    text = stringResource("code.run.dialog.message"),
                    style = AppTextStyles.bodySecondary,
                )
            },
            dismissButton = {
                secondaryButton(onClick = {
                    pendingRunRequest = null
                    EventBus.post(RunCodeEvent(code = runRequest.first, language = runRequest.second, couldExecute = false))
                }) {
                    Text(stringResource("code.run.dialog.paste"))
                }
            },
            confirmButton = {
                primaryButton(onClick = {
                    pendingRunRequest = null
                    EventBus.post(RunCodeEvent(code = runRequest.first, language = runRequest.second, couldExecute = true))
                }) {
                    Text(stringResource("code.run.dialog.execute"))
                }
            },
        )
    }
}

/**
 * Collapsible section that displays AI thinking/reasoning tokens above the response text.
 *
 * Auto-expands as soon as the first thinking token arrives during streaming; stays expanded
 * so the user can review the full trace after the response completes. Can be collapsed
 * manually by clicking the header.
 *
 * Displays a "💭 Thinking…" header while streaming and "💭 Thought" when complete.
 * The body uses a muted italic style visually distinct from the main response.
 */
@Composable
internal fun thinkingSection(
    thinkingContent: String,
    isStreaming: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val headerText = if (isStreaming) {
        stringResource("message.thinking.section.streaming")
    } else {
        stringResource("message.thinking.section.done")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.medium, end = Spacing.medium, top = Spacing.small),
    ) {
        // Header row — clickable to toggle expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(vertical = Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = headerColor,
            )
            Text(
                text = headerText,
                style = AppTextStyles.hint,
                color = headerColor,
                fontStyle = FontStyle.Italic,
            )
        }

        // Expanded body — blockquote style: left accent bar + scrollable muted text
        if (isExpanded && thinkingContent.isNotEmpty()) {
            val scrollState = rememberScrollState()
            val density = LocalDensity.current
            var viewportHeightPx by remember { mutableStateOf(0) }

            LaunchedEffect(thinkingContent) {
                if (isStreaming) scrollState.animateScrollTo(scrollState.maxValue)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = Spacing.extraSmall)
                    .onSizeChanged { viewportHeightPx = it.height },
            ) {
                // Left accent bar — blockquote-style vertical line, stretches to match content height
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )

                // Scrollable content next to the bar
                Box(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(scrollState)
                            .padding(start = Spacing.small, end = Spacing.medium, top = 2.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = thinkingContent,
                            style = AppTextStyles.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            fontStyle = FontStyle.Italic,
                        )
                    }
                    // Only render once we know the viewport height (after the first layout pass).
                    if (viewportHeightPx > 0) {
                        VerticalScrollbar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .height(with(density) { viewportHeightPx.toDp() }),
                            adapter = rememberScrollbarAdapter(scrollState),
                            style = AppComponents.scrollbarStyle(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders timeline groups in chronological order — tool-call and thinking groups reuse this
 * file's own collapsible sections ([toolCallsSection]/[thinkingSection]); token groups render
 * as normal markdown; status groups show as a small muted subtitle. Each group gets its own
 * remembered expand/collapse state, keyed by [stableKey] — content-based rather than list
 * position, since `grouped()` runs `collapsedEffectiveTools()` first, which can drop earlier
 * groups entirely (superseded tool retries + the thinking that led to them) as new entries
 * stream in. That shifts later groups' *indices* between recompositions even though their
 * *content* hasn't changed, which — if keyed by index — would reattach one group's remembered
 * expand/collapse state to a completely different, unrelated group occupying the same slot.
 *
 * Used both for the live streaming turn (agentic runs) and for finalized/historical AI
 * messages (via [messageBubble]'s `customBody`), so order is preserved identically in both.
 */
@Composable
internal fun turnTimelineView(
    groups: List<TurnTimelineGroup>,
    isStreaming: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        groups.forEachIndexed { index, group ->
            // Only the *last* group can still be actively receiving new streamed deltas (see
            // grouped()) — every earlier group's content is already frozen. See [renderKey].
            val isStreamingTail = isStreaming && index == groups.lastIndex
            key(group.renderKey(isStreamingTail)) {
                when (group) {
                    is TurnTimelineGroup.StatusGroup -> {
                        Text(
                            text = group.entries.last().text,
                            style = AppTextStyles.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is TurnTimelineGroup.ToolGroup -> {
                        var expanded by remember { mutableStateOf(true) }
                        toolCallsSection(
                            toolCalls = group.entries.map { it.toolCall },
                            isExpanded = expanded,
                            onToggle = { expanded = !expanded },
                        )
                    }

                    is TurnTimelineGroup.ThinkingGroup -> {
                        var expanded by remember { mutableStateOf(true) }
                        thinkingSection(
                            thinkingContent = group.text,
                            isStreaming = false,
                            isExpanded = expanded,
                            onToggle = { expanded = !expanded },
                        )
                    }

                    is TurnTimelineGroup.TokenGroup -> {
                        markdownText(
                            markdown = group.text,
                            modifier = Modifier.fillMaxWidth().padding(start = Spacing.medium, end = 48.dp),
                        )
                    }
                }
            }
        }

        // After the AI's last tool call finishes, there's a gap where the model is
        // processing the tool result before it emits the next thinking/text chunk — with
        // no other signal during that gap, the user has no feedback that the AI is still
        // working (it looks like it's halted). Show a ticking "Processing..." row for
        // exactly that window; it disappears the instant a new thinking/token group is
        // appended to the timeline (grouped() will no longer report the ToolGroup as last).
        val lastGroup = groups.lastOrNull()
        if (isStreaming &&
            lastGroup is TurnTimelineGroup.ToolGroup &&
            lastGroup.entries.all { it.toolCall.status == ToolCallStatus.DONE }
        ) {
            key("processing:" + lastGroup.stableKey()) {
                aiProcessingIndicator()
            }
        }
    }
}

/**
 * Ticking "AI is processing..." row shown right after the last tool call in a turn
 * finishes, while the model works on the tool result before emitting its next
 * thinking/token chunk (see [turnTimelineView]). Self-contained ticker, same pattern as
 * [toolCallsSection]'s elapsed timer — the elapsed count starts from the moment this
 * composable first appears (i.e. right when the tool flipped to DONE), and the whole row
 * disappears as soon as the caller ([turnTimelineView]) stops rendering it.
 */
@Composable
private fun aiProcessingIndicator() {
    val startedAtMillis = remember { System.currentTimeMillis() }
    var nowMillis by remember { mutableStateOf(startedAtMillis) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1.seconds)
        }
    }
    val elapsedSeconds = ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.medium, end = Spacing.medium, top = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource("message.processing", elapsedSeconds),
            style = AppTextStyles.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            fontStyle = FontStyle.Italic,
        )
    }
}

/**
 * A stable, content-derived identity for a [TurnTimelineGroup], used as the [key] in
 * [turnTimelineView] so each group's remembered expand/collapse state stays attached to the
 * group it belongs to — not to whatever group happens to occupy the same list index after
 * `grouped()` drops superseded tool retries (see [turnTimelineView] doc).
 *
 * [TurnTimelineGroup.ToolGroup] is keyed by its tool names only (not status/arguments/result),
 * since those mutate in place as a call goes from running to done — the *identity* of "this is
 * the group for tool X (then Y, ...)" is what needs to stay stable, not its current state.
 *
 * NOTE: [ThinkingGroup]/[TokenGroup]/[StatusGroup] embed their full accumulated text, which
 * `grouped()` keeps growing (via concatenation) for as long as a group is the *streaming tail*
 * — so this key is only stable once a group's content is frozen (i.e. it's no longer the tail
 * receiving new deltas, or the turn isn't streaming at all). While actively streaming, callers
 * must use [renderKey] instead, which substitutes a fixed key for the tail group.
 */
private fun TurnTimelineGroup.stableKey(): String = when (this) {
    is TurnTimelineGroup.StatusGroup -> "status:" + entries.joinToString("|") { it.text }
    is TurnTimelineGroup.ToolGroup -> "tool:" + entries.joinToString("|") { it.toolCall.toolName }
    is TurnTimelineGroup.ThinkingGroup -> "thinking:$text"
    is TurnTimelineGroup.TokenGroup -> "token:$text"
}

/**
 * The [key] used for a group in [turnTimelineView] — [stableKey] for every group *except* the
 * one currently receiving new streamed deltas ([isStreamingTail]), which is keyed by a fixed,
 * content-independent marker instead (`"live:thinking"`/`"live:token"`/`"live:status"`).
 *
 * Needed because `grouped()` keeps appending new deltas to the *tail* group's accumulated text
 * while streaming, which would otherwise change its [stableKey] on every single delta —
 * discarding the group's `remember`ed state (expand/collapse, [thinkingSection]'s scroll
 * position, etc.) each time, and risking a duplicate-key crash if two tail contents ever
 * coincide. The fixed marker never collides with a [stableKey] value (those never start with
 * `"live:"`), and since only one group can be the streaming tail at a time, no two groups ever
 * compete for it. [TurnTimelineGroup.ToolGroup] is excluded — its [stableKey] already ignores
 * status/arguments/result, so it's already stable while streaming.
 *
 * Once a group stops being the tail, its content is frozen and it reverts to [stableKey].
 */
private fun TurnTimelineGroup.renderKey(isStreamingTail: Boolean): String {
    if (isStreamingTail) {
        when (this) {
            is TurnTimelineGroup.ThinkingGroup -> return "live:thinking"
            is TurnTimelineGroup.TokenGroup -> return "live:token"
            is TurnTimelineGroup.StatusGroup -> return "live:status"
            is TurnTimelineGroup.ToolGroup -> {}
        }
    }
    return stableKey()
}

/**
 * Collapsible section that displays AI tool calls above the response text.
 * Shows "▶ Running tool… (Ns)" — with a live-ticking elapsed timer — when any tool is active,
 * "▶ Used N tool(s)" when all are done. Expands automatically when a tool starts running;
 * collapses manually by the user.
 *
 * The elapsed timer exists because a tool call (e.g. a long shell command) can run silently
 * for a while with no other events — without it the user has no feedback that the AI is still
 * working, unlike the pre-first-token "Thinking... Ns" indicator (which stops as soon as any
 * event, including a tool call starting, arrives — see ChatViewModel.subscribeToThread).
 */
@Composable
internal fun toolCallsSection(
    toolCalls: List<ToolCallInfo>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val hasRunning = toolCalls.any { it.status == ToolCallStatus.RUNNING }

    // Live elapsed-seconds ticker, driven by the earliest still-running call's start time —
    // only ticks while a tool is actually running, so it's a no-op (and stops recomposing)
    // once every call in this group has completed.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasRunning) {
        while (hasRunning) {
            nowMillis = System.currentTimeMillis()
            delay(1.seconds)
        }
    }
    val earliestRunningStart = toolCalls
        .filter { it.status == ToolCallStatus.RUNNING }
        .minOfOrNull { it.startedAtMillis }

    val headerText = if (hasRunning && earliestRunningStart != null) {
        val elapsedSeconds = ((nowMillis - earliestRunningStart) / 1000).coerceAtLeast(0)
        stringResource("tool.call.header.running", elapsedSeconds)
    } else {
        stringResource("tool.call.header.done", toolCalls.size)
    }
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.medium, end = Spacing.medium, top = Spacing.small),
    ) {
        // Collapsed / expanded header row — full width, hand cursor
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(vertical = Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = headerColor,
            )
            Text(
                text = headerText,
                style = AppTextStyles.hint,
                color = headerColor,
            )
        }

        // Expanded: one row per tool call
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.extraSmall, bottom = Spacing.extraSmall),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                toolCalls.forEach { toolCall ->
                    toolCallRow(toolCall)
                }
            }
        }
    }
}

/**
 * Single row showing a tool name, its running/done/failed status icon, and an optional
 * expandable section with the raw arguments and result.
 *
 * Uses a blockquote-style left accent bar whose colour reflects the call's status:
 *   • running  → onSurfaceVariant (neutral)
 *   • done     → primary (success)
 *   • failed   → error (destructive)
 */
@Composable
private fun toolCallRow(toolCall: ToolCallInfo) {
    val isDone = toolCall.status == ToolCallStatus.DONE
    val hasFailed = toolCall.hasFailed
    val hasDetails = !toolCall.arguments.isNullOrBlank() || !toolCall.result.isNullOrBlank()

    var detailsExpanded by remember { mutableStateOf(false) }

    val barColor = when {
        hasFailed -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        isDone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val statusIconColor = when {
        hasFailed -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        isDone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // Left accent bar — colour reflects tool status
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color = barColor, shape = RoundedCornerShape(2.dp)),
        )

        // Content column — header + optional expandable details
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.small),
        ) {
            // Header row: icon + name + status indicator (+ expand chevron if details exist)
            Row(
                modifier = if (hasDetails && isDone) {
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { detailsExpanded = !detailsExpanded },
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier.fillMaxWidth()
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = statusIconColor,
                )
                Text(
                    text = toolCall.toolName,
                    style = AppTextStyles.hint,
                    modifier = Modifier.weight(1f),
                )
                // Status indicator
                when {
                    !isDone -> Text(
                        text = "⏳",
                        style = AppTextStyles.hint,
                        color = statusIconColor,
                    )

                    hasFailed -> Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource("tool.call.status.failed"),
                        modifier = Modifier.size(12.dp),
                        tint = statusIconColor,
                    )

                    else -> Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource("tool.call.status.done"),
                        modifier = Modifier.size(12.dp),
                        tint = statusIconColor,
                    )
                }
                // Expand chevron (only when done and has details)
                if (hasDetails && isDone) {
                    Icon(
                        imageVector = if (detailsExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }

            // Expandable details: arguments + result
            if (detailsExpanded && hasDetails) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.extraSmall),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (!toolCall.arguments.isNullOrBlank()) {
                        toolCallDetailSection(
                            label = stringResource("tool.call.detail.arguments"),
                            content = toolCall.arguments!!,
                        )
                    }
                    if (!toolCall.result.isNullOrBlank()) {
                        toolCallDetailSection(
                            label = stringResource("tool.call.detail.result"),
                            content = toolCall.result!!,
                            labelColor = if (hasFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else null,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A small labelled text block used inside an expanded tool-call row.
 */
@Composable
private fun toolCallDetailSection(
    label: String,
    content: String,
    labelColor: Color? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = AppTextStyles.hint,
            color = labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        SelectionContainer {
            Text(
                text = content,
                style = AppTextStyles.codeSecondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = Spacing.extraSmall, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun fileAttachmentChip(
    attachment: FileAttachmentDTO,
    onDownload: ((FileAttachmentDTO) -> Unit)? = null,
) {
    themedTooltip(
        text = if (onDownload != null) stringResource("attachment.download") else "",
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onDownload != null) {
                        Modifier
                            .clickable { onDownload(attachment) }
                            .pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    },
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = AppTextStyles.secondaryContent,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.fileName,
                        style = AppTextStyles.caption,
                    )
                    Text(
                        text = formatFileSize(attachment.size),
                        style = AppTextStyles.hint,
                    )
                }
                if (onDownload != null) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource("attachment.download.description"),
                        modifier = Modifier.size(16.dp),
                        tint = AppTextStyles.secondaryContent,
                    )
                }
            }
        }
    }
}

@Composable
fun aiMessageEditDialog(
    message: ChatMessageDTO,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val textFieldState = rememberTextFieldState(message.content)
    val textScrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .width(900.dp)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Title
                Text(
                    text = stringResource("message.ai.edit"),
                    style = AppTextStyles.pageTitle,
                )

                // Scrollable content field with visible scrollbar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(600.dp),
                ) {
                    OutlinedTextField(
                        state = textFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(end = 12.dp), // room for the scrollbar
                        textStyle = AppTextStyles.body,
                        colors = AppComponents.outlinedTextFieldColors(),
                        label = { Text(stringResource("message.ai.edit.content.label")) },
                        scrollState = textScrollState,
                    )

                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(textScrollState),
                        style = AppComponents.scrollbarStyle(),
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource("action.cancel"))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    primaryButton(
                        onClick = {
                            onSave(textFieldState.text.toString())
                            onDismiss()
                        },
                    ) {
                        Text(stringResource("action.save"))
                    }
                }
            }
        }
    }
}

/** Day separator shown between messages from different calendar days. */
@Composable
private fun messageDaySeparator(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Text(
            text = label,
            style = AppTextStyles.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun bookmarkToggleButton(
    msgId: String,
    isBookmarked: Boolean,
    onToggleBookmark: (String) -> Unit,
) {
    themedTooltip(
        text = if (isBookmarked) stringResource("message.bookmark.remove") else stringResource("message.bookmark"),
    ) {
        IconButton(
            onClick = { onToggleBookmark(msgId) },
            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = stringResource("message.bookmark.description"),
                modifier = Modifier.size(16.dp),
                tint = if (isBookmarked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
