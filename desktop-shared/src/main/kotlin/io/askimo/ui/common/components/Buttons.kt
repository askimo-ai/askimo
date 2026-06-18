/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp

/**
 * Primary action button with filled background.
 * Use for main/confirm actions in dialogs and forms.
 *
 * Features:
 * - Filled background with primary accent color
 * - White text for maximum contrast (from theme)
 * - Hand cursor on hover
 * - High visual prominence
 *
 * @param onClick Called when the button is clicked
 * @param modifier The modifier to be applied to the button
 * @param enabled Whether the button is enabled
 * @param content The button content (typically Text)
 */
@Composable
fun primaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        content = content,
    )
}

/**
 * Secondary action button with outlined style.
 * Use for dismiss/cancel actions in dialogs and forms.
 *
 * Features:
 * - Outlined style with subtle background
 * - Hand cursor on hover
 * - Lower visual prominence than PrimaryButton
 *
 * @param onClick Called when the button is clicked
 * @param modifier The modifier to be applied to the button
 * @param enabled Whether the button is enabled
 * @param content The button content (typically Text)
 */
@Composable
fun secondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
        ),
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        content = content,
    )
}

/**
 * Danger button for destructive actions.
 * Use for delete, remove, or other dangerous operations.
 *
 * Features:
 * - Filled background with error/danger color (from theme)
 * - White text for maximum contrast (from theme)
 * - Hand cursor on hover
 * - High visual prominence with warning color
 *
 * @param onClick Called when the button is clicked
 * @param modifier The modifier to be applied to the button
 * @param enabled Whether the button is enabled
 * @param content The button content (typically Text)
 */
@Composable
fun dangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        content = content,
    )
}

/**
 * Link button for navigational actions and external links.
 * Use for hyperlinks, website URLs, and secondary navigation.
 *
 * Features:
 * - Transparent background
 * - Text color matches theme (white in dark mode, black in light mode)
 * - Hand cursor on hover
 * - Lower visual prominence than primary/secondary buttons
 *
 * @param onClick Called when the button is clicked
 * @param modifier The modifier to be applied to the button
 * @param enabled Whether the button is enabled
 * @param content The button content (typically Text with optional Icon)
 */
@Composable
fun linkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        content = content,
    )
}
