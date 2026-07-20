/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalComposeUiApi::class)
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        // Group messages into active and outdated branches
        val messageGroups = groupMessagesWithOutdatedBranches(messages)
        val locale = LocalizationManager.getCurrentLocale()

        var messageIndex = 0
        var isFirstMessage = true
        var lastDayLabel: String? = null
        messageGroups.forEach { group ->
            when (group) {
                is MessageGroup.ActiveMessage -> {
                    // Day separator — show when the message date is different from the previous one
                    val ts = group.message.timestamp
                    if (ts != null) {
                        val dayLabel = formatDayLabel(ts, locale, currentDate)
                        if (dayLabel != lastDayLabel) {
                            messageDaySeparator(label = dayLabel)
                            lastDayLabel = dayLabel
                        }
                    }

                    val isActiveResult = searchQuery.isNotBlank() && messageIndex == currentSearchResultIndex
                    // A message is streaming when it's the last AI message still without a persisted ID
                    val isStreamingMessage = !group.message.isUser && group.message.id == null
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
                    style = MaterialTheme.typography.bodyMedium,
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
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                Text(
                                    text = message.content,
                                    modifier = Modifier.padding(Spacing.medium),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        if (isOutdatedMessage) {
                            Text(
                                text = stringResource("outdated.label"),
                                style = MaterialTheme.typography.labelSmall,
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
                        style = MaterialTheme.typography.labelLarge,
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
                                                delay(2000)
                                                showCopyFeedback = false
                                            }
                                        },
                                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = stringResource("message.copy.description"),
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                // Timestamp — visible on hover, right side of action bar
                                message.timestamp?.let { ts ->
                                    Spacer(modifier = Modifier.width(Spacing.small))
                                    themedTooltip(text = TimeUtil.formatFullDateTime(ts, LocalizationManager.getCurrentLocale())) {
                                        Text(
                                            text = formatMessageTime(ts),
                                            style = MaterialTheme.typography.labelSmall,
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
            // AI avatar
            Box(
                modifier = Modifier
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
                                        style = MaterialTheme.typography.bodyMedium,
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
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.small, top = Spacing.extraSmall),
                                )
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Export to PDF button
                    themedTooltip(text = stringResource("message.ai.export.pdf")) {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isExporting = true
                                    withContext(Dispatchers.IO) {
                                        val file = FileDialogUtils.pickSavePath(
                                            suggestedName = "ai-response",
                                            extension = "pdf",
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
                    style = MaterialTheme.typography.labelLarge,
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
                        style = MaterialTheme.typography.labelSmall,
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
                            text = "$timestampPrefix${formatMessageTime(ts)}",
                            style = MaterialTheme.typography.labelSmall,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }

    // Run code dialog — outside SelectionContainer
    val runRequest = pendingRunRequest
    if (runRequest != null) {
        Dialog(onDismissRequest = { pendingRunRequest = null }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).width(360.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(text = stringResource("code.run.dialog.title"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource("code.run.dialog.message"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        secondaryButton(onClick = {
                            pendingRunRequest = null
                            EventBus.post(RunCodeEvent(code = runRequest.first, language = runRequest.second, couldExecute = false))
                        }) {
                            Text(stringResource("code.run.dialog.paste"))
                        }
                        primaryButton(onClick = {
                            pendingRunRequest = null
                            EventBus.post(RunCodeEvent(code = runRequest.first, language = runRequest.second, couldExecute = true))
                        }) {
                            Text(stringResource("code.run.dialog.execute"))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatFileSize(attachment.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onDownload != null) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource("attachment.download.description"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
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
                        textStyle = MaterialTheme.typography.bodyMedium,
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

/** Formats an [Instant] as "HH:mm" in the system default time zone using the current locale. */
private fun formatMessageTime(timestamp: Instant): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(LocalizationManager.getCurrentLocale())
        .withZone(ZoneId.systemDefault())
    return formatter.format(timestamp)
}

/** Formats an [Instant] as a day label relative to today (Today / Yesterday / full date). */
private fun formatDayLabel(timestamp: Instant, locale: Locale, currentDate: LocalDate): String {
    val zone = ZoneId.systemDefault()
    return when (val msgDate = timestamp.atZone(zone).toLocalDate()) {
        currentDate -> LocalizationManager.getString("message.date.today")

        currentDate.minusDays(1) -> LocalizationManager.getString("message.date.yesterday")

        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(msgDate)
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
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}
