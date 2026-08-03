/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.bookmarks

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.service.BookmarkGroup
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.markdownText
import java.util.Locale

/**
 * Full content-area view that lists all bookmarked messages across every session,
 * grouped by conversation. Newest-activity conversation first.
 *
 * @param viewModel     Provides [BookmarksViewModel.groups], loading, and error state.
 * @param onNavigateToSession  Called with a sessionId when the user wants to jump to a conversation.
 * @param modifier      Outer modifier.
 */
@Composable
fun bookmarksView(
    viewModel: BookmarksViewModel,
    onNavigateToSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 8.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 36.dp, top = 24.dp, bottom = 24.dp),
            ) {
                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = stringResource("bookmarks.title"),
                            style = AppTextStyles.pageTitle,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.large))

                // ── Body ──────────────────────────────────────────────────────
                when {
                    viewModel.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppComponents.loadingSpinner()
                        }
                    }

                    viewModel.errorMessage != null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                Text(
                                    text = viewModel.errorMessage ?: "",
                                    style = AppTextStyles.body,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = viewModel::load) {
                                    Text(stringResource("action.retry"))
                                }
                            }
                        }
                    }

                    viewModel.groups.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(300.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                                Text(
                                    text = stringResource("bookmarks.empty.title"),
                                    style = AppTextStyles.sectionTitle,
                                )
                                Text(
                                    text = stringResource("bookmarks.empty.hint"),
                                    style = AppTextStyles.bodySecondary,
                                )
                            }
                        }
                    }

                    else -> {
                        bookmarkGroupList(
                            groups = viewModel.groups,
                            onNavigateToSession = onNavigateToSession,
                            onRemoveBookmark = viewModel::removeBookmark,
                        )
                    }
                }
            }
        }

        // Scrollbar
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

// ── Group list ────────────────────────────────────────────────────────────────

@Composable
private fun bookmarkGroupList(
    groups: List<BookmarkGroup>,
    onNavigateToSession: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        groups.forEach { group ->
            bookmarkGroupCard(
                group = group,
                onNavigateToSession = onNavigateToSession,
                onRemoveBookmark = onRemoveBookmark,
            )
        }
    }
}

@Composable
private fun bookmarkGroupCard(
    group: BookmarkGroup,
    onNavigateToSession: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppComponents.sidebarSurfaceColor(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            // ── Session header ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToSession(group.session.id) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = Spacing.large, vertical = Spacing.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.session.title.ifBlank { stringResource("bookmarks.session.untitled") },
                    style = AppTextStyles.itemTitle,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource("bookmarks.message.count", group.messages.size),
                    style = AppTextStyles.hint,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── Bookmarked messages ───────────────────────────────────────
            group.messages.forEach { message ->
                bookmarkMessageRow(
                    content = message.content,
                    timestamp = message.timestamp,
                    isUser = message.isUser,
                    onJumpToSession = { onNavigateToSession(group.session.id) },
                    onRemove = { message.id?.let { onRemoveBookmark(it) } },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Spacing.large),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

@Composable
private fun bookmarkMessageRow(
    content: String,
    timestamp: java.time.Instant?,
    isUser: Boolean,
    onJumpToSession: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJumpToSession)
            .pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)
            .padding(horizontal = Spacing.large, vertical = Spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        // Bookmark icon — always visible; hover switches to BookmarkBorder; click removes
        val bookmarkHoverSource = remember { MutableInteractionSource() }
        val isBookmarkHovered by bookmarkHoverSource.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .hoverable(bookmarkHoverSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isBookmarkHovered) Icons.Default.BookmarkBorder else Icons.Default.Bookmark,
                contentDescription = stringResource("message.bookmark.remove"),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (isBookmarkHovered) 1f else 0.7f),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // Role label
            Text(
                text = if (isUser) stringResource("bookmarks.role.user") else stringResource("bookmarks.role.ai"),
                style = AppTextStyles.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Message preview — rendered as markdown
            markdownText(
                markdown = content.take(600),
                modifier = Modifier.fillMaxWidth(),
            )
            timestamp?.let { ts ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = TimeUtil.formatFullDateTime(ts, Locale.getDefault()),
                    style = AppTextStyles.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}
