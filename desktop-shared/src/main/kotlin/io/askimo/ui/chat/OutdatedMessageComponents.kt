/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles

/**
 * Sealed class to represent different types of message groups
 */
sealed class MessageGroup {
    data class ActiveMessage(val message: ChatMessageDTO) : MessageGroup()
    data class OutdatedBranch(val messages: List<ChatMessageDTO>) : MessageGroup()
}

/**
 * Group messages into active and outdated branches for display
 */
fun groupMessagesWithOutdatedBranches(messages: List<ChatMessageDTO>): List<MessageGroup> {
    val groups = mutableListOf<MessageGroup>()
    val outdatedBuffer = mutableListOf<ChatMessageDTO>()

    messages.forEach { message ->
        if (message.isOutdated) {
            outdatedBuffer.add(message)
        } else {
            // If we have outdated messages buffered, create an outdated group
            if (outdatedBuffer.isNotEmpty()) {
                groups.add(MessageGroup.OutdatedBranch(outdatedBuffer.toList()))
                outdatedBuffer.clear()
            }
            // Add active message
            groups.add(MessageGroup.ActiveMessage(message))
        }
    }

    // Handle remaining outdated messages at the end
    if (outdatedBuffer.isNotEmpty()) {
        groups.add(MessageGroup.OutdatedBranch(outdatedBuffer.toList()))
    }

    return groups
}

/**
 * Component to display a collapsible branch of outdated messages
 */
@Composable
fun outdatedBranchComponent(
    messages: List<ChatMessageDTO>,
    modifier: Modifier = Modifier,
    userAvatarPainter: BitmapPainter? = null,
    aiAvatarPainter: BitmapPainter? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
    ) {
        // Collapsible header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardDefaults.shape)
                .clickable { isExpanded = !isExpanded }
                .pointerHoverIcon(PointerIcon.Hand),
            colors = CardDefaults.cardColors(
                containerColor = AppColors.surfaceColor(AppColors.Elevation.RAISED),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (isExpanded) {
                        stringResource("outdated.collapse")
                    } else {
                        stringResource("outdated.expand")
                    },
                    tint = AppColors.secondaryIconColor(),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource("outdated.branch.header", messages.size),
                    style = AppTextStyles.caption,
                    color = AppColors.secondaryIconColor(),
                    fontStyle = FontStyle.Italic,
                )
            }
        }

        // Expandable content
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp)
                    .background(
                        AppColors.surfaceColor(AppColors.Elevation.RECESSED),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(8.dp),
            ) {
                messages.forEach { message ->
                    outdatedMessageItem(
                        message = message,
                        userAvatarPainter = userAvatarPainter,
                        aiAvatarPainter = aiAvatarPainter,
                    )
                }
            }
        }
    }
}

/**
 * Component to display a single outdated message
 * Reuses messageBubble from MessageComponents for consistent rendering
 * with a gray overlay to create a faded/outdated appearance
 */
@Composable
fun outdatedMessageItem(
    message: ChatMessageDTO,
    userAvatarPainter: BitmapPainter? = null,
    aiAvatarPainter: BitmapPainter? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        messageBubble(
            message = message,
            isOutdatedMessage = true,
            userAvatarPainter = userAvatarPainter,
            aiAvatarPainter = aiAvatarPainter,
            // Disable interactive features for outdated messages
            onMessageClick = null,
            onEditMessage = null,
            onDownloadAttachment = null,
            onRetryMessage = null,
        )

        // Gray overlay on top to create faded appearance
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(AppColors.outdatedMessageOverlayColor()),
        )
    }
}
