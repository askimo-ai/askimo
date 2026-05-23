/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.askimo.core.db.DatabaseManager
import io.askimo.core.search.DateFilter
import io.askimo.core.search.SearchResult
import io.askimo.core.search.SessionSearchService
import io.askimo.core.search.SortBy
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Toolkit
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Global Search Dialog for searching across all chat sessions
 *
 * @param onDismiss Called when the dialog is dismissed
 * @param onNavigateToMessage Called when user clicks on a search result (sessionId, messageId)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun globalSearchDialog(
    onDismiss: () -> Unit,
    onNavigateToMessage: (sessionId: String, messageId: String) -> Unit = { _, _ -> },
) {
    var searchQuery by remember { mutableStateOf("") }
    var dateFilterExpanded by remember { mutableStateOf(false) }
    var sortByExpanded by remember { mutableStateOf(false) }

    // Initialize localized strings
    val allTime = stringResource("global.search.date.all.time")
    val today = stringResource("global.search.date.today")
    val last7Days = stringResource("global.search.date.last.7.days")
    val last30Days = stringResource("global.search.date.last.30.days")
    val last3Months = stringResource("global.search.date.last.3.months")
    val lastYear = stringResource("global.search.date.last.year")

    val relevance = stringResource("global.search.sort.relevance")
    val dateNewest = stringResource("global.search.sort.date.newest")
    val dateOldest = stringResource("global.search.sort.date.oldest")

    var selectedDateFilter by remember { mutableStateOf(allTime) }
    var selectedSortBy by remember { mutableStateOf(dateNewest) }

    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Calculate responsive dialog size based on screen dimensions
    val defaultSize = remember {
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val width = (screenSize.width * 0.6).toInt().coerceIn(700, 1200)
        val height = (screenSize.height * 0.55).toInt().coerceIn(500, 800)
        DpSize(width.dp, height.dp)
    }

    // Dialog state for resizable window
    val dialogState = rememberDialogState(
        size = defaultSize,
    )

    // Auto-focus the search field when dialog opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Initialize search service
    val searchService = remember {
        val dbManager = DatabaseManager.getInstance()
        SessionSearchService(
            sessionRepository = dbManager.getChatSessionRepository(),
            messageRepository = dbManager.getChatMessageRepository(),
        )
    }

    val dateFilterOptions = listOf(allTime, today, last7Days, last30Days, last3Months, lastYear)
    val sortByOptions = listOf(relevance, dateNewest, dateOldest)

    // Map UI strings to enum values
    fun mapDateFilter(uiString: String): DateFilter = when (uiString) {
        today -> DateFilter.TODAY
        last7Days -> DateFilter.LAST_7_DAYS
        last30Days -> DateFilter.LAST_30_DAYS
        last3Months -> DateFilter.LAST_3_MONTHS
        lastYear -> DateFilter.LAST_YEAR
        else -> DateFilter.ALL_TIME
    }

    fun mapSortBy(uiString: String): SortBy = when (uiString) {
        dateNewest -> SortBy.DATE_DESC
        dateOldest -> SortBy.DATE_ASC
        else -> SortBy.RELEVANCE
    }

    fun performSearch() {
        if (searchQuery.isBlank()) return

        isSearching = true
        hasSearched = true

        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    searchService.searchSessions(
                        query = searchQuery,
                        dateFilter = mapDateFilter(selectedDateFilter),
                        projectId = null, // TODO: Map project filter when implemented
                        sortBy = mapSortBy(selectedSortBy),
                        limit = 100,
                    )
                }
                searchResults = results
            } catch (e: Exception) {
                e.printStackTrace()
                searchResults = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        state = dialogState,
        resizable = true,
        title = stringResource("global.search.title"),
    ) {
        // Set dynamic minimum and maximum window size based on screen dimensions
        val screenSize = Toolkit.getDefaultToolkit().screenSize

        // Minimum: 50% of screen width, 40% of screen height (but at least 600x400)
        val minWidth = maxOf(600, (screenSize.width * 0.5).toInt())
        val minHeight = maxOf(400, (screenSize.height * 0.4).toInt())

        // Maximum: 90% of screen width, 85% of screen height
        val maxWidth = (screenSize.width * 0.9).toInt()
        val maxHeight = (screenSize.height * 0.85).toInt()

        window.minimumSize = Dimension(minWidth, minHeight)
        window.maximumSize = Dimension(maxWidth, maxHeight)

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("global.search.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource("dialog.close"),
                        )
                    }
                }

                // Search Query Input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource("global.search.query")) },
                    placeholder = { Text(stringResource("global.search.query.placeholder")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter && searchQuery.isNotBlank() && !isSearching) {
                                performSearch()
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                // Filters Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Date Filter
                    ExposedDropdownMenuBox(
                        expanded = dateFilterExpanded,
                        onExpandedChange = { dateFilterExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = selectedDateFilter,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource("global.search.filter.date")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dateFilterExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                            colors = AppComponents.outlinedTextFieldColors(),
                        )

                        MaterialTheme(
                            colorScheme = MaterialTheme.colorScheme.copy(
                                surfaceContainer = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            ExposedDropdownMenu(
                                expanded = dateFilterExpanded,
                                onDismissRequest = { dateFilterExpanded = false },
                            ) {
                                dateFilterOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            selectedDateFilter = option
                                            dateFilterExpanded = false
                                        },
                                        colors = AppComponents.menuItemColors(),
                                        modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                                    )
                                }
                            }
                        }
                    }

                    // Sort By
                    ExposedDropdownMenuBox(
                        expanded = sortByExpanded,
                        onExpandedChange = { sortByExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = selectedSortBy,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource("global.search.sort.by")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortByExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                            colors = AppComponents.outlinedTextFieldColors(),
                        )
                        // Override surfaceContainer so the popup background matches the theme surface
                        MaterialTheme(
                            colorScheme = MaterialTheme.colorScheme.copy(
                                surfaceContainer = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            ExposedDropdownMenu(
                                expanded = sortByExpanded,
                                onDismissRequest = { sortByExpanded = false },
                            ) {
                                sortByOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            selectedSortBy = option
                                            sortByExpanded = false
                                        },
                                        colors = AppComponents.menuItemColors(),
                                        modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                                    )
                                }
                            }
                        }
                    }
                }

                // Search Results Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when {
                        isSearching -> {
                            // Loading state
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource("global.search.searching"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        hasSearched && searchResults.isEmpty() -> {
                            // No results state
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource("global.search.no.results"),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource("global.search.no.results.hint"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }

                        searchResults.isNotEmpty() -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = if (searchResults.size == 1) {
                                        stringResource("global.search.results.count", searchResults.size)
                                    } else {
                                        stringResource("global.search.results.count.plural", searchResults.size)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(searchResults) { result ->
                                        searchResultItem(
                                            result = result,
                                            searchQuery = searchQuery,
                                            onClick = {
                                                onNavigateToMessage(result.sessionId, result.messageId)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource("global.search.initial.message"),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource("dialog.cancel"))
                    }

                    primaryButton(
                        onClick = { performSearch() },
                        enabled = searchQuery.isNotBlank() && !isSearching,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource("global.search.button"))
                    }
                }
            }
        }
    }
}

@Composable
private fun searchResultItem(
    result: SearchResult,
    searchQuery: String,
    onClick: () -> Unit,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm") }
    val formattedDate = remember(result.messageTimestamp) {
        result.messageTimestamp
            .atZone(ZoneId.systemDefault())
            .format(dateFormatter)
    }

    // Get message preview with context around the search term
    val messagePreview = remember(result.messageContent, searchQuery) {
        getMessagePreviewWithContext(result.messageContent, searchQuery, 200)
    }

    // Create highlighted text with search terms highlighted
    val highlightedText = remember(messagePreview, searchQuery) {
        highlightSearchTerms(messagePreview, searchQuery)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Session title and timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result.sessionTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Message preview with sender icon and highlighted search terms
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = if (result.isUserMessage) Icons.Default.Person else Icons.Default.SmartToy,
                    contentDescription = if (result.isUserMessage) "User" else "Assistant",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = highlightedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Get a preview of the message content with context around the search term.
 * If the search term is found, shows text around it. Otherwise, shows the start of the message.
 */
private fun getMessagePreviewWithContext(content: String, searchQuery: String, maxLength: Int): String {
    if (content.length <= maxLength) {
        return content
    }

    // Find the search term (case-insensitive)
    val lowerContent = content.lowercase()
    val lowerQuery = searchQuery.lowercase()
    val queryIndex = lowerContent.indexOf(lowerQuery)

    return when {
        queryIndex == -1 -> {
            // Search term not found (shouldn't happen), show start
            content.take(maxLength) + "..."
        }

        queryIndex < 80 -> {
            // Search term near the start, show from beginning
            val endIndex = minOf(content.length, maxLength)
            content.take(endIndex) + if (content.length > maxLength) "..." else ""
        }

        else -> {
            // Search term in the middle/end, show context around it
            val contextBefore = 80
            val contextAfter = maxLength - contextBefore - searchQuery.length

            val startIndex = maxOf(0, queryIndex - contextBefore)
            val endIndex = minOf(content.length, queryIndex + searchQuery.length + contextAfter)

            val prefix = if (startIndex > 0) "..." else ""
            val suffix = if (endIndex < content.length) "..." else ""

            prefix + content.substring(startIndex, endIndex) + suffix
        }
    }
}

/**
 * Highlight all occurrences of the search term in the text with a yellow background.
 */
private fun highlightSearchTerms(text: String, searchQuery: String): AnnotatedString {
    if (searchQuery.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        val lowerText = text.lowercase()
        val lowerQuery = searchQuery.lowercase()
        var lastIndex = 0

        while (lastIndex < text.length) {
            val index = lowerText.indexOf(lowerQuery, lastIndex)
            if (index == -1) {
                // No more matches, append remaining text
                append(text.substring(lastIndex))
                break
            }

            // Append text before the match
            append(text.substring(lastIndex, index))

            // Append the match with highlighting
            withStyle(
                SpanStyle(
                    background = Color(0xFFFFEB3B), // Yellow highlight
                    fontWeight = FontWeight.Bold,
                    color = Color.Black, // Ensure text is readable on yellow
                ),
            ) {
                append(text.substring(index, index + searchQuery.length))
            }

            lastIndex = index + searchQuery.length
        }
    }
}
