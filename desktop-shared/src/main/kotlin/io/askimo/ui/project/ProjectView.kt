/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.project

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFilesKnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.domain.UrlKnowledgeSourceConfig
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectIndexingRequestedEvent
import io.askimo.core.event.internal.ProjectReIndexEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.util.TimeUtil
import io.askimo.ui.chat.CreationMode
import io.askimo.ui.chat.chatInputField
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.successIcon
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.session.SessionActionMenu
import io.askimo.ui.session.SessionActionMenu.projectViewMenu
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import java.awt.Desktop
import java.net.URI
import kotlin.collections.emptyList
import kotlin.let

/**
 * Project view showing project details and chat interface.
 */
@Composable
fun projectView(
    project: Project,
    onBack: () -> Unit,
    onStartChat: (projectId: String, mode: CreationMode, message: String, attachments: List<FileAttachmentDTO>, enabledServerIds: Set<String>) -> Unit,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (sessionId: String, projectId: String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onNavigateToMcpSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Create ViewModel
    val scope = rememberCoroutineScope()
    val viewModel = remember(project.id) {
        GlobalContext.get().get<ProjectViewModel> { parametersOf(scope, project.id) }
    }

    // Local UI state
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var attachments by remember { mutableStateOf<List<FileAttachmentDTO>>(emptyList()) }
    var currentEnabledServerIds by remember { mutableStateOf(emptySet<String>()) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddReferenceMaterialDialog by remember { mutableStateOf(false) }

    // Use ViewModel state
    val currentProject = viewModel.currentProject ?: project
    val projectSessions = viewModel.projectSessions
    val allProjects = viewModel.allProjects

    // Get repository
    val projectRepository = remember { DatabaseManager.getInstance().getProjectRepository() }

    // Broadcast indexing request when entering the project view so that
    // all knowledge sources are (re-)indexed / watched for changes.
    LaunchedEffect(currentProject.id) {
        EventBus.post(
            ProjectIndexingRequestedEvent(
                projectId = currentProject.id,
                knowledgeSources = null,
                watchForChanges = true,
            ),
        )
    }

    // ProjectView has a sticky chat input at the bottom — it needs a bounded Column
    // so that weight(1f) works correctly. We apply CONTENT_MAX_WIDTH manually.
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
                        .padding(start = 24.dp, end = 36.dp, top = 24.dp, bottom = 8.dp),
                ) {
                    // ── Back navigation (same style as PlanDetailView) ─────────
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource("action.back"),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = stringResource("projects.title"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = currentProject.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            currentProject.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }

                        Box {
                            themedTooltip(text = stringResource("project.menu.tooltip")) {
                                IconButton(
                                    onClick = { showProjectMenu = true },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource("project.menu.tooltip"),
                                        tint = MaterialTheme.colorScheme.onSurface,
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
                                        EventBus.post(
                                            ProjectReIndexEvent(
                                                projectId = currentProject.id,
                                                reason = "Manual re-index requested by user from project menu",
                                            ),
                                        )
                                        showProjectMenu = false
                                    },
                                    onDismiss = { showProjectMenu = false },
                                )
                            }
                        }
                    }

                    // Knowledge Sources Panel
                    knowledgeSourcesPanel(
                        currentProject = currentProject,
                        viewModel = viewModel,
                        onShowAddDialog = { showAddReferenceMaterialDialog = true },
                        modifier = Modifier.padding(bottom = 24.dp),
                    )

                    // Sessions Section
                    if (projectSessions.isNotEmpty()) {
                        Text(
                            text = stringResource("project.recent.chats"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    // Display sessions (no LazyColumn, just iterate)
                    if (projectSessions.isEmpty()) {
                        Text(
                            text = stringResource("project.no.chats"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
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

            // Fixed chat input footer — width-constrained, outside scroll
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 36.dp, bottom = 24.dp),
                ) {
                    chatInputField(
                        inputText = inputText,
                        onInputTextChange = { inputText = it },
                        attachments = attachments,
                        onAttachmentsChange = { attachments = it },
                        onSendMessage = { mode ->
                            if (inputText.text.isNotBlank()) {
                                onStartChat(currentProject.id, mode, inputText.text, attachments, currentEnabledServerIds)
                                inputText = TextFieldValue("")
                                attachments = emptyList()
                            }
                        },
                        onEnabledServerIdsChange = { currentEnabledServerIds = it },
                        onNavigateToMcpSettings = onNavigateToMcpSettings,
                        sessionId = currentProject.id,
                        placeholder = stringResource("project.new.chat.placeholder", currentProject.name),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        } // end outer Column

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
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

    // Add reference material dialog
    if (showAddReferenceMaterialDialog) {
        addReferenceMaterialDialog(
            projectId = currentProject.id,
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

                // Trigger re-indexing for the new sources
                EventBus.post(
                    ProjectIndexingRequestedEvent(
                        projectId = currentProject.id,
                        knowledgeSources = newConfigs,
                        watchForChanges = true,
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
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                themedTooltip(text = session.title) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = TimeUtil.formatDisplay(session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun knowledgeSourcesPanel(
    currentProject: Project,
    viewModel: ProjectViewModel,
    onShowAddDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember(currentProject.id) {
        mutableStateOf(currentProject.knowledgeSources.isEmpty())
    }

    val indexProgress = viewModel.indexProgress

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = AppComponents.bannerCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Collapsible header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left side - clickable expansion area (only if sources exist)
                if (currentProject.knowledgeSources.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                onClick = { isExpanded = !isExpanded },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            )
                            .pointerHoverIcon(PointerIcon.Hand),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )

                        Text(
                            text = stringResource("projects.sources.count", currentProject.knowledgeSources.size),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        // ── Indexed badge ──────────────────────────────────
                        if (indexProgress.isComplete) {
                            themedTooltip(text = stringResource("project.indexing.ready.tooltip")) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    successIcon(size = 14.dp)
                                    Text(
                                        text = stringResource("project.indexing.ready.label"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        val rotation by animateFloatAsState(
                            targetValue = if (isExpanded) 180f else 0f,
                            label = "rotation",
                        )

                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) {
                                stringResource("projects.sources.collapse")
                            } else {
                                stringResource("projects.sources.expand")
                            },
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.rotate(rotation),
                        )

                        themedTooltip(text = stringResource("projects.sources.info.tooltip")) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                        }
                    }
                } else {
                    // Empty state header
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )

                        Text(
                            text = stringResource("projects.sources.empty.title"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Right side - Guide link + Add button (always visible)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    linkButton(
                        onClick = {
                            try {
                                if (Desktop.isDesktopSupported()) {
                                    Desktop.getDesktop().browse(URI("https://askimo.chat/docs/desktop/rag/"))
                                }
                            } catch (_: Exception) {}
                        },
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource("projects.sources.guide"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    themedTooltip(text = stringResource("projects.sources.add.tooltip")) {
                        IconButton(
                            onClick = onShowAddDialog,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource("projects.sources.add.tooltip"),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            // ── Index progress indicator ───────────────────────────────────
            indexProgressIndicator(
                indexProgress = indexProgress,
                hasKnowledgeSources = currentProject.knowledgeSources.isNotEmpty(),
            )

            // Expandable content
            if (currentProject.knowledgeSources.isNotEmpty()) {
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        val groupedSources = currentProject.knowledgeSources.groupBy { source ->
                            when (source) {
                                is LocalFoldersKnowledgeSourceConfig -> stringResource("projects.sources.type.local_folders")
                                is LocalFilesKnowledgeSourceConfig -> stringResource("projects.sources.type.local_files")
                                is UrlKnowledgeSourceConfig -> stringResource("projects.sources.type.urls")
                            }
                        }

                        groupedSources.forEach { (groupName, sources) ->
                            Text(
                                text = groupName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                            sources.forEach { source ->
                                knowledgeSourceItem(
                                    source = source,
                                    onDelete = { viewModel.deleteKnowledgeSource(source) },
                                )
                            }
                        }
                    }
                }
            } else {
                // Empty state description below the header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = stringResource("projects.sources.empty.description"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun knowledgeSourceItem(
    source: KnowledgeSourceConfig,
    onDelete: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Resource identifier (path/URL) with bullet point
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = source.resourceIdentifier,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Delete button
        themedTooltip(text = stringResource("projects.sources.delete.tooltip")) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(32.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource("projects.sources.delete.tooltip"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun indexProgressIndicator(
    indexProgress: IndexProgress,
    hasKnowledgeSources: Boolean,
) {
    if (!hasKnowledgeSources) return

    when (indexProgress.status) {
        IndexStatus.QUEUED -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource("project.indexing.queued"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IndexStatus.INDEXING -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("project.indexing.label"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (indexProgress.totalFiles > 0) {
                        Text(
                            text = "${indexProgress.processedFiles} / ${indexProgress.totalFiles}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (indexProgress.totalFiles > 0) {
                    LinearProgressIndicator(
                        progress = { indexProgress.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                }
            }
        }

        IndexStatus.FAILED -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = indexProgress.error
                        ?: stringResource("project.indexing.failed"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        else -> Unit // READY, WATCHING, NOT_STARTED — no indicator needed
    }
}
