/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

@Composable
fun errorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    linkText: String? = null,
    linkUrl: String? = null,
    details: String? = null,
) {
    val linkColor = MaterialTheme.colorScheme.onSurface
    var showDetails by remember { mutableStateOf(false) }
    var messageExpanded by remember { mutableStateOf(false) }

    // Collapse long messages at 4 lines; user can expand to see the full text
    val messageCollapsedMaxLines = 4
    val isMessageLong = message.lines().size > messageCollapsedMaxLines ||
        message.length > 300

    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(800.dp),
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = title,
                    style = AppTextStyles.pageTitle,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AppTextStyles.secondaryContent,
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                modifier = Modifier.padding(vertical = Spacing.small),
            ) {
                if (linkText != null && linkUrl != null) {
                    val annotatedString = buildAnnotatedString {
                        append(message)
                        append("\n\n")

                        withLink(
                            LinkAnnotation.Url(
                                url = linkUrl,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            append(linkText)
                        }
                    }

                    Text(
                        text = annotatedString,
                        style = AppTextStyles.body,
                        maxLines = if (messageExpanded || !isMessageLong) Int.MAX_VALUE else messageCollapsedMaxLines,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (messageExpanded) {
                                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                },
                            )
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                } else {
                    Text(
                        text = message,
                        style = AppTextStyles.body,
                        maxLines = if (messageExpanded || !isMessageLong) Int.MAX_VALUE else messageCollapsedMaxLines,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (messageExpanded) {
                                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }

                // Show more / Show less toggle for long messages
                if (isMessageLong) {
                    Text(
                        text = if (messageExpanded) stringResource("action.show.less") else stringResource("action.show.more"),
                        style = AppTextStyles.fieldLabel,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { messageExpanded = !messageExpanded }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(top = Spacing.extraSmall),
                    )
                }

                // Collapsible details section showing the cause message
                if (details != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDetails = !showDetails }
                            .padding(top = Spacing.extraSmall),
                    ) {
                        Icon(
                            imageVector = if (showDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = AppTextStyles.secondaryContent,
                        )
                        Text(
                            text = if (showDetails) "Hide details" else "Show details",
                            style = AppTextStyles.fieldLabel,
                        )
                    }
                    if (showDetails) {
                        Text(
                            text = details,
                            style = AppTextStyles.codeSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.small),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                primaryButton(onClick = onDismiss) {
                    Text(stringResource("action.ok"))
                }
            }
        },
    )
}
