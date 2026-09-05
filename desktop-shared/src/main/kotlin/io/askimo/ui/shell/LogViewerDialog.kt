/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.askimo.core.logging.LoggingService
import io.askimo.core.logging.currentFileLogger
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val log = currentFileLogger()

/**
 * Dialog for viewing application log files.
 * Displays the current log file content with options to refresh and copy.
 */
@Composable
fun logViewerDialog(
    onDismiss: () -> Unit,
) {
    var logContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isCopied by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Load log file on first display
    LaunchedEffect(Unit) {
        loadLogContent(
            onSuccess = { content ->
                logContent = content
                isLoading = false
            },
            onError = { error ->
                errorMessage = error
                isLoading = false
            },
        )
    }

    // Auto-scroll to bottom when content loads
    LaunchedEffect(logContent) {
        if (logContent.isNotEmpty() && !isLoading) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val dialogWidth = maxWidth * 0.95f
        val dialogHeight = maxHeight * 0.90f

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Semi-transparent background overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.scrimColor(alpha = 0.32f))
                    .pointerInput(Unit) {
                        detectTapGestures { onDismiss() }
                    },
            )

            // Dialog content - responsive to window size
            Card(
                modifier = Modifier
                    .size(width = dialogWidth, height = dialogHeight)
                    .pointerInput(Unit) {
                        // Prevent clicks from passing through to background
                        detectTapGestures { /* consume */ }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.extraLarge),
                ) {
                    // Header
                    Text(
                        text = stringResource("settings.log.viewer.dialog.title"),
                        style = AppTextStyles.pageTitle,
                    )

                    Spacer(modifier = Modifier.height(Spacing.small))

                    // Log file path
                    LoggingService.getLogFilePath()?.let { logPath ->
                        Text(
                            text = logPath.toString(),
                            style = AppTextStyles.caption,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.large))

                    // Action buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        // Refresh button
                        secondaryButton(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    loadLogContent(
                                        onSuccess = { content ->
                                            logContent = content
                                            isLoading = false
                                        },
                                        onError = { error ->
                                            errorMessage = error
                                            isLoading = false
                                        },
                                    )
                                }
                            },
                            enabled = !isLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(Spacing.small))
                            Text(stringResource("settings.log.viewer.refresh"))
                        }

                        // Copy to clipboard button
                        secondaryButton(
                            onClick = {
                                copyToClipboard(logContent)
                                isCopied = true
                                // Reset copied state after 2 seconds
                                scope.launch {
                                    delay(2000)
                                    isCopied = false
                                }
                            },
                            enabled = logContent.isNotEmpty() && !isLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(Spacing.small))
                            Text(
                                if (isCopied) {
                                    stringResource("settings.log.viewer.copied")
                                } else {
                                    stringResource("settings.log.viewer.copy")
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.large))

                    // Log content display
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        when {
                            isLoading -> {
                                // Loading state
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.extraLarge),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = stringResource("settings.log.viewer.loading"),
                                        style = AppTextStyles.bodySecondary,
                                    )
                                }
                            }

                            errorMessage != null -> {
                                // Error state
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.extraLarge),
                                ) {
                                    Text(
                                        text = stringResource("settings.log.viewer.error"),
                                        style = AppTextStyles.sectionTitle,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(modifier = Modifier.height(Spacing.small))
                                    Text(
                                        text = errorMessage ?: "",
                                        style = AppTextStyles.caption,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }

                            logContent.isEmpty() -> {
                                // Empty state
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.extraLarge),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = stringResource("settings.log.viewer.empty"),
                                        style = AppTextStyles.bodySecondary,
                                    )
                                }
                            }

                            else -> {
                                // Content display
                                SelectionContainer {
                                    Text(
                                        text = logContent,
                                        modifier = Modifier
                                            .padding(Spacing.medium)
                                            .verticalScroll(scrollState),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        style = AppTextStyles.caption,
                                        lineHeight = 16.sp,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.large))

                    // Close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        primaryButton(
                            onClick = onDismiss,
                        ) {
                            Text(stringResource("action.close"))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loads log file content asynchronously.
 */
private suspend fun loadLogContent(
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
) {
    withContext(Dispatchers.IO) {
        try {
            val logFile = LoggingService.getLogFilePath()?.toFile()
            if (logFile == null) {
                onError("Log file not found")
                return@withContext
            }

            if (!logFile.exists()) {
                onError("Log file does not exist: ${logFile.absolutePath}")
                return@withContext
            }

            // Read last 10000 lines (approximately 1MB of text)
            val lines = logFile.readLines()
            val content = if (lines.size > 10000) {
                "... (showing last 10,000 lines)\n\n" +
                    lines.takeLast(10000).joinToString("\n")
            } else {
                lines.joinToString("\n")
            }

            onSuccess(content)
        } catch (e: Exception) {
            onError("Failed to read log file: ${e.message}")
        }
    }
}

/**
 * Copies text to system clipboard.
 */
private fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
    } catch (e: Exception) {
        log.error("Failed to copy to clipboard", e)
    }
}
