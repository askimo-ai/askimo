/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.askimo.core.logging.logger
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private object UrlInputDialog
private val log = logger<UrlInputDialog>()

/**
 * Dialog for entering and validating a URL.
 * Validates the URL format and checks if it's accessible via HTTP GET.
 */
@Composable
fun urlInputDialog(
    onDismiss: () -> Unit,
    onUrlAdded: (url: String) -> Unit,
) {
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var isValidating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the text field when dialog opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Validate URL format and accessibility
    suspend fun validateUrlAccessibility(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uri = URI(url)

            // Check URL format
            if (uri.scheme !in listOf("http", "https")) {
                return@withContext Result.failure(Exception("URL must start with http:// or https://"))
            }

            if (uri.host == null) {
                return@withContext Result.failure(Exception("Invalid URL: missing host"))
            }

            // Try to access the URL with GET request
            val urlConnection = uri.toURL().openConnection() as HttpURLConnection
            urlConnection.requestMethod = "GET"
            urlConnection.connectTimeout = 5000
            urlConnection.readTimeout = 5000
            urlConnection.instanceFollowRedirects = true

            try {
                val responseCode = urlConnection.responseCode
                if (responseCode in 200..399) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("URL returned HTTP $responseCode"))
                }
            } finally {
                urlConnection.disconnect()
            }
        } catch (e: Exception) {
            log.debug("URL validation failed for $url", e)
            Result.failure(Exception("Cannot access URL: ${e.message ?: "Unknown error"}"))
        }
    }

    fun handleAdd() {
        val url = urlInput.trim()

        if (url.isEmpty()) {
            urlError = "Please enter a URL"
            return
        }

        // Basic format validation first
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            urlError = "URL must start with http:// or https://"
            return
        }

        // Validate accessibility
        isValidating = true
        urlError = null

        scope.launch {
            val result = validateUrlAccessibility(url)
            isValidating = false

            result.fold(
                onSuccess = {
                    onUrlAdded(url)
                    onDismiss()
                },
                onFailure = { error ->
                    urlError = error.message ?: "Invalid URL"
                },
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(500.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Title
                Text(
                    text = stringResource("project.dialog.url.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Description
                Text(
                    text = stringResource("project.dialog.url.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // URL Input Field
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        urlError = null
                    },
                    label = { Text(stringResource("project.dialog.url.label")) },
                    placeholder = { Text("https://example.com/docs") },
                    isError = urlError != null,
                    supportingText = urlError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    enabled = !isValidating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = AppComponents.outlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleAdd() }),
                )

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = onDismiss,
                        enabled = !isValidating,
                    ) {
                        Text(stringResource("project.dialog.url.button.cancel"))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    primaryButton(
                        onClick = { handleAdd() },
                        enabled = !isValidating,
                    ) {
                        if (isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (isValidating) {
                                stringResource("project.dialog.url.button.validating")
                            } else {
                                stringResource("project.dialog.url.button.add")
                            },
                        )
                    }
                }
            }
        }
    }
}
