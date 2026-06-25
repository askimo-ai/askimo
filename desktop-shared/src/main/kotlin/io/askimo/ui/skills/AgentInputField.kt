/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.askimo.ui.common.components.ActionInputField

/**
 * Agent-specific input field.
 *
 * This stays intentionally minimal so agent workflows can evolve independently from plan inputs.
 */
@Composable
fun AgentInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    error: String? = null,
    sendContentDescription: String = "Send",
) {
    val tfv = TextFieldValue(text = value, selection = TextRange(value.length))
    AgentInputField(
        value = tfv,
        onValueChange = { onValueChange(it.text) },
        onSend = onSend,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        isLoading = isLoading,
        minLines = minLines,
        maxLines = maxLines,
        error = error,
        sendContentDescription = sendContentDescription,
    )
}

/**
 * Overload that works with [TextFieldValue] directly.
 */
@Composable
fun AgentInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    error: String? = null,
    sendContentDescription: String = "Send",
) {
    ActionInputField(
        value = value,
        onValueChange = onValueChange,
        onSend = onSend,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        isLoading = isLoading,
        minLines = minLines,
        maxLines = maxLines,
        error = error,
        sendContentDescription = sendContentDescription,
    )
}

