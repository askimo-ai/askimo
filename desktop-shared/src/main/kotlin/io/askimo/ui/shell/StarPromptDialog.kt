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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents

/**
 * Happiness gate shown before [starPromptDialog].
 *
 * Tracks sentiment via [Analytics.track] — respects the user's analytics opt-in.
 * - Happy   → [onHappy]   caller shows the star/share prompt
 * - Neutral → [onNeutral] caller dismisses
 * - Unhappy → [onUnhappy] caller opens the contact/feedback page in the browser
 */
@Composable
fun happinessGateDialog(
    onHappy: () -> Unit,
    onNeutral: () -> Unit,
    onUnhappy: () -> Unit,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            modifier = Modifier.width(400.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "😊",
                    style = MaterialTheme.typography.displaySmall,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource("happiness.gate.title"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource("happiness.gate.subtitle"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            Analytics.track(AnalyticsEvent.USER_SENTIMENT_NEUTRAL)
                            onNeutral()
                        },
                    )
                    sentimentButton(
                        label = stringResource("happiness.gate.unhappy"),
                        onClick = {
                            Analytics.track(AnalyticsEvent.USER_SENTIMENT_UNHAPPY)
                            onUnhappy()
                        },
                    )
                }
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
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Shown after neutral/unhappy sentiment — asks if user wants to share feedback.
 * - Yes → [onConfirm] caller opens the contact/feedback page
 * - No  → [onDecline] caller dismisses
 */
@Composable
fun feedbackPromptDialog(
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            modifier = Modifier.width(400.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "💬",
                    style = MaterialTheme.typography.displaySmall,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource("happiness.gate.feedback.title"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource("happiness.gate.feedback.message"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sentimentButton(
                        label = stringResource("happiness.gate.feedback.yes"),
                        onClick = onConfirm,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    sentimentButton(
                        label = stringResource("happiness.gate.feedback.no"),
                        onClick = onDecline,
                    )
                }
            }
        }
    }
}

/**
 * Dialog prompting users to support the project.
 */
@Composable
fun starPromptDialog(
    onDismiss: () -> Unit,
    onStar: () -> Unit,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            modifier = Modifier.width(520.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource("star.prompt.title"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource("star.prompt.message"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Action cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    supportActionCard(
                        icon = Icons.Default.Star,
                        iconTint = Color(0xFFFFC107),
                        label = stringResource("star.prompt.star.button"),
                        description = stringResource("star.prompt.star.description"),
                        onClick = onStar,
                        modifier = Modifier.weight(1f),
                    )
                    // Share card with dropdown
                    shareActionCard(
                        modifier = Modifier.weight(1f),
                    )
                }

                // Maybe Later link
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(
                            text = stringResource("star.prompt.maybe.later"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun shareActionCard(modifier: Modifier = Modifier) {
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
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color(0xFF1DA1F2), // Twitter/share blue
                )
                Text(
                    text = stringResource("star.prompt.share.button"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHovered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource("star.prompt.share.description"),
                    style = MaterialTheme.typography.bodySmall,
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
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = iconTint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isHovered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isHovered) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                },
            )
        }
    }
}
