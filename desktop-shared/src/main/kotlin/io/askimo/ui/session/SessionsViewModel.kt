/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Label
import io.askimo.core.chat.domain.toggledLabelIds
import io.askimo.core.chat.service.ChatSessionExporterService
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.db.Pageable
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.BookmarkToggledEvent
import io.askimo.core.event.internal.ProjectsRefreshEvent
import io.askimo.core.event.internal.SessionCreatedEvent
import io.askimo.core.event.internal.SessionTitleUpdatedEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.core.exception.ExceptionHandler
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.ui.common.export.ExportFormat
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.shell.PinnedSidebarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for managing sessions view state and operations.
 */
class SessionsViewModel(
    private val scope: CoroutineScope,
    private val sessionService: ChatSessionService,
    private val sessionManager: SessionManager,
    private val onCreateNewSession: () -> String,
    private val onRenameComplete: () -> Unit = {}, // Callback when rename completes
) : PinnedSidebarState {
    private val log = logger<SessionsViewModel>()
    companion object {
        const val MAX_SIDEBAR_SESSIONS = 50

        const val MAX_FILENAME_LENGTH = 255
    }

    var pagedSessions by mutableStateOf<Pageable<ChatSession>?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var recentSessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set

    /** Current sort option for [recentSessions] in the sidebar, persisted across restarts. */
    var sortOption by mutableStateOf(SessionSortOption.fromPrefValue(ApplicationPreferences.getSidebarSessionsSortOption()))
        private set

    /** [recentSessions] reordered per [sortOption] for display in the sidebar. */
    val sortedRecentSessions: List<ChatSession>
        get() = recentSessions.sortedByOption(sortOption)

    /** Change the sidebar's sessions sort option and persist the choice. */
    fun selectSortOption(option: SessionSortOption) {
        if (sortOption == option) return
        sortOption = option
        ApplicationPreferences.setSidebarSessionsSortOption(option.prefValue)
    }

    /** Current group option for [recentSessions] in the sidebar, persisted across restarts. */
    var groupOption by mutableStateOf(SessionGroupOption.fromPrefValue(ApplicationPreferences.getSidebarSessionsGroupOption()))
        private set

    /** Change the sidebar's sessions group option and persist the choice. */
    fun selectGroupOption(option: SessionGroupOption) {
        if (groupOption == option) return
        groupOption = option
        ApplicationPreferences.setSidebarSessionsGroupOption(option.prefValue)
    }

    /** Transient (non-persisted) inline search query for filtering the sidebar's sessions list. */
    var sidebarSearchQuery by mutableStateOf("")

    /** [sortedRecentSessions] filtered per [sidebarSearchQuery] for display in the sidebar. */
    val filteredSortedRecentSessions: List<ChatSession>
        get() = sortedRecentSessions.filteredByQuery(sidebarSearchQuery)

    /** Whether the sidebar's bulk-selection mode is active. */
    var selectionMode by mutableStateOf(false)
        private set

    /** IDs of sessions currently selected while [selectionMode] is active. */
    var selectedSessionIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Toggles bulk-selection mode. Turning it off always clears any current selection. */
    fun toggleSelectionMode() {
        selectionMode = !selectionMode
        if (!selectionMode) {
            selectedSessionIds = emptySet()
        }
    }

    /** Toggles whether [sessionId] is selected. */
    fun toggleSessionSelected(sessionId: String) {
        selectedSessionIds = if (selectedSessionIds.contains(sessionId)) {
            selectedSessionIds - sessionId
        } else {
            selectedSessionIds + sessionId
        }
    }

    /** Selects every session in [visibleIds] (the currently visible/filtered sessions only). */
    fun selectAllVisible(visibleIds: List<String>) {
        selectedSessionIds = visibleIds.toSet()
    }

    /** Clears the current selection without leaving selection mode. */
    fun clearSelection() {
        selectedSessionIds = emptySet()
    }

    /** Deletes every currently selected session, then refreshes the list and exits selection mode. */
    fun bulkDeleteSelected() {
        val ids = selectedSessionIds
        if (ids.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sessionService.deleteSessions(ids.toList())
                }
                selectionMode = false
                selectedSessionIds = emptySet()
                refresh()
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "deleting selected sessions",
                    LocalizationManager.getString("sessions.error.deleting"),
                )
            }
        }
    }

    /**
     * Moves every currently selected session to [projectId] (or removes them from their project
     * when null), then refreshes the list and exits selection mode.
     */
    fun bulkMoveSelectedToProject(projectId: String?) {
        val ids = selectedSessionIds
        if (ids.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sessionService.updateSessionProjectForIds(ids.toList(), projectId)
                }
                EventBus.post(
                    ProjectsRefreshEvent(reason = "${ids.size} session(s) moved to project $projectId"),
                )
                EventBus.post(
                    SessionsRefreshEvent(reason = "${ids.size} session(s) moved to project $projectId"),
                )
                selectionMode = false
                selectedSessionIds = emptySet()
                refresh()
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "moving selected sessions",
                    LocalizationManager.getString("sessions.error.deleting"),
                )
            }
        }
    }

    /**
     * All starred sessions, loaded independently via [ChatSessionService.getStarredSessions].
     * This covers sessions of any age, not just the recent-sessions window.
     * Implements [PinnedSidebarState].
     */
    override var starredSessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set

    var totalSessionCount by mutableStateOf(0)
        private set

    /** sessionId → number of bookmarked messages. Only populated for sessions that have ≥1 bookmark. */
    var bookmarkCountsBySession by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** All labels the user has defined, ordered by name — loaded alongside [recentSessions]. */
    var allLabels by mutableStateOf<List<Label>>(emptyList())
        private set

    /** sessionId → assigned labels, bulk-loaded for the sessions currently visible in the sidebar. */
    var labelsBySessionId by mutableStateOf<Map<String, List<Label>>>(emptyMap())
        private set

    /**
     * labelId → total number of sessions carrying that label (across *all* sessions, not just
     * those currently loaded in the sidebar) — used by [manageLabelsDialog] to warn before
     * deleting a label that's still in use.
     */
    var labelUsageCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** Label ids currently selected in the sidebar's filter-by-label chip row ("any of" semantics). */
    var selectedLabelFilterIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** [filteredSortedRecentSessions] further filtered by [selectedLabelFilterIds], for sidebar display. */
    val sidebarVisibleSessions: List<ChatSession>
        get() = filteredSortedRecentSessions.filteredByLabels(selectedLabelFilterIds, labelsBySessionId)

    /** Toggles whether [labelId] is part of the active sidebar label filter. */
    fun toggleLabelFilter(labelId: String) {
        selectedLabelFilterIds = if (labelId in selectedLabelFilterIds) {
            selectedLabelFilterIds - labelId
        } else {
            selectedLabelFilterIds + labelId
        }
    }

    /** Clears the active label filter. */
    fun clearLabelFilter() {
        selectedLabelFilterIds = emptySet()
    }

    /** Whether the "Manage labels" dialog is currently shown. */
    var showManageLabelsDialog by mutableStateOf(false)
        private set

    fun openManageLabelsDialog() {
        showManageLabelsDialog = true
    }

    fun closeManageLabelsDialog() {
        showManageLabelsDialog = false
    }

    /**
     * Loads [allLabels] and [labelUsageCounts] (across all sessions), and rebuilds
     * [labelsBySessionId]: cached entries are reconciled against the fresh catalog (dropping
     * deleted labels, refreshing renamed/recolored ones), then every id in [sessionIds] is
     * overwritten with its current labels — including an empty list — since [bySession] omits
     * unlabeled sessions and would otherwise leave stale entries behind.
     */
    private fun loadLabels(sessionIds: List<String>) {
        scope.launch {
            try {
                val (labels, bySession, usageCounts) = withContext(Dispatchers.IO) {
                    val labels = sessionService.listLabels()
                    val bySession = sessionService.labelsForSessions(sessionIds)
                    val usageCounts = labels.associate { it.id to sessionService.sessionIdsForLabel(it.id).size }
                    Triple(labels, bySession, usageCounts)
                }
                allLabels = labels

                val freshById = labels.associateBy { it.id }
                // Drop deleted labels, refresh renamed/recolored ones in cached entries.
                val reconciled = labelsBySessionId.mapValues { (_, sessionLabels) ->
                    sessionLabels.mapNotNull { freshById[it.id] }
                }
                // Overwrite every queried session (even to empty) so removed labels disappear.
                val queried = sessionIds.associateWith { id -> bySession[id].orEmpty() }
                labelsBySessionId = reconciled + queried

                labelUsageCounts = usageCounts
            } catch (e: Exception) {
                log.error("Failed to load labels: ${e.message}", e)
            }
        }
    }

    /** Creates a new label, then refreshes [allLabels]. */
    fun createLabel(name: String, color: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { sessionService.createLabel(name, color) }
                loadLabels(recentSessions.map { it.id })
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(e, "creating label", LocalizationManager.getString("label.error.saving"))
            }
        }
    }

    /** Renames/recolors an existing label, then refreshes [allLabels] and [labelsBySessionId]. */
    fun updateLabel(label: Label) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { sessionService.updateLabel(label) }
                loadLabels(recentSessions.map { it.id })
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(e, "updating label", LocalizationManager.getString("label.error.saving"))
            }
        }
    }

    /** Deletes a label definition (cascades its assignment off every session), then refreshes state. */
    fun deleteLabel(labelId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { sessionService.deleteLabel(labelId) }
                selectedLabelFilterIds = selectedLabelFilterIds - labelId
                loadLabels(recentSessions.map { it.id })
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(e, "deleting label", LocalizationManager.getString("label.error.deleting"))
            }
        }
    }

    /** Per-session chain of in-flight label-assignment writes — each new write joins the previous
     *  one before running, so persisted snapshots land in the same order the user toggled them
     *  instead of racing on Dispatchers.IO and letting an older snapshot overwrite a newer one. */
    private val labelWriteJobs = mutableMapOf<String, Job>()

    /** Toggles whether [labelId] is assigned to [sessionId], applied optimistically then persisted. */
    fun toggleSessionLabel(sessionId: String, labelId: String) {
        val current = labelsBySessionId[sessionId].orEmpty()
        val isAssigned = current.any { it.id == labelId }
        val newIds = current.toggledLabelIds(labelId)

        // Optimistic local update so the chip/menu reflects the change instantly.
        val label = allLabels.find { it.id == labelId }
        labelsBySessionId = if (isAssigned || label == null) {
            labelsBySessionId + (sessionId to current.filterNot { it.id == labelId })
        } else {
            labelsBySessionId + (sessionId to current + label)
        }

        // Captured synchronously (before any suspension), so the chain preserves call order.
        val previousWrite = labelWriteJobs[sessionId]
        val write = scope.launch {
            previousWrite?.join()
            try {
                withContext(Dispatchers.IO) { sessionService.setSessionLabels(sessionId, newIds) }
                val delta = if (isAssigned) -1 else 1
                labelUsageCounts = labelUsageCounts +
                    (labelId to ((labelUsageCounts[labelId] ?: 0) + delta).coerceAtLeast(0))
            } catch (e: Exception) {
                log.error("Failed to toggle label {} on session {}: {}", labelId, sessionId, e.message)
                loadLabels(recentSessions.map { it.id })
            }
        }
        labelWriteJobs[sessionId] = write
    }

    var searchQuery by mutableStateOf("")
        private set

    private var searchDebounceJob: Job? = null

    // Export dialog state
    var showExportDialog by mutableStateOf(false)
        private set

    var exportSessionId by mutableStateOf<String?>(null)
        private set

    var exportSessionTitle by mutableStateOf("")
        private set

    var exportDefaultFilename by mutableStateOf("")
        private set

    var exportFormat by mutableStateOf(ExportFormat.MARKDOWN)
        private set

    var successMessage by mutableStateOf<String?>(null)
        private set

    // File overwrite confirmation state
    var showOverwriteConfirmation by mutableStateOf(false)
        private set

    var pendingExportPath by mutableStateOf<String?>(null)
        private set

    // Rename dialog state
    var showRenameDialog by mutableStateOf(false)
        private set

    var renameSessionId by mutableStateOf<String?>(null)
        private set

    var renameCurrentTitle by mutableStateOf("")
        private set

    private var sessionsPerPage = 10

    fun setPageSize(size: Int) {
        sessionsPerPage = size
        loadSessions(1)
    }

    init {
        loadSessions(1)
        loadRecentSessions()
        loadStarredSessions()
        subscribeToSessionEvents()
    }

    /**
     * Subscribe to internal events to keep session list updated.
     */
    private fun subscribeToSessionEvents() {
        // Listen for new session creation
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<SessionCreatedEvent>()
                .collect { event ->
                    if (event.projectId == null) {
                        log.debug("New session created: ${event.sessionId}, refreshing sidebar")
                        loadRecentSessions()
                    }
                }
        }

        // Listen for session title updates - update in-place instead of full reload
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<SessionTitleUpdatedEvent>()
                .collect { event ->
                    log.debug("Session ${event.sessionId} title updated to: ${event.newTitle}, updating in-place")
                    val updated = recentSessions.map { session ->
                        if (session.id == event.sessionId) session.copy(title = event.newTitle) else session
                    }
                    if (updated != recentSessions) {
                        recentSessions = updated
                    }
                    // Also update paged list if visible
                    pagedSessions?.let { paged ->
                        val updatedItems = paged.items.map { session ->
                            if (session.id == event.sessionId) session.copy(title = event.newTitle) else session
                        }
                        if (updatedItems != paged.items) {
                            pagedSessions = paged.copy(items = updatedItems)
                        }
                    }
                }
        }

        // Listen for generic session refresh requests
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<SessionsRefreshEvent>()
                .collect { event ->
                    log.debug("Sessions refresh requested: ${event.reason ?: "no reason specified"}")
                    loadRecentSessions()
                    loadStarredSessions()
                }
        }

        // Listen for bookmark toggles — update the sidebar badge count in-place,
        // no DB round-trip needed.
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<BookmarkToggledEvent>()
                .collect { event ->
                    val current = bookmarkCountsBySession[event.sessionId] ?: 0
                    val updated = (current + event.delta).coerceAtLeast(0)
                    bookmarkCountsBySession = if (updated == 0) {
                        bookmarkCountsBySession - event.sessionId
                    } else {
                        bookmarkCountsBySession + (event.sessionId to updated)
                    }
                    log.debug(
                        "Bookmark badge updated for session {}: {} → {}",
                        event.sessionId,
                        current,
                        updated,
                    )
                }
        }
    }

    /**
     * Load recent sessions for the sidebar.
     * Only loads sessions without a project (projectId is null).
     * Sessions with projects are shown in ProjectView.
     */
    fun loadRecentSessions() {
        scope.launch {
            try {
                val (sessions, total, bookmarkCounts) = withContext(Dispatchers.IO) {
                    val sessions = sessionService.getSessionsWithoutProject(MAX_SIDEBAR_SESSIONS)
                    val total = sessionService.countSessionsWithoutProject()
                    val bookmarkCounts = sessionService.getBookmarkCountsBySession()
                    Triple(sessions, total, bookmarkCounts)
                }
                recentSessions = sessions
                totalSessionCount = total
                bookmarkCountsBySession = bookmarkCounts
                loadLabels(sessions.map { it.id })

                log.debug("Loaded ${sessions.size} sessions without projects (showing ${sessions.size} in sidebar, total: $total)")
            } catch (e: Exception) {
                log.error("Failed to load recent sessions: ${e.message}", e)
            }
        }
    }

    /**
     * Load ALL starred sessions for the pinned sidebar section.
     */
    fun loadStarredSessions() {
        scope.launch {
            try {
                val sessions = withContext(Dispatchers.IO) {
                    sessionService.getStarredSessions()
                }
                starredSessions = sessions
                loadLabels(sessions.map { it.id })
                log.debug("Loaded ${sessions.size} starred sessions for pinned section")
            } catch (e: Exception) {
                log.error("Failed to load starred sessions: ${e.message}", e)
            }
        }
    }

    /**
     * Load sessions for a specific page, respecting any active [searchQuery].
     */
    fun loadSessions(page: Int = 1) {
        isLoading = true
        errorMessage = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (searchQuery.isBlank()) {
                        sessionService.getSessionsPagedWithoutProject(page, sessionsPerPage)
                    } else {
                        sessionService.searchSessionsWithoutProject(searchQuery, page, sessionsPerPage)
                    }
                }
                pagedSessions = result
                loadLabels(result.items.map { it.id })
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "loading sessions",
                    LocalizationManager.getString("sessions.error.loading"),
                )
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Update the search query and reload page 1 with a 300 ms debounce.
     */
    fun updateSearch(query: String) {
        searchQuery = query
        searchDebounceJob?.cancel()
        searchDebounceJob = scope.launch {
            delay(300.milliseconds)
            loadSessions(1)
        }
    }

    /**
     * Clear the search query and reload.
     */
    fun clearSearch() {
        searchQuery = ""
        searchDebounceJob?.cancel()
        loadSessions(1)
    }

    /**
     * Reload the current page.
     */
    fun refresh() {
        loadSessions(pagedSessions?.currentPage ?: 1)
        loadRecentSessions()
        loadStarredSessions()
    }

    /**
     * Go to the next page.
     */
    fun nextPage() {
        pagedSessions?.let {
            if (it.hasNextPage) {
                loadSessions(it.currentPage + 1)
            }
        }
    }

    /**
     * Go to the previous page.
     */
    fun previousPage() {
        pagedSessions?.let {
            if (it.hasPreviousPage) {
                loadSessions(it.currentPage - 1)
            }
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        errorMessage = null
    }

    /**
     * Delete a session with full cleanup including session manager state and automatic session switching.
     *
     * This method:
     * 1. Closes the session in SessionManager (stops any active streaming)
     * 2. Deletes the session from the database
     * 3. If the deleted session was active, switches to another session or creates a new one
     * 4. Refreshes the session list
     */
    fun deleteSessionWithCleanup(sessionId: String) {
        scope.launch {
            try {
                // 1. Clean up ViewModel and stop any active streaming
                sessionManager.closeSession(sessionId)

                // 2. Delete from database
                val deleted = withContext(Dispatchers.IO) {
                    sessionService.deleteSession(sessionId)
                }

                if (deleted) {
                    // 3. If deleted active session, switch to another or create new
                    if (sessionManager.activeSessionId == null) {
                        // Refresh to get updated list first
                        val updatedSessions = withContext(Dispatchers.IO) {
                            sessionService.getSessions(MAX_SIDEBAR_SESSIONS)
                        }

                        if (updatedSessions.isNotEmpty()) {
                            // Switch to first remaining session
                            sessionManager.switchToSession(updatedSessions.first().id)
                        } else {
                            // Create new empty session
                            val newSessionId = onCreateNewSession.invoke()
                            sessionManager.switchToSession(newSessionId)
                        }
                    }

                    // 4. Refresh the session list
                    refresh()
                } else {
                    errorMessage = LocalizationManager.getString("sessions.error.not.found")
                }
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "deleting session",
                    LocalizationManager.getString("sessions.error.deleting"),
                )
            }
        }
    }

    /**
     * Simple delete a session and refresh the list (without session manager cleanup).
     * Used when session manager is not available or cleanup is not needed.
     */
    fun deleteSession(sessionId: String) {
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    sessionService.deleteSession(sessionId)
                }
                if (deleted) {
                    refresh()
                } else {
                    errorMessage = LocalizationManager.getString("sessions.error.not.found")
                }
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "deleting session",
                    LocalizationManager.getString("sessions.error.deleting"),
                )
            }
        }
    }

    /**
     * Update the starred status of a session and refresh the list.
     */
    fun updateSessionStarred(sessionId: String, isStarred: Boolean) {
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    sessionService.updateSessionStarred(sessionId, isStarred)
                }
                if (updated) {
                    refresh()
                } else {
                    errorMessage = LocalizationManager.getString("sessions.error.not.found")
                }
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "updating session",
                    LocalizationManager.getString("sessions.error.updating"),
                )
            }
        }
    }

    /**
     * Show the rename dialog for a session.
     */
    fun showRenameDialog(sessionId: String) {
        scope.launch {
            try {
                // Find the session to get the current title
                val session = withContext(Dispatchers.IO) {
                    sessionService.getSessionById(sessionId)
                } ?: run {
                    errorMessage = LocalizationManager.getString("sessions.error.not.found")
                    return@launch
                }

                // Set state to show dialog
                renameSessionId = sessionId
                renameCurrentTitle = session.title
                showRenameDialog = true
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "preparing rename",
                    "Failed to prepare rename: ${e.message}",
                )
            }
        }
    }

    /**
     * Dismiss the rename dialog.
     */
    fun dismissRenameDialog() {
        showRenameDialog = false
        renameSessionId = null
        renameCurrentTitle = ""
    }

    /**
     * Execute the actual rename after user confirms new title.
     */
    fun executeRename(newTitle: String) {
        val sessionId = renameSessionId ?: return

        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    sessionService.renameTitle(sessionId, newTitle)
                }
                if (updated) {
                    refresh()
                    dismissRenameDialog()
                    onRenameComplete()
                } else {
                    errorMessage = LocalizationManager.getString("sessions.error.rename.failed")
                }
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "renaming session",
                    LocalizationManager.getString("sessions.error.renaming"),
                )
            }
        }
    }

    /**
     * Export a session in the selected format.
     * Shows a styled export dialog to choose format and location.
     */
    fun exportSession(sessionId: String) {
        scope.launch {
            try {
                // Find the session to get the title for display in dialog
                val session = withContext(Dispatchers.IO) {
                    sessionService.getSessionById(sessionId)
                } ?: run {
                    errorMessage = LocalizationManager.getString("sessions.error.not.found")
                    return@launch
                }

                // Use simple default filename pattern instead of session title
                // to avoid long filenames that cause errors
                val timestamp = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"),
                )
                val defaultFilename = "chat_export_$timestamp"

                // Set state to show dialog (will update filename when format changes)
                exportSessionId = sessionId
                exportSessionTitle = session.title
                exportFormat = ExportFormat.MARKDOWN // Default format
                exportDefaultFilename = "$defaultFilename.${exportFormat.extension}"
                showExportDialog = true
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "preparing export",
                    "Failed to prepare export: ${e.message}",
                )
            }
        }
    }

    /**
     * Update the export format and refresh the default filename.
     */
    fun updateExportFormat(format: ExportFormat) {
        exportFormat = format
        // Update filename extension
        val baseFilename = exportDefaultFilename.substringBeforeLast('.')
        exportDefaultFilename = "$baseFilename.${format.extension}"
    }

    /**
     * Dismiss the export dialog.
     */
    fun dismissExportDialog() {
        showExportDialog = false
        exportSessionId = null
        exportSessionTitle = ""
        exportDefaultFilename = ""
        exportFormat = ExportFormat.MARKDOWN // Reset to default
    }

    /**
     * Execute the actual export after user confirms file location.
     */
    fun executeExport(fullPath: String) {
        scope.launch {
            try {
                // Validate filename length (most filesystems have 255 character limit for filename)
                val file = File(fullPath)
                val filename = file.name

                if (filename.length > MAX_FILENAME_LENGTH) {
                    errorMessage = LocalizationManager.getString("file.name.too.long")
                    return@launch
                }

                // Check if file exists
                val fileExists = withContext(Dispatchers.IO) {
                    file.exists()
                }

                if (fileExists) {
                    // Show overwrite confirmation
                    pendingExportPath = fullPath
                    showOverwriteConfirmation = true
                } else {
                    // File doesn't exist, proceed with export
                    performExport(fullPath)
                }
            } catch (e: FileNotFoundException) {
                // Handle filename too long error specifically
                if (e.message?.contains("File name too long") == true) {
                    errorMessage = LocalizationManager.getString("file.name.too.long")
                } else {
                    errorMessage = ExceptionHandler.handle(
                        e,
                        "exporting session",
                        "Failed to export session: ${e.message}",
                    )
                }
            } catch (e: Exception) {
                errorMessage = ExceptionHandler.handle(
                    e,
                    "exporting session",
                    "Failed to export session: ${e.message}",
                )
            }
        }
    }

    /**
     * Confirm overwrite and proceed with export.
     */
    fun confirmOverwrite() {
        val path = pendingExportPath
        showOverwriteConfirmation = false
        pendingExportPath = null

        if (path != null) {
            scope.launch {
                performExport(path)
            }
        }
    }

    /**
     * Cancel overwrite confirmation.
     */
    fun cancelOverwrite() {
        showOverwriteConfirmation = false
        pendingExportPath = null
    }

    /**
     * Perform the actual export operation.
     */
    private suspend fun performExport(fullPath: String) {
        try {
            val result = withContext(Dispatchers.IO) {
                val exporterService = ChatSessionExporterService()
                when (exportFormat) {
                    ExportFormat.MARKDOWN -> exporterService.exportToMarkdown(exportSessionId!!, fullPath)
                    ExportFormat.JSON -> exporterService.exportToJson(exportSessionId!!, fullPath)
                    ExportFormat.HTML -> exporterService.exportToHtml(exportSessionId!!, fullPath)
                }
            }

            result.onSuccess {
                successMessage = "${LocalizationManager.getString("session.export.success.message")} $fullPath"
                dismissExportDialog()
            }

            result.onFailure { error ->
                errorMessage = ExceptionHandler.handle(
                    error,
                    "exporting session",
                    "Failed to export session: ${error.message}",
                )
            }
        } catch (e: Exception) {
            errorMessage = ExceptionHandler.handle(
                e,
                "exporting session",
                "Failed to export session: ${e.message}",
            )
        }
    }

    /**
     * Dismiss the success message.
     */
    fun dismissSuccessMessage() {
        successMessage = null
    }
}
