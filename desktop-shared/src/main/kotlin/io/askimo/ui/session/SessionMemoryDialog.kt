/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.SessionMemory
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.util.JsonUtils
import io.askimo.core.util.JsonUtils.prettyJson
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

private val log = currentFileLogger()

/**
 * Dialog to display session memory information including memory messages and summary.
 *
 * @param sessionId The session ID to load memory for
 * @param onLoadMemory Callback to load session memory from repository
 * @param onDismiss Callback when the dialog is dismissed
 */
@Composable
fun sessionMemoryDialog(
    sessionId: String?,
    onLoadMemory: suspend (String) -> SessionMemory?,
    onDismiss: () -> Unit,
) {
    var sessionMemory by remember { mutableStateOf<SessionMemory?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(sessionId) {
        isLoading = true
        sessionMemory = if (sessionId != null) {
            onLoadMemory(sessionId)
        } else {
            null
        }
        isLoading = false
    }

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 800.dp,
        title = {
            Text(
                text = stringResource("developer.session.memory.title"),
                style = AppTextStyles.pageTitle,
            )
        },
        content = {
            if (isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.surfaceVariantCardColors(),
                ) {
                    Text(
                        text = stringResource("developer.session.memory.loading"),
                        style = AppTextStyles.bodySecondary,
                        modifier = Modifier.padding(Spacing.large),
                    )
                }
            } else if (sessionMemory == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.surfaceVariantCardColors(),
                ) {
                    Text(
                        text = stringResource("developer.session.memory.not.found"),
                        style = AppTextStyles.bodySecondary,
                        modifier = Modifier.padding(Spacing.large),
                    )
                }
            } else {
                val memory = sessionMemory!!

                // Memory Summary Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource("developer.session.memory.summary"),
                            style = AppTextStyles.sectionTitle,
                        )
                        Text(
                            text = stringResource(
                                "developer.session.memory.word.count",
                                countWords(memory.memorySummary ?: ""),
                            ),
                            style = AppTextStyles.caption,
                        )
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        colors = AppComponents.surfaceVariantCardColors(),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val scrollState = rememberScrollState()
                            SelectionContainer {
                                Text(
                                    text = formatJson(memory.memorySummary ?: stringResource("developer.session.memory.empty")),
                                    style = AppTextStyles.bodySecondary,
                                    modifier = Modifier
                                        .padding(Spacing.large)
                                        .fillMaxWidth()
                                        .verticalScroll(scrollState),
                                )
                            }
                            VerticalScrollbar(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = Spacing.extraSmall),
                                adapter = rememberScrollbarAdapter(scrollState),
                                style = AppComponents.scrollbarStyle(),
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Memory Messages Section with Pagination
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    // Parse messages and setup pagination - latest first
                    val messages = remember(memory.memoryMessages) {
                        parseMemoryMessages(memory.memoryMessages).reversed()
                    }

                    val pageSize = 5
                    val totalPages = remember(messages.size) {
                        if (messages.isEmpty()) 1 else (messages.size + pageSize - 1) / pageSize
                    }
                    var currentPage by remember(memory.memoryMessages) { mutableStateOf(1) }

                    val startIndex = (currentPage - 1) * pageSize
                    val endIndex = minOf(startIndex + pageSize, messages.size)
                    val currentMessages = remember(currentPage, messages) {
                        if (messages.isEmpty()) emptyList() else messages.subList(startIndex, endIndex)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource("developer.session.memory.messages"),
                            style = AppTextStyles.sectionTitle,
                        )

                        if (messages.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        "developer.session.memory.word.count",
                                        countWords(memory.memoryMessages),
                                    ),
                                    style = AppTextStyles.caption,
                                )

                                // Pagination controls
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = { currentPage = maxOf(1, currentPage - 1) },
                                        enabled = currentPage > 1,
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = "Previous page",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }

                                    Text(
                                        text = "$currentPage / $totalPages",
                                        style = AppTextStyles.caption,
                                    )

                                    IconButton(
                                        onClick = { currentPage = minOf(totalPages, currentPage + 1) },
                                        enabled = currentPage < totalPages,
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Next page",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(
                                    "developer.session.memory.word.count",
                                    countWords(memory.memoryMessages),
                                ),
                                style = AppTextStyles.caption,
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        colors = AppComponents.surfaceVariantCardColors(),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val scrollState = rememberScrollState()
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .padding(Spacing.large)
                                        .fillMaxWidth()
                                        .verticalScroll(scrollState),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.large),
                                ) {
                                    if (currentMessages.isEmpty()) {
                                        Text(
                                            text = stringResource("developer.session.memory.empty"),
                                            style = AppTextStyles.bodySecondary,
                                        )
                                    } else {
                                        currentMessages.forEach { msg ->
                                            memoryMessageItem(msg)
                                            if (msg != currentMessages.last()) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(vertical = Spacing.small),
                                                    color = MaterialTheme.colorScheme.outlineVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            VerticalScrollbar(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = Spacing.extraSmall),
                                adapter = rememberScrollbarAdapter(scrollState),
                                style = AppComponents.scrollbarStyle(),
                            )
                        }
                    }
                }
            }
        },
        actions = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.close"))
            }
        },
    )
}

/**
 * Render a single memory message in a formatted, readable way.
 */
@Composable
private fun memoryMessageItem(messageJson: String) {
    // Parse JSON outside of composable context
    val parsedMessage = remember(messageJson) {
        try {
            val jsonElement = JsonUtils.json.parseToJsonElement(messageJson)
            val content = jsonElement.jsonObject["content"]?.toString()?.trim('"')?.replace("\\n", "\n") ?: ""
            val type = jsonElement.jsonObject["type"]?.toString()?.trim('"') ?: "unknown"
            val createdAt = jsonElement.jsonObject["createdAt"]?.toString()?.trim('"') ?: ""
            Triple(content, type, createdAt)
        } catch (e: Exception) {
            log.debug("Failed to parse memory message JSON: {}", messageJson, e)
            null
        }
    }

    if (parsedMessage != null) {
        val (content, type, createdAt) = parsedMessage
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = type.replaceFirstChar { it.uppercase() },
                    style = AppTextStyles.fieldLabel,
                    fontWeight = FontWeight.SemiBold,
                    color = when (type) {
                        "user" -> MaterialTheme.colorScheme.tertiary
                        "assistant" -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = createdAt,
                    style = AppTextStyles.hint,
                )
            }
            Text(
                text = content,
                style = AppTextStyles.body,
            )
        }
    } else {
        // Fallback for invalid JSON
        Text(
            text = messageJson,
            style = AppTextStyles.errorText,
        )
    }
}

/**
 * Count words in a text string.
 * Words are separated by whitespace.
 */
private fun countWords(text: String): Int {
    if (text.isBlank()) return 0
    return text.trim().split("\\s+".toRegex()).size
}

/**
 * Parse memory messages string into individual message items.
 * The input is a JSON array of MemoryMessage objects.
 */
private fun parseMemoryMessages(memoryMessages: String): List<String> {
    if (memoryMessages.isBlank()) return emptyList()

    return try {
        val jsonElement = JsonUtils.json.parseToJsonElement(memoryMessages)

        if (jsonElement is JsonArray) {
            jsonElement.map { it.toString() }
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        log.debug("Failed to parse memory messages JSON: {}", memoryMessages, e)
        emptyList()
    }
}

/**
 * Format JSON string with proper indentation for better readability.
 * If the string is not valid JSON, returns it as-is.
 */
private fun formatJson(text: String): String {
    if (text.isBlank()) return text

    return try {
        val jsonElement = JsonUtils.json.parseToJsonElement(text)
        prettyJson.encodeToString(JsonElement.serializer(), jsonElement)
    } catch (e: Exception) {
        log.debug("Text is not valid JSON, returning as-is", e)
        text
    }
}
