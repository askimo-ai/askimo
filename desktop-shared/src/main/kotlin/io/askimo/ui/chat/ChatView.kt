/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.service.ChatDirectiveService
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingInProgressEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.rag.ProjectIndexer
import io.askimo.core.util.TimeUtil.formatDisplay
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.keymap.KeyMapManager.AppShortcut
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.LocalBackgroundActive
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.service.AvatarService
import io.askimo.ui.session.manageDirectivesDialog
import io.askimo.ui.session.newDirectiveDialog
import io.askimo.ui.session.sessionActionsMenu
import io.askimo.ui.session.sessionMemoryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.time.LocalDateTime
import java.util.UUID.randomUUID

private val log = currentFileLogger()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun chatView(
    state: ChatState,
    actions: ChatActions,
    provider: String? = null,
    model: String? = null,
    onJumpToMessage: (String, LocalDateTime) -> Unit = { _, _ -> },
    initialInputText: TextFieldValue = TextFieldValue(""),
    initialAttachments: List<FileAttachmentDTO> = emptyList(),
    initialEditingMessage: ChatMessageDTO? = null,
    onStateChange: (TextFieldValue, List<FileAttachmentDTO>, ChatMessageDTO?) -> Unit = { _, _, _ -> },
    sessionId: String? = null,
    onRenameSession: (String) -> Unit = {},
    onExportSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onStarSession: (String, Boolean) -> Unit = { _, _ -> },
    onNavigateToProject: ((String) -> Unit)? = null,
    onNavigateToMcpSettings: (() -> Unit)? = null,
    userAvatarPath: String? = null,
    serverBaseUrl: String? = null,
) {
    // Unpack state for internal use
    val messages = state.messages
    val hasMoreMessages = state.hasMoreMessages
    val isLoadingPrevious = state.isLoadingPrevious
    val prependGeneration = state.prependGeneration
    val isLoading = state.isLoading
    val isThinking = state.isThinking
    val thinkingElapsedSeconds = state.thinkingElapsedSeconds
    val spinnerFrame = state.spinnerFrame
    val errorMessage = state.errorMessage
    val isSearchMode = state.isSearchMode
    val searchQuery = state.searchQuery
    val searchResults = state.searchResults
    val currentSearchResultIndex = state.currentSearchResultIndex
    val isSearching = state.isSearching
    val selectedDirective = state.selectedDirective
    val sessionTitle = state.sessionTitle
    val project = state.project

    // Internal state management for ChatView
    val scope = rememberCoroutineScope()
    var inputText by remember(sessionId, initialInputText) { mutableStateOf(initialInputText) }
    var attachments by remember(sessionId, initialAttachments) { mutableStateOf(initialAttachments) }
    var editingMessage by remember(sessionId, initialEditingMessage) { mutableStateOf(initialEditingMessage) }
    var editingAIMessage by remember(sessionId) { mutableStateOf<ChatMessageDTO?>(null) }
    // Always-current disabled server IDs from ChatInputField
    var currentEnabledServerIds by remember(sessionId) { mutableStateOf(emptySet<String>()) }

    // When there is no project (e.g. user clicked "New Chat"), drop any pending
    // attachments that were added from the project side panel.
    LaunchedEffect(project) {
        if (project == null) attachments = emptyList()
    }

    // Notify parent of state changes
    LaunchedEffect(inputText, attachments, editingMessage) {
        onStateChange(inputText, attachments, editingMessage)
    }

    val directiveService = remember {
        GlobalContext.get().get<ChatDirectiveService>()
    }

    // Load all directives
    var availableDirectives by remember { mutableStateOf<List<ChatDirective>>(emptyList()) }
    var showNewDirectiveDialog by remember { mutableStateOf(false) }

    // Session memory dialog state
    var showSessionMemoryDialog by remember { mutableStateOf(false) }
    var sessionMemorySessionId by remember { mutableStateOf<String?>(null) }

    // Hoisted scroll state — owned here so the whole messages area responds to scroll
    // regardless of mouse position within the chat panel.
    val messagesScrollState = rememberScrollState()

    // RAG indexing status state
    var ragIndexingStatus by remember { mutableStateOf<String?>(null) }
    var ragIndexingPercentage by remember { mutableStateOf<Int?>(null) }

    // Side panel state (RAG sources, MCP, etc.)
    var sidePanelExpanded by remember { mutableStateOf(ApplicationPreferences.getProjectSidePanelExpanded()) }

    // Save panel state when it changes
    LaunchedEffect(sidePanelExpanded) {
        ApplicationPreferences.setProjectSidePanelExpanded(sidePanelExpanded)
    }

    // Check initial RAG status when project changes and subscribe to indexing events
    LaunchedEffect(project?.id) {
        if (project?.id != null && project.knowledgeSources.isNotEmpty()) {
            // Check if project is already indexed
            val projectIndexer = try {
                GlobalContext.get().get<ProjectIndexer>()
            } catch (e: Exception) {
                log.warn("ProjectIndexer not available: ${e.message}", e)
                null
            }

            if (projectIndexer != null) {
                val isIndexed = withContext(Dispatchers.IO) {
                    projectIndexer.isProjectIndexed(project.id)
                }

                if (isIndexed) {
                    ragIndexingStatus = "completed"
                    ragIndexingPercentage = null
                    log.debug("Project ${project.id} is already indexed")
                }
            }

            EventBus.internalEvents.collect { event ->
                val eventProjectId = when (event) {
                    is IndexingStartedEvent -> event.projectId
                    is IndexingInProgressEvent -> event.projectId
                    is IndexingCompletedEvent -> event.projectId
                    is IndexingFailedEvent -> event.projectId
                    else -> null
                }

                if (eventProjectId == project.id) {
                    when (event) {
                        is IndexingStartedEvent -> {
                            ragIndexingStatus = "started"
                            ragIndexingPercentage = null
                        }

                        is IndexingInProgressEvent -> {
                            ragIndexingStatus = "inprogress"
                            ragIndexingPercentage = if (event.totalFiles > 0) {
                                event.filesIndexed * 100 / event.totalFiles
                            } else {
                                0
                            }
                        }

                        is IndexingCompletedEvent -> {
                            ragIndexingStatus = "completed"
                            ragIndexingPercentage = null
                        }

                        is IndexingFailedEvent -> {
                            ragIndexingStatus = "failed"
                            ragIndexingPercentage = null
                        }
                    }
                }
            }
        } else {
            // Reset status when project changes or has no knowledge sources
            ragIndexingStatus = null
            ragIndexingPercentage = null
        }
    }

    LaunchedEffect(Unit) {
        availableDirectives = directiveService.listAllDirectives()
    }

    // Focus requester for search field
    val searchFocusRequester = remember { FocusRequester() }

    // Focus requester for input field
    val inputFocusRequester = remember { FocusRequester() }

    // Focus requester for the main ChatView container
    val chatViewFocusRequester = remember { FocusRequester() }

    // Focus search field when search mode is activated
    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            searchFocusRequester.requestFocus()
        } else {
            chatViewFocusRequester.requestFocus()
        }
    }

    // Focus input field and position cursor at start when entering edit mode
    LaunchedEffect(editingMessage) {
        if (editingMessage != null) {
            inputFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        chatViewFocusRequester.requestFocus()
    }

    // Load avatar painters from AvatarService (cached — decoded once per app lifetime).
    // userAvatarPath is passed in from the caller which already has userProfile loaded,
    // so there is no async race on first run and it re-triggers naturally when the
    // user saves a new avatar (path string changes).
    val avatarService = remember { GlobalContext.get().get<AvatarService>() }
    var aiAvatarPainter by remember { mutableStateOf<BitmapPainter?>(null) }
    var userAvatarPainter by remember { mutableStateOf<BitmapPainter?>(null) }

    LaunchedEffect(Unit) {
        aiAvatarPainter = withContext(Dispatchers.IO) { avatarService.getAiAvatarPainter() }
    }

    LaunchedEffect(userAvatarPath) {
        userAvatarPainter = withContext(Dispatchers.IO) {
            avatarService.getUserAvatarPainter(userAvatarPath, serverBaseUrl)
        }
    }

    // Show new directive dialog
    if (showNewDirectiveDialog) {
        newDirectiveDialog(
            onDismiss = { showNewDirectiveDialog = false },
            onConfirm = { name, content, applyToCurrent ->
                // Create the new directive
                val newDirective = directiveService.createDirective(name, content)

                // Reload directives
                availableDirectives = directiveService.listAllDirectives()

                // Apply to current session if requested
                if (applyToCurrent) {
                    actions.setDirective(newDirective.id)
                }

                showNewDirectiveDialog = false
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (LocalBackgroundActive.current) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .focusRequester(chatViewFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val shortcut = KeyMapManager.handleKeyEvent(keyEvent)

                when (shortcut) {
                    AppShortcut.SEARCH_IN_CHAT -> {
                        if (!isSearchMode) {
                            actions.searchMessages("")
                            true
                        } else {
                            false
                        }
                    }

                    AppShortcut.CLOSE_SEARCH -> {
                        if (isSearchMode) {
                            actions.clearSearch()
                            true
                        } else {
                            false
                        }
                    }

                    AppShortcut.NEXT_SEARCH_RESULT -> {
                        if (isSearchMode && searchResults.isNotEmpty()) {
                            actions.nextSearchResult()
                            true
                        } else {
                            false
                        }
                    }

                    AppShortcut.PREVIOUS_SEARCH_RESULT -> {
                        if (isSearchMode && searchResults.isNotEmpty()) {
                            actions.previousSearchResult()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            },
    ) {
        // Main chat area (left side)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            // Session header with title and directive selector
            if (provider != null && model != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = AppComponents.sidebarSurfaceColor(),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            // Breadcrumb navigation for project
                            if (project != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Clickable project name as breadcrumb
                                    themedTooltip(
                                        text = buildString {
                                            append(project.name)
                                            project.description?.takeIf { it.isNotBlank() }?.let {
                                                append("\n").append(it)
                                            }
                                            if (project.knowledgeSources.isNotEmpty()) {
                                                append("\n").append(project.knowledgeSources.joinToString("\n") { "• ${it.resourceIdentifier}" })
                                            }
                                            append("\nCreated: ${formatDisplay(project.createdAt)}")
                                            append("\nUpdated: ${formatDisplay(project.updatedAt)}")
                                        },
                                    ) {
                                        TextButton(
                                            onClick = {
                                                onNavigateToProject?.invoke(project.id)
                                            },
                                            colors = ButtonDefaults.textButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            ),
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                        ) {
                                            Text(
                                                text = project.name.take(3).uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }

                                    // Chevron separator
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }

                            // RAG indexing status indicator
                            if (project != null && project.knowledgeSources.isNotEmpty()) {
                                val statusText = when (ragIndexingStatus) {
                                    "started" -> "RAG (Started)"
                                    "inprogress" -> ragIndexingPercentage?.let { "RAG (In Progress - $it%)" } ?: "RAG (In Progress)"
                                    "completed" -> "RAG"
                                    "failed" -> "RAG (Failed)"
                                    else -> null
                                }

                                val statusColor = when (ragIndexingStatus) {
                                    "failed" -> MaterialTheme.colorScheme.error
                                    "completed" -> MaterialTheme.colorScheme.onSurface
                                    "inprogress" -> MaterialTheme.colorScheme.tertiary
                                    "started" -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }

                                if (statusText != null) {
                                    themedTooltip(text = statusText) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = "RAG Status",
                                                tint = statusColor,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            if (ragIndexingStatus == "inprogress") {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    color = statusColor,
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Session title
                            themedTooltip(
                                text = sessionTitle,
                            ) {
                                Text(
                                    text = sessionTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .padding(end = 8.dp),
                                )
                            }
                        }

                        // Right side: Directive selector and session actions
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            var showManageDirectivesDialog by remember { mutableStateOf(false) }
                            var directiveDropdownExpanded by remember { mutableStateOf(false) }

                            // Get the selected directive details
                            val selectedDirectiveObj = remember(selectedDirective, availableDirectives) {
                                selectedDirective?.let { id ->
                                    availableDirectives.find { it.id == id }
                                }
                            }

                            Box {
                                themedTooltip(
                                    text = if (selectedDirectiveObj != null) {
                                        "${stringResource("chat.directive")}: ${selectedDirectiveObj.name}\n${selectedDirectiveObj.content}"
                                    } else {
                                        "${stringResource("chat.directive")}: ${stringResource("chat.directive.none")}"
                                    },
                                ) {
                                    TextButton(
                                        onClick = { directiveDropdownExpanded = true },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = if (selectedDirectiveObj != null) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        ),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = stringResource("chat.directive"),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = selectedDirectiveObj?.name?.take(30)?.let {
                                                if (selectedDirectiveObj.name.length > 30) "$it..." else it
                                            } ?: stringResource("chat.directive.none"),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Select directive",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }

                                dropdownMenu(
                                    expanded = directiveDropdownExpanded,
                                    onDismissRequest = { directiveDropdownExpanded = false },
                                    modifier = Modifier.fillMaxWidth(0.3f),
                                ) {
                                    // "None" option to clear directive
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource("chat.directive.none"),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        },
                                        onClick = {
                                            actions.setDirective(null)
                                            directiveDropdownExpanded = false
                                        },
                                        leadingIcon = if (selectedDirective == null) {
                                            {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    )

                                    // Show available directives in a scrollable section
                                    if (availableDirectives.isNotEmpty()) {
                                        HorizontalDivider()

                                        availableDirectives.forEach { directive ->
                                            themedTooltip(
                                                text = "${directive.name}\n${directive.content}",
                                                placement = TooltipPlacement.LEFT,
                                            ) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = directive.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                        )
                                                    },
                                                    onClick = {
                                                        actions.setDirective(directive.id)
                                                        directiveDropdownExpanded = false
                                                    },
                                                    leadingIcon = if (selectedDirective == directive.id) {
                                                        {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                contentDescription = "Selected",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                            )
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                                )
                                            }
                                        }
                                    }

                                    // Action items section
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )

                                    // New Directive action
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Default.Add,
                                                    contentDescription = "New directive",
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Text(
                                                    text = stringResource("chat.directive.new"),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        },
                                        onClick = {
                                            showNewDirectiveDialog = true
                                            directiveDropdownExpanded = false
                                        },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    )

                                    // Manage Directives action
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "Manage directives",
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Text(
                                                    text = stringResource("chat.directive.manage"),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        },
                                        onClick = {
                                            showManageDirectivesDialog = true
                                            directiveDropdownExpanded = false
                                        },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    )

                                    // Learn more link
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Text(
                                                    text = stringResource("chat.directive.learn.more"),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            uriHandler.openUri("https://askimo.chat/docs/desktop/directives/")
                                            directiveDropdownExpanded = false
                                        },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    )
                                }

                                // Show manage directives dialog
                                if (showManageDirectivesDialog) {
                                    manageDirectivesDialog(
                                        directives = availableDirectives,
                                        onDismiss = { showManageDirectivesDialog = false },
                                        onAdd = { name, content, applyToCurrent ->
                                            val newDirective = directiveService.createDirective(name, content)
                                            availableDirectives = directiveService.listAllDirectives()
                                            if (applyToCurrent) {
                                                actions.setDirective(newDirective.id)
                                            }
                                        },
                                        onUpdate = { id, newName, newContent ->
                                            directiveService.updateDirective(id, newName, newContent)
                                            availableDirectives = directiveService.listAllDirectives()
                                        },
                                        onDelete = { id ->
                                            directiveService.deleteDirective(id)
                                            if (selectedDirective == id) {
                                                actions.setDirective(null)
                                            }
                                            availableDirectives = directiveService.listAllDirectives()
                                        },
                                    )
                                }
                            }

                            if (sessionId != null && messages.isNotEmpty()) {
                                sessionActionsMenu(
                                    sessionId = sessionId,
                                    onRenameSession = onRenameSession,
                                    onExportSession = onExportSession,
                                    onDeleteSession = onDeleteSession,
                                    onStarSession = onStarSession,
                                    onShowSessionSummary = { sid ->
                                        sessionMemorySessionId = sid
                                        showSessionMemoryDialog = true
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Search bar - fixed at top, always visible when search mode is active
            if (isSearchMode) {
                HorizontalDivider()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = AppComponents.bannerCardColors(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Search icon
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )

                        // Search input field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = actions::searchMessages,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFocusRequester),
                            placeholder = { Text(stringResource("chat.search.placeholder")) },
                            singleLine = true,
                            colors = AppComponents.outlinedTextFieldColors(),
                        )

                        // Result count
                        if (!isSearching && searchQuery.isNotEmpty()) {
                            Text(
                                text = if (searchResults.isEmpty()) {
                                    stringResource("chat.search.no.results")
                                } else {
                                    "${currentSearchResultIndex + 1}/${searchResults.size}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }

                        // Navigation buttons (Previous)
                        IconButton(
                            onClick = actions::previousSearchResult,
                            enabled = searchResults.isNotEmpty(),
                            modifier = Modifier
                                .size(36.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Previous result (${AppShortcut.PREVIOUS_SEARCH_RESULT.getDisplayString()})",
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Navigation buttons (Next)
                        IconButton(
                            onClick = actions::nextSearchResult,
                            enabled = searchResults.isNotEmpty(),
                            modifier = Modifier
                                .size(36.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next result (${AppShortcut.NEXT_SEARCH_RESULT.getDisplayString()})",
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Close button
                        IconButton(
                            onClick = actions::clearSearch,
                            modifier = Modifier
                                .size(36.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close search",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // Download attachment handler
            val saveDialogTitle = stringResource("attachment.save.file")
            val downloadAttachment: (FileAttachmentDTO) -> Unit = { attachment ->
                scope.launch {
                    val nameWithoutExt = attachment.fileName.substringBeforeLast('.', attachment.fileName)
                    val ext = attachment.fileName.substringAfterLast('.', "")
                    val targetFile = FileDialogUtils.pickSavePath(
                        suggestedName = nameWithoutExt,
                        extension = ext,
                        title = saveDialogTitle,
                    ) ?: return@launch
                    attachment.filePath?.let { filePath ->
                        val sourceFile = File(filePath)
                        if (sourceFile.exists()) {
                            try {
                                sourceFile.copyTo(targetFile, overwrite = true)
                                log.debug("Downloaded attachment: ${attachment.fileName} to ${targetFile.absolutePath}")
                            } catch (e: Exception) {
                                log.error("Error copying attachment file: ${e.message}", e)
                            }
                        } else {
                            log.error("Source file not found: $filePath")
                        }
                    } ?: log.error("Attachment file path is null: ${attachment.fileName}")
                }
            }

            // ── Auto-scroll logic ────────────────────────────────────────────
            val currentUserMessageCount = messages.count { it.isUser }
            var lastUserMessageCount by remember { mutableStateOf(0) }
            var userScrolledUp by remember { mutableStateOf(false) }
            var lastSeenPrependGeneration by remember { mutableStateOf(prependGeneration) }
            var savedScrollValue by remember { mutableStateOf(-1) }
            var savedScrollMax by remember { mutableStateOf(-1) }

            if (prependGeneration != lastSeenPrependGeneration) {
                savedScrollValue = messagesScrollState.value
                savedScrollMax = messagesScrollState.maxValue
                lastSeenPrependGeneration = prependGeneration
            }

            LaunchedEffect(currentUserMessageCount) {
                if (currentUserMessageCount > lastUserMessageCount) {
                    lastUserMessageCount = currentUserMessageCount
                    userScrolledUp = false
                    messagesScrollState.scrollTo(messagesScrollState.maxValue)
                }
            }

            // Only update userScrolledUp when the user is physically dragging/scrolling,
            // not when maxValue grows underneath a stationary scroll position.
            LaunchedEffect(messagesScrollState.value) {
                if (messagesScrollState.isScrollInProgress) {
                    val distanceFromBottom = messagesScrollState.maxValue - messagesScrollState.value
                    userScrolledUp = distanceFromBottom > 100
                }
                if (messagesScrollState.value < 100 && hasMoreMessages && !isLoadingPrevious) {
                    actions.loadPrevious()
                }
            }

            LaunchedEffect(messagesScrollState.maxValue) {
                val sv = savedScrollValue
                val sm = savedScrollMax
                if (sv >= 0 && sm >= 0 && messagesScrollState.maxValue > sm) {
                    val addedHeight = messagesScrollState.maxValue - sm
                    messagesScrollState.scrollTo((sv + addedHeight).coerceIn(0, messagesScrollState.maxValue))
                    savedScrollValue = -1
                    savedScrollMax = -1
                } else if (!userScrolledUp && sv < 0) {
                    messagesScrollState.scrollTo(messagesScrollState.maxValue)
                }
            }

            LaunchedEffect(currentSearchResultIndex, searchQuery) {
                if (searchQuery.isNotBlank() && messages.isNotEmpty()) {
                    val estimatedItemHeight = 150f
                    val targetPosition = (
                        currentSearchResultIndex * estimatedItemHeight *
                            messagesScrollState.maxValue / (messages.size * estimatedItemHeight)
                        ).toInt()
                    messagesScrollState.animateScrollTo(targetPosition)
                }
            }

            // ── Messages area ────────────────────────────────────────────────
            // The outer Box is the scroll container — the full chat area scrolls,
            // not just the inner column. This means scroll works regardless of
            // where the mouse is within the chat panel.
            var viewportBounds by remember { mutableStateOf<Rect?>(null) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(end = 8.dp) // room for scrollbar
                        .verticalScroll(messagesScrollState)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) viewportBounds = coords.boundsInWindow()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Constrain content width while allowing scroll to fill the whole area
                    Box(modifier = Modifier.widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH).fillMaxWidth()) {
                        when {
                            isSearchMode && searchResults.isEmpty() && !isSearching -> {
                                Text(
                                    stringResource("chat.search.not.found", searchQuery),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.align(Alignment.Center).padding(top = 32.dp),
                                )
                            }

                            isSearchMode -> {
                                messageList(
                                    messages = searchResults,
                                    isThinking = false,
                                    thinkingElapsedSeconds = 0,
                                    spinnerFrame = spinnerFrame.toString(),
                                    isLoadingPrevious = false,
                                    searchQuery = searchQuery,
                                    currentSearchResultIndex = currentSearchResultIndex,
                                    onMessageClick = onJumpToMessage,
                                    onEditMessage = { message ->
                                        if (message.isUser) {
                                            editingMessage = message
                                            inputText = TextFieldValue(text = message.content, selection = TextRange(0))
                                            attachments = message.attachments
                                        } else {
                                            editingAIMessage = message
                                        }
                                    },
                                    onDownloadAttachment = downloadAttachment,
                                    userAvatarPainter = userAvatarPainter,
                                    aiAvatarPainter = aiAvatarPainter,
                                    onRetryMessage = { messageId -> actions.retryMessage(messageId, currentEnabledServerIds) },
                                    viewportTopY = viewportBounds?.top,
                                )
                            }

                            messages.isEmpty() -> {
                                Text(
                                    stringResource("chat.welcome"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.align(Alignment.Center).padding(top = 32.dp),
                                )
                            }

                            else -> {
                                messageList(
                                    messages = messages,
                                    isThinking = isThinking,
                                    thinkingElapsedSeconds = thinkingElapsedSeconds,
                                    spinnerFrame = spinnerFrame.toString(),
                                    isLoadingPrevious = isLoadingPrevious,
                                    onEditMessage = { message ->
                                        if (message.isUser) {
                                            editingMessage = message
                                            inputText = TextFieldValue(text = message.content, selection = TextRange(0))
                                            attachments = message.attachments
                                        } else {
                                            editingAIMessage = message
                                        }
                                    },
                                    onDownloadAttachment = downloadAttachment,
                                    userAvatarPainter = userAvatarPainter,
                                    aiAvatarPainter = aiAvatarPainter,
                                    onRetryMessage = { messageId -> actions.retryMessage(messageId, currentEnabledServerIds) },
                                    viewportTopY = viewportBounds?.top,
                                )
                            }
                        }
                    }
                }

                // Scrollbar — belongs to ChatView, spans the full messages area
                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(messagesScrollState),
                    style = ScrollbarStyle(
                        minimalHeight = 16.dp,
                        thickness = 8.dp,
                        shape = MaterialTheme.shapes.small,
                        hoverDurationMillis = 300,
                        unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    ),
                )
            }

            // Input area
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                chatInputField(
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    attachments = attachments,
                    onAttachmentsChange = { attachments = it },
                    onSendMessage = { mode ->
                        if (inputText.text.isNotBlank() && !isLoading && !isThinking) {
                            actions.sendOrEditMessage(
                                mode,
                                inputText.text,
                                attachments,
                                editingMessage,
                                currentEnabledServerIds,
                            )
                            inputText = TextFieldValue("")
                            attachments = emptyList()
                            editingMessage = null
                        }
                    },
                    isLoading = isLoading,
                    isThinking = isThinking,
                    onStopResponse = actions::cancelResponse,
                    errorMessage = errorMessage,
                    editingMessage = editingMessage,
                    onCancelEdit = {
                        editingMessage = null
                        inputText = TextFieldValue("")
                        attachments = emptyList()
                    },
                    sessionId = sessionId,
                    onEnabledServerIdsChange = { currentEnabledServerIds = it },
                    onNavigateToMcpSettings = onNavigateToMcpSettings,
                    modifier = Modifier
                        .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            } // end centered Box
        } // End of main chat Column

        // Project Side Panel (right side) - Tabbed panel with RAG sources, MCP, etc.
        if (project != null) {
            projectSidePanel(
                project = project,
                ragIndexingStatus = ragIndexingStatus,
                ragIndexingPercentage = ragIndexingPercentage,
                isExpanded = sidePanelExpanded,
                onExpandedChange = { sidePanelExpanded = it },
                onAddToChat = { filePaths ->
                    val newAttachments = filePaths.map { path ->
                        val file = File(path)
                        FileAttachmentDTO(
                            id = randomUUID().toString(),
                            messageId = "",
                            sessionId = sessionId ?: "",
                            fileName = file.name,
                            mimeType = file.extension,
                            size = file.length(),
                            createdAt = LocalDateTime.now(),
                            content = null,
                            filePath = file.absolutePath,
                        )
                    }
                    // Merge, avoiding duplicates by path
                    val existingPaths = attachments.mapNotNull { it.filePath }.toSet()
                    val toAdd = newAttachments.filter { it.filePath !in existingPaths }
                    if (toAdd.isNotEmpty()) {
                        attachments = attachments + toAdd
                    }
                },
                modifier = Modifier.fillMaxHeight(),
            )
        }
    } // End of Row

    // AI Message Edit Dialog
    editingAIMessage?.let { message ->
        aiMessageEditDialog(
            message = message,
            onDismiss = { editingAIMessage = null },
            onSave = { newContent ->
                message.id?.let { messageId ->
                    actions.updateAIMessage(messageId, newContent)
                }
            },
        )
    }

    // Session memory dialog
    if (showSessionMemoryDialog) {
        sessionMemoryDialog(
            sessionId = sessionMemorySessionId,
            onLoadMemory = { sid ->
                withContext(Dispatchers.IO) {
                    DatabaseManager.getInstance().getSessionMemoryRepository().getBySessionId(sid)
                }
            },
            onDismiss = {
                showSessionMemoryDialog = false
                sessionMemorySessionId = null
            },
        )
    }
}
