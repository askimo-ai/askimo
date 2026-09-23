/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.service.ChatDirectiveService
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.IndexingRequestedEvent
import io.askimo.core.event.internal.ProjectRefreshEvent
import io.askimo.core.event.internal.ReIndexEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.core.rag.container.IndexingContainerType
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.util.TimeUtil
import io.askimo.desktop.knowledgesource.addReferenceMaterialDialog
import io.askimo.desktop.knowledgesource.buildKnowledgeSourceConfigs
import io.askimo.desktop.knowledgesource.knowledgeSourcesPanel
import io.askimo.desktop.knowledgesource.mergeKnowledgeSourceConfigs
import io.askimo.ui.chat.CreationMode
import io.askimo.ui.chat.chatInputField
import io.askimo.ui.common.components.embeddingModelNotConfiguredBanner
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.session.SessionActionMenu
import io.askimo.ui.session.SessionActionMenu.projectViewMenu
import io.askimo.ui.session.sessionTooltip
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import kotlin.collections.emptyList
import kotlin.let

/**
 * Project view showing project details and chat interface.
 */
@Composable
fun projectView(
    project: Project,
    onBack: () -> Unit,
    onStartChat: (projectId: String, mode: CreationMode, message: String, attachments: List<FileAttachmentDTO>, enabledServerIds: Set<String>, directiveId: String?, useWebSearch: Boolean) -> Unit,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (sessionId: String, projectId: String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onNavigateToMcpSettings: (() -> Unit)? = null,
    onNavigateToAiProviderSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Create ViewModel
    val scope = rememberCoroutineScope()
    val viewModel = remember(project.id) {
        GlobalContext.get().get<ProjectViewModel> { parametersOf(scope, project.id) }
    }

    // Local UI state
    var showProjectMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReIndexConfirmDialog by remember { mutableStateOf(false) }
    var showAddReferenceMaterialDialog by remember { mutableStateOf(false) }

    // Use ViewModel state
    val currentProject = viewModel.currentProject ?: project
    val projectSessions = viewModel.projectSessions
    val allProjects = viewModel.allProjects
    val indexProgress = viewModel.indexProgress

    // Get repository
    val projectRepository = remember { DatabaseManager.getInstance().getProjectRepository() }

    // Broadcast an indexing request on entry so sources are (re-)indexed/watched.
    // Gated on embeddingModelConfigured — otherwise RagIndexer would throw and surface
    // a duplicate error dialog on top of the banner shown below.
    LaunchedEffect(currentProject.id, viewModel.embeddingModelConfigured) {
        if (viewModel.embeddingModelConfigured) {
            EventBus.post(
                IndexingRequestedEvent(
                    containerId = currentProject.id,
                    containerType = IndexingContainerType.PROJECT,
                    knowledgeSources = null,
                    watchForChanges = true,
                ),
            )
        }
    }

    // Sticky chat input at the bottom needs a bounded Column for weight(1f) to work;
    // CONTENT_MAX_WIDTH is applied manually here.
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Scrollable content — full width captures all scroll events
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Width-constrained inner content
                Column(
                    modifier = Modifier
                        .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(start = Spacing.extraLarge, end = Spacing.scrollbarGutter, top = Spacing.extraLarge, bottom = Spacing.small),
                ) {
                    // ── Back navigation breadcrumb ─────────────────────────
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = Spacing.small),
                    ) {
                        val breadcrumbColor = AppTextStyles.secondaryContent
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource("action.back"),
                                tint = breadcrumbColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        TextButton(
                            onClick = onBack,
                            colors = ButtonDefaults.textButtonColors(contentColor = breadcrumbColor),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = stringResource("projects.title"),
                                style = AppTextStyles.caption,
                                color = breadcrumbColor,
                            )
                        }
                    }

                    // ── Project hero card ───────────────────────────────────
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.large),
                        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.large),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Project avatar — colored circle with first letter
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = currentProject.name.firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                                    style = AppTextStyles.pageTitle,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }

                            // Title + description — full content, no truncation
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Column {
                                    Text(
                                        text = currentProject.name,
                                        style = AppTextStyles.pageTitle,
                                    )
                                    currentProject.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                        Text(
                                            text = desc,
                                            style = AppTextStyles.bodySecondary,
                                            modifier = Modifier.padding(top = Spacing.extraSmall),
                                        )
                                    }
                                }
                            }

                            // Menu button
                            Box {
                                themedTooltip(text = stringResource("project.menu.tooltip")) {
                                    IconButton(
                                        onClick = { showProjectMenu = true },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = stringResource("project.menu.tooltip"),
                                            tint = AppTextStyles.primaryContent,
                                        )
                                    }
                                }
                                dropdownMenu(
                                    expanded = showProjectMenu,
                                    onDismissRequest = { showProjectMenu = false },
                                ) {
                                    SessionActionMenu.projectActionMenu(
                                        onEditProject = {
                                            onEditProject(currentProject.id)
                                            showProjectMenu = false
                                        },
                                        onDeleteProject = {
                                            showDeleteDialog = true
                                            showProjectMenu = false
                                        },
                                        onReindexProject = {
                                            showProjectMenu = false
                                            if (!viewModel.embeddingModelConfigured) {
                                                // No-op — banner above already prompts to configure an embedding model.
                                            } else if (indexProgress.status == IndexStatus.INDEXING) {
                                                showReIndexConfirmDialog = true
                                            } else {
                                                EventBus.post(
                                                    ReIndexEvent(
                                                        containerId = currentProject.id,
                                                        containerType = IndexingContainerType.PROJECT,
                                                        reason = "Manual re-index requested by user from project menu",
                                                    ),
                                                )
                                            }
                                        },
                                        onDismiss = { showProjectMenu = false },
                                    )
                                }
                            }
                        }
                    }

                    if (!viewModel.embeddingModelConfigured && onNavigateToAiProviderSettings != null) {
                        embeddingModelNotConfiguredBanner(
                            providerSupportsEmbedding = viewModel.embeddingSupportedByProvider,
                            onConfigureClick = onNavigateToAiProviderSettings,
                        )
                        Spacer(modifier = Modifier.height(Spacing.large))
                    }

                    // Shared collapsible component (see knowledgesource.knowledgeSourcesPanel),
                    // also used by the Resource Collection detail view.
                    knowledgeSourcesPanel(
                        key = currentProject.id,
                        knowledgeSources = currentProject.knowledgeSources,
                        indexProgress = indexProgress,
                        embeddingModelConfigured = viewModel.embeddingModelConfigured,
                        onShowAddDialog = { showAddReferenceMaterialDialog = true },
                        modifier = Modifier.padding(bottom = Spacing.extraLarge),
                    ) { source ->
                        knowledgeSourceItem(
                            source = source,
                            onDelete = { viewModel.deleteKnowledgeSource(source) },
                            onRescan = { viewModel.rescanKnowledgeSource(source) },
                            onWatchToggle = { watch ->
                                if (source is LocalFoldersKnowledgeSourceConfig) {
                                    viewModel.toggleWatchForChanges(source, watch)
                                }
                            },
                        )
                    }

                    // Sessions Section
                    if (projectSessions.isNotEmpty()) {
                        Text(
                            text = stringResource("project.recent.chats"),
                            style = AppTextStyles.sectionTitle,
                            modifier = Modifier.padding(bottom = Spacing.medium),
                        )
                    }

                    // Display sessions (no LazyColumn, just iterate)
                    if (projectSessions.isEmpty()) {
                        Text(
                            text = stringResource("project.no.chats"),
                            style = AppTextStyles.bodySecondary,
                            modifier = Modifier.padding(vertical = Spacing.large),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            projectSessions.forEach { session ->
                                sessionCard(
                                    session = session,
                                    onClick = { onResumeSession(session.id) },
                                    onDeleteSession = { sessionId ->
                                        onDeleteSession(sessionId, currentProject.id)
                                    },
                                    onRenameSession = onRenameSession,
                                    onExportSession = onExportSession,
                                    currentProject = currentProject,
                                    allProjects = allProjects,
                                    viewModel = viewModel,
                                )
                            }
                        }
                    }
                }
            } // end scrollable content column

            // Isolated footer so keystrokes only recompose it, not the whole project view.
            projectChatInputFooter(
                projectId = currentProject.id,
                projectName = currentProject.name,
                onStartChat = onStartChat,
                onNavigateToMcpSettings = onNavigateToMcpSettings,
            )
        } // end outer Column

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            style = AppComponents.scrollbarStyle(),
        )
    } // end outer Box

    // Delete project confirmation dialog
    if (showDeleteDialog) {
        deleteProjectDialog(
            projectName = currentProject.name,
            onConfirm = {
                onDeleteProject(currentProject.id)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    // Re-index confirmation (shown when a re-index is requested while already indexing)
    if (showReIndexConfirmDialog) {
        reIndexConfirmDialog(
            projectName = currentProject.name,
            onConfirm = {
                showReIndexConfirmDialog = false
                EventBus.post(
                    ReIndexEvent(
                        containerId = currentProject.id,
                        containerType = IndexingContainerType.PROJECT,
                        reason = "Manual re-index confirmed by user from project menu",
                    ),
                )
            },
            onDismiss = { showReIndexConfirmDialog = false },
        )
    }

    // Add reference material dialog
    if (showAddReferenceMaterialDialog) {
        addReferenceMaterialDialog(
            onDismiss = { showAddReferenceMaterialDialog = false },
            onAdd = { newSources ->
                // Build knowledge source configs from the new items
                val newConfigs = buildKnowledgeSourceConfigs(newSources)

                // Merge with existing knowledge sources
                val mergedConfigs = mergeKnowledgeSourceConfigs(
                    existing = currentProject.knowledgeSources,
                    new = newConfigs,
                )

                // Update the project
                projectRepository.updateProject(
                    projectId = currentProject.id,
                    name = currentProject.name,
                    description = currentProject.description,
                    knowledgeSources = mergedConfigs,
                )

                // Re-index the new sources only if embeddings are configured; otherwise
                // they're saved but left un-indexed until the user configures a model
                // (see banner above).
                if (viewModel.embeddingModelConfigured) {
                    EventBus.post(
                        IndexingRequestedEvent(
                            containerId = currentProject.id,
                            containerType = IndexingContainerType.PROJECT,
                            knowledgeSources = newConfigs,
                            watchForChanges = true,
                        ),
                    )
                }

                // The dialog is container-agnostic and doesn't post this itself — it's
                // the caller's responsibility (see ResourceCollectionDetailView's equivalent).
                EventBus.post(
                    ProjectRefreshEvent(
                        projectId = currentProject.id,
                        reason = "Knowledge sources added via dialog",
                    ),
                )

                showAddReferenceMaterialDialog = false
            },
        )
    }
}

@Composable
private fun sessionCard(
    session: ChatSession,
    onClick: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    currentProject: Project,
    allProjects: List<Project>,
    viewModel: ProjectViewModel,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var sessionIdToMove by remember { mutableStateOf<String?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clickableCard(cornerRadius = 8.dp, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = AppColors.codeBlockBorderColor(),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = AppTextStyles.secondaryContent,
            )

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.medium),
            ) {
                sessionTooltip(session = session) {
                    Text(
                        text = session.title,
                        style = AppTextStyles.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = TimeUtil.formatDisplay(session.updatedAt),
                    style = AppTextStyles.caption,
                )
            }

            if (isHovered || showMenu) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(24.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = AppTextStyles.secondaryContent,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    dropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        projectViewMenu(
                            currentProjectId = currentProject.id,
                            currentProjectName = currentProject.name,
                            availableProjects = allProjects,
                            onExport = { onExportSession(session.id) },
                            onRename = { onRenameSession(session.id, session.title) },
                            onDelete = { onDeleteSession(session.id) },
                            onMoveToNewProject = {
                                sessionIdToMove = session.id
                                showNewProjectDialog = true
                            },
                            onMoveToExistingProject = { selectedProject ->
                                viewModel.moveSessionToProject(session.id, selectedProject.id)
                            },
                            onRemoveFromProject = {
                                viewModel.removeSessionFromProject(session.id)
                                // Refresh global sessions list (session now appears in "All Sessions")
                                EventBus.post(
                                    SessionsRefreshEvent(
                                        reason = "Session ${session.id} removed from project",
                                    ),
                                )
                            },
                            onDismiss = { showMenu = false },
                        )
                    }
                } // end Box
            } // end if (isHovered || showMenu)
        }
    }

    // New Project Dialog
    if (showNewProjectDialog && sessionIdToMove != null) {
        val projectRepository = remember { DatabaseManager.getInstance().getProjectRepository() }
        newProjectDialog(
            onDismiss = {
                showNewProjectDialog = false
                sessionIdToMove = null
            },
            onCreateProject = { name, description ->
                // Project is already created in the dialog with all knowledge sources
                val createdProject = projectRepository.findProjectByName(name)

                if (createdProject != null) {
                    viewModel.moveSessionToProject(sessionIdToMove!!, createdProject.id)
                }

                showNewProjectDialog = false
                sessionIdToMove = null
            },
        )
    }
}

@Composable
private fun knowledgeSourceItem(
    source: KnowledgeSourceConfig,
    onDelete: () -> Unit = {},
    onRescan: () -> Unit = {},
    onWatchToggle: (Boolean) -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.micro, horizontal = Spacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Resource identifier (path/URL) with bullet point
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "•",
                    style = AppTextStyles.caption,
                )
                Text(
                    text = source.resourceIdentifier,
                    style = AppTextStyles.caption,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Watch-for-changes toggle — only folders can be watched (files/URLs never are)
            if (source is LocalFoldersKnowledgeSourceConfig) {
                themedTooltip(text = stringResource("projects.sources.watch.tooltip")) {
                    Checkbox(
                        checked = source.watchForChanges,
                        onCheckedChange = onWatchToggle,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }

            // Actions menu — Rescan / Delete
            Box {
                themedTooltip(text = stringResource("projects.sources.more.tooltip")) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource("projects.sources.more.tooltip"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                AppComponents.dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource("action.rescan")) },
                        onClick = {
                            showMenu = false
                            onRescan()
                        },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        }
    }
}

/**
 * Isolated footer owning all chat-input state, so keystrokes don't recompose the
 * heavy scrollable content above (hero card, knowledge sources panel, session cards).
 */
@Composable
private fun projectChatInputFooter(
    projectId: String,
    projectName: String,
    onStartChat: (projectId: String, mode: CreationMode, message: String, attachments: List<FileAttachmentDTO>, enabledServerIds: Set<String>, directiveId: String?, useWebSearch: Boolean) -> Unit,
    onNavigateToMcpSettings: (() -> Unit)? = null,
) {
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var attachments by remember { mutableStateOf<List<FileAttachmentDTO>>(emptyList()) }
    var currentEnabledServerIds by remember { mutableStateOf(emptySet<String>()) }
    // Pre-select this project's default directive (falling back to the global default)
    // so new chats start with the right instructions.
    var selectedDirective by remember(projectId) {
        mutableStateOf(
            GlobalContext.get().get<ChatDirectiveService>().resolveDefaultDirectiveId(projectId),
        )
    }
    var webSearchInRag by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .padding(start = Spacing.extraLarge, end = Spacing.scrollbarGutter, bottom = Spacing.extraLarge),
        ) {
            chatInputField(
                inputText = inputText,
                onInputTextChange = { inputText = it },
                attachments = attachments,
                onAttachmentsChange = { attachments = it },
                onSendMessage = { mode ->
                    if (inputText.text.isNotBlank()) {
                        onStartChat(projectId, mode, inputText.text, attachments, currentEnabledServerIds, selectedDirective, webSearchInRag)
                        inputText = TextFieldValue("")
                        attachments = emptyList()
                    }
                },
                onEnabledServerIdsChange = { currentEnabledServerIds = it },
                onNavigateToMcpSettings = onNavigateToMcpSettings,
                selectedDirective = selectedDirective,
                onToggleDirective = { selectedDirective = it },
                isProjectSession = true,
                onWebSearchInRagChange = { webSearchInRag = it },
                sessionId = projectId,
                placeholder = stringResource("project.new.chat.placeholder", projectName),
                modifier = Modifier.padding(top = Spacing.large),
            )
        }
    }
}
