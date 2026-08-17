/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import io.askimo.core.service.StatsService
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pre-defined feedback categories shown in [feedbackPromptDialog]. */
enum class FeedbackReason(val emoji: String, val i18nKey: String) {
    INACCURATE("😕", "feedback.reason.inaccurate"),
    SLOW("🐌", "feedback.reason.slow"),
    MISSING_FEATURE("🧩", "feedback.reason.missing.feature"),
    BROKEN("🐛", "feedback.reason.broken"),
    HARD_TO_USE("😵", "feedback.reason.hard.to.use"),
    INTEGRATION("🔌", "feedback.reason.integration"),
    PRIVACY("🔒", "feedback.reason.privacy"),
    OTHER("🤔", "feedback.reason.other"),
}

/**
 * Happiness gate shown before [starPromptDialog].
 *
 * Tracks sentiment via [Analytics.track] — respects the user's analytics opt-in.
 * - Happy   → [onHappy]   caller shows the star/share prompt
 * - Neutral → [onNeutral] caller shows [feedbackPromptDialog]
 *             `USER_SENTIMENT_NEUTRAL` is NOT fired here — deferred to [feedbackPromptDialog]
 *             and only fired when the user submits with a comment.
 * - Unhappy → [onUnhappy] caller shows [feedbackPromptDialog] in unhappy path mode.
 *             `USER_SENTIMENT_UNHAPPY` is NOT fired here — deferred to [feedbackPromptDialog]
 *             and only fired when the user submits with a comment.
 */
@Composable
fun happinessGateDialog(
    onHappy: () -> Unit,
    onNeutral: () -> Unit,
    onUnhappy: () -> Unit,
) {
    AppComponents.scaffoldDialog(
        onDismissRequest = {},
        width = 480.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "😊",
                style = AppTextStyles.emptyStateEmoji,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource("happiness.gate.title"),
                    style = AppTextStyles.sectionTitle,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource("happiness.gate.subtitle"),
                    style = AppTextStyles.bodySecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                sentimentButton(
                    label = stringResource("happiness.gate.happy"),
                    onClick = {
                        Analytics.track(AnalyticsEvent.USER_SENTIMENT_HAPPY)
                        onHappy()
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                sentimentButton(
                    label = stringResource("happiness.gate.neutral"),
                    onClick = {
                        onNeutral()
                    },
                )
                sentimentButton(
                    label = stringResource("happiness.gate.unhappy"),
                    onClick = {
                        onUnhappy()
                    },
                )
            }
        }
    }
}

@Composable
private fun sentimentButton(
    label: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            style = AppTextStyles.groupTitle,
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Inline feedback dialog shown after neutral/unhappy sentiment, or opened directly from the Help menu.
 *
 * Lets the user pick one or more pre-defined [FeedbackReason]s and optionally add a comment.
 * No browser is opened — structured data is surfaced via [onSubmit].
 *
 * - Send Feedback → [onSubmit] receives selected reasons + comment + email; shows a thank-you screen.
 * - Close (after thank-you) → [onClose] — caller permanently dismisses.
 * - Skip → shows a reminder screen when [showReminderOnSkip] is true (automatic flow),
 *   or calls [onSnooze] immediately when false (opened from menu — user already knows where to find it).
 * - Got it (after reminder) → [onSnooze] — caller snoozes so user can return later.
 */
@Composable
fun feedbackPromptDialog(
    onSubmit: (reasons: Set<FeedbackReason>, comment: String, email: String) -> Unit,
    onClose: () -> Unit,
    onSnooze: () -> Unit,
    showReminderOnSkip: Boolean = true,
    /** `"unhappy"`, `"neutral"`, or `null` (menu-opened). Controls copy and analytics gating. */
    pathSentiment: String? = null,
) {
    val isUnhappyPath = pathSentiment == "unhappy"
    val isNeutralPath = pathSentiment == "neutral"
    val isWeakSentimentPath = isUnhappyPath || isNeutralPath
    var selectedReasons by remember { mutableStateOf(emptySet<FeedbackReason>()) }
    var comment by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }

    AppComponents.scaffoldDialog(
        onDismissRequest = {},
        width = 700.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                submitted -> {
                    // ── Thank-you screen ───────────────────────────────────
                    Text(text = "🙏", style = AppTextStyles.emptyStateEmoji)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("feedback.thanks.title"),
                            style = AppTextStyles.sectionTitle,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource("feedback.thanks.message"),
                            style = AppTextStyles.bodySecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Text(text = stringResource("feedback.thanks.close"), style = AppTextStyles.caption)
                        }
                    }
                }

                showReminder -> {
                    // ── Skip reminder screen ───────────────────────────────
                    Text(text = "💡", style = AppTextStyles.emptyStateEmoji)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("feedback.remind.title"),
                            style = AppTextStyles.sectionTitle,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource("feedback.remind.message"),
                            style = AppTextStyles.bodySecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onSnooze, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Text(text = stringResource("feedback.remind.got.it"), style = AppTextStyles.caption)
                        }
                    }
                }

                else -> {
                    // ── Input screen ───────────────────────────────────────
                    Text(text = "💬", style = AppTextStyles.emptyStateEmoji)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource(
                                when {
                                    isUnhappyPath -> "feedback.unhappy.dialog.title"
                                    isNeutralPath -> "feedback.neutral.dialog.title"
                                    else -> "feedback.dialog.title"
                                },
                            ),
                            style = AppTextStyles.sectionTitle,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(
                                when {
                                    isUnhappyPath -> "feedback.unhappy.dialog.subtitle"
                                    isNeutralPath -> "feedback.neutral.dialog.subtitle"
                                    else -> "feedback.dialog.subtitle"
                                },
                            ),
                            style = AppTextStyles.bodySecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    // ── Reason chips (2-column grid) ───────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        FeedbackReason.entries.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                row.forEach { reason ->
                                    feedbackReasonChip(
                                        reason = reason,
                                        selected = reason in selectedReasons,
                                        onToggle = {
                                            selectedReasons = if (reason in selectedReasons) {
                                                selectedReasons - reason
                                            } else {
                                                selectedReasons + reason
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    // ── Comment field — encouraged on weak-sentiment paths, optional otherwise ──
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                text = stringResource(
                                    when {
                                        isUnhappyPath -> "feedback.comment.label.unhappy"
                                        isNeutralPath -> "feedback.comment.label.neutral"
                                        else -> "feedback.comment.label"
                                    },
                                ),
                                style = AppTextStyles.caption,
                            )
                        },
                        placeholder = {
                            Text(
                                text = stringResource(
                                    when {
                                        isUnhappyPath -> "feedback.comment.placeholder.unhappy"
                                        isNeutralPath -> "feedback.comment.placeholder.neutral"
                                        else -> "feedback.comment.placeholder"
                                    },
                                ),
                                style = AppTextStyles.caption,
                            )
                        },
                        supportingText = if (isWeakSentimentPath) {
                            {
                                Text(
                                    text = stringResource("feedback.comment.encourage.hint"),
                                    style = AppTextStyles.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            null
                        },
                        minLines = if (isWeakSentimentPath) 4 else 3,
                        maxLines = 5,
                        shape = MaterialTheme.shapes.medium,
                    )
                    // ── Optional email ─────────────────────────────────────
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(text = stringResource("feedback.email.label"), style = AppTextStyles.caption)
                        },
                        placeholder = {
                            Text(text = stringResource("feedback.email.placeholder"), style = AppTextStyles.caption)
                        },
                        supportingText = {
                            Text(
                                text = stringResource("feedback.email.hint"),
                                style = AppTextStyles.caption,
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                    )
                    // ── Action buttons ─────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { if (showReminderOnSkip) showReminder = true else onSnooze() },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = stringResource("feedback.action.skip"),
                                style = AppTextStyles.caption,
                            )
                        }
                        Button(
                            onClick = {
                                if (comment.isNotBlank()) {
                                    when {
                                        isUnhappyPath -> Analytics.track(AnalyticsEvent.USER_SENTIMENT_UNHAPPY)
                                        isNeutralPath -> Analytics.track(AnalyticsEvent.USER_SENTIMENT_NEUTRAL)
                                    }
                                }
                                onSubmit(selectedReasons, comment.trim(), email.trim())
                                submitted = true
                            },
                            enabled = (selectedReasons.isNotEmpty() || comment.isNotBlank()) &&
                                (FeedbackReason.MISSING_FEATURE !in selectedReasons || comment.isNotBlank()) &&
                                (FeedbackReason.OTHER !in selectedReasons || comment.isNotBlank()),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(text = stringResource("feedback.action.send"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun feedbackReasonChip(
    reason: FeedbackReason,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = reason.emoji, style = AppTextStyles.body)
            Text(
                text = stringResource(reason.i18nKey),
                style = AppTextStyles.fieldLabel,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Dialog prompting users to support the project.
 * Fetches the current star count from the public API and shows social proof when loaded.
 *
 * @param onDismiss       User clicked "Maybe later"
 * @param onStar          User clicked "Star on GitHub"
 * @param onAlreadyStarred User clicked "Already starred ✓"
 */
@Composable
fun starPromptDialog(
    onDismiss: () -> Unit,
    onStar: () -> Unit,
    onAlreadyStarred: () -> Unit,
    showReminderOnMaybeLater: Boolean = true,
) {
    var starCount by remember { mutableStateOf<Int?>(null) }
    var thanked by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            starCount = StatsService.getInstance().getStats()?.stars
        }
    }

    AppComponents.scaffoldDialog(
        onDismissRequest = {},
        width = 700.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (thanked) {
                // ── Thank-you screen ───────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    Text(text = "🙏", style = AppTextStyles.emptyStateEmoji)
                    Text(
                        text = stringResource("star.prompt.thanks.title"),
                        style = AppTextStyles.sectionTitle,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource("star.prompt.thanks.message"),
                        style = AppTextStyles.bodySecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onAlreadyStarred, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text(text = stringResource("star.prompt.thanks.done"), style = AppTextStyles.caption)
                    }
                }
            } else if (showReminder) {
                // ── Maybe-later reminder screen ────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    Text(text = "💡", style = AppTextStyles.emptyStateEmoji)
                    Text(
                        text = stringResource("star.prompt.remind.title"),
                        style = AppTextStyles.sectionTitle,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource("star.prompt.remind.message"),
                        style = AppTextStyles.bodySecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text(text = stringResource("star.prompt.remind.got.it"), style = AppTextStyles.caption)
                    }
                }
            } else {
                // ── Default screen ─────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(
                        text = stringResource("star.prompt.title"),
                        style = AppTextStyles.sectionTitle,
                    )
                    Text(
                        text = stringResource("star.prompt.message"),
                        style = AppTextStyles.bodySecondary,
                    )
                    val count = starCount
                    if (count != null) {
                        Text(
                            text = stringResource("star.prompt.social.proof", count),
                            style = AppTextStyles.fieldLabel,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    supportActionCard(
                        icon = Icons.Default.Star,
                        iconTint = Color(0xFFFFC107),
                        label = stringResource("star.prompt.star.button"),
                        description = stringResource("star.prompt.star.description"),
                        onClick = {
                            onStar()
                            thanked = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                    shareActionCard(
                        onShared = { thanked = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onAlreadyStarred, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text(
                            text = stringResource("star.prompt.already.starred"),
                            style = AppTextStyles.caption,
                        )
                    }
                    TextButton(
                        onClick = { if (showReminderOnMaybeLater) showReminder = true else onDismiss() },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(
                            text = stringResource("star.prompt.maybe.later"),
                            style = AppTextStyles.caption,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun shareActionCard(
    onShared: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .hoverable(interactionSource)
                .clickable { showMenu = true }
                .pointerHoverIcon(PointerIcon.Hand),
            shape = MaterialTheme.shapes.medium,
            color = if (isHovered) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isHovered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color(0xFF1DA1F2),
                )
                Text(
                    text = stringResource("star.prompt.share.button"),
                    style = AppTextStyles.groupTitle,
                    color = if (isHovered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource("star.prompt.share.description"),
                    style = AppTextStyles.caption,
                    color = if (isHovered) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    },
                )
            }
        }

        AppComponents.dropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            ShareTarget.entries.forEach { target ->
                DropdownMenuItem(
                    text = { Text(ShareUtils.labelFor(target)) },
                    onClick = {
                        showMenu = false
                        ShareUtils.share(target)
                        onShared()
                    },
                    colors = AppComponents.menuItemColors(),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

@Composable
private fun supportActionCard(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        shape = MaterialTheme.shapes.medium,
        color = if (isHovered) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isHovered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = iconTint,
            )
            Text(
                text = label,
                style = AppTextStyles.groupTitle,
                color = if (isHovered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = AppTextStyles.caption,
                color = if (isHovered) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                },
            )
        }
    }
}
