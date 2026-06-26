/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing

private val InlineControlsHeight = 44.dp

/**
 * Neutral input scaffold used by the plan and agent text fields.
 *
 * - When [inlineControls] is **null** — renders a simple flat row: text field + send button.
 * - When [inlineControls] is **provided** — renders a two-section bordered container identical
 *   to the one used in [io.askimo.ui.chat.chatInputField]:
 *   ```
 *   ┌──────────────────────────────────────┐
 *   │  Text area  (scrollable)             │
 *   ├──────────────────────────────────────┤
 *   │  <spacer>  [inlineControls]          │
 *   └──────────────────────────────────────┘  [Send]
 *   ```
 */
@Composable
internal fun actionInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    error: String? = null,
    sendContentDescription: String = "Send",
    /** When non-null, activates the two-section bordered layout and renders this content
     *  right-aligned inside the bottom controls strip. */
    inlineControls: (@Composable RowScope.() -> Unit)? = null,
) {
    val canSend = enabled && !isLoading && value.text.isNotBlank()

    val keyModifier = Modifier.onPreviewKeyEvent { keyEvent ->
        when (KeyMapManager.handleKeyEvent(keyEvent)) {
            KeyMapManager.AppShortcut.NEW_LINE -> {
                val cursor = value.selection.start
                val newText = value.text.substring(0, cursor) + "\n" + value.text.substring(cursor)
                onValueChange(TextFieldValue(text = newText, selection = TextRange(cursor + 1)))
                true
            }

            KeyMapManager.AppShortcut.SEND_MESSAGE -> {
                if (canSend) onSend()
                true
            }

            else -> false
        }
    }

    val sendButton: @Composable () -> Unit = {
        IconButton(
            onClick = { if (canSend) onSend() },
            enabled = canSend,
            colors = AppComponents.primaryIconButtonColors(),
            modifier = Modifier
                .size(48.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = sendContentDescription,
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }

    if (inlineControls != null) {
        // ── Two-section layout ────────────────────────────────────────────────
        Row(
            modifier = modifier.fillMaxWidth().then(keyModifier),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val borderColor = when {
                    error != null -> MaterialTheme.colorScheme.error
                    isFocused -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                val borderWidth = if (isFocused || error != null) 2.dp else 1.dp

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
                        .clip(RoundedCornerShape(4.dp)),
                ) {
                    // Text area
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = if (placeholder.isNotEmpty()) {
                            { Text(placeholder) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = minLines,
                        maxLines = maxLines,
                        enabled = enabled,
                        isError = error != null,
                        interactionSource = interactionSource,
                        // Outer container owns the border; keep the field's own border invisible.
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            errorBorderColor = Color.Transparent,
                            focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )

                    // Controls strip — items provided by caller are pushed to the right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(InlineControlsHeight)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        inlineControls()
                    }
                }

                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.small))
            sendButton()
        }
    } else {
        // ── Simple flat layout ────────────────────────────────────────────────
        Row(
            modifier = modifier.fillMaxWidth().then(keyModifier),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = if (placeholder.isNotEmpty()) {
                    { Text(placeholder) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
                minLines = minLines,
                maxLines = maxLines,
                enabled = enabled,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                colors = AppComponents.outlinedTextFieldColors(),
            )

            sendButton()
        }
    }
}
