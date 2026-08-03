/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

/**
 * Displays an inline error message with consistent styling.
 * Used in dialogs and forms to show validation errors or operation failures.
 *
 * @param errorMessage The error message to display. If null, nothing is rendered.
 * @param modifier The modifier to be applied to the error container
 */
@Composable
fun inlineErrorMessage(
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    if (errorMessage != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = AppTextStyles.caption,
                modifier = Modifier.padding(Spacing.medium),
            )
        }
    }
}

/**
 * Displays an inline success message with consistent styling.
 * Used in dialogs and forms to confirm a successful operation.
 *
 * @param message The success message to display. If null, nothing is rendered.
 * @param modifier The modifier to be applied to the container
 */
@Composable
fun inlineSuccessMessage(
    message: String?,
    modifier: Modifier = Modifier,
) {
    if (message != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = AppTextStyles.caption,
                modifier = Modifier.padding(Spacing.medium),
            )
        }
    }
}
