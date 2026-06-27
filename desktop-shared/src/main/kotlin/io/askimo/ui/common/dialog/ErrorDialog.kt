/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
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

    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
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
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                } else {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (showDetails) "Hide details" else "Show details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showDetails) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.small),
                        )
                    }
                }
            }
        },
        confirmButton = {
            primaryButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource("action.ok"))
            }
        },
    )
}
