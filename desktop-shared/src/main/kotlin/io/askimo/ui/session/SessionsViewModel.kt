/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.service.ChatSessionExporterService
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.db.Pageable
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.SessionCreatedEvent
import io.askimo.core.event.internal.SessionTitleUpdatedEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.ui.common.export.ExportFormat
import io.askimo.ui.util.ErrorHandler
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

/**
 * ViewModel for managing sessions view state and operations.
 */
class SessionsViewModel(
    private val scope: CoroutineScope,
    private val sessionService: ChatSessionService,
    private val sessionManager: SessionManager,
    private val onCreateNewSession: () -> String,
    private val onRenameComplete: () -> Unit = {}, // Callback when rename completes
) {
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

    var totalSessionCount by mutableStateOf(0)
        private set

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
                val (sessions, total) = withContext(Dispatchers.IO) {
                    val sessions = sessionService.getSessionsWithoutProject(MAX_SIDEBAR_SESSIONS)
                    val total = sessionService.countSessionsWithoutProject()
                    sessions to total
                }
                recentSessions = sessions
                totalSessionCount = total

                log.debug("Loaded ${sessions.size} sessions without projects (showing ${sessions.size} in sidebar, total: $total)")
            } catch (e: Exception) {
                log.error("Failed to load recent sessions: ${e.message}", e)
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
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(
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
            delay(300)
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
                errorMessage = ErrorHandler.getUserFriendlyError(
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
                errorMessage = ErrorHandler.getUserFriendlyError(
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
                errorMessage = ErrorHandler.getUserFriendlyError(
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
                errorMessage = ErrorHandler.getUserFriendlyError(
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
                errorMessage = ErrorHandler.getUserFriendlyError(
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
                errorMessage = ErrorHandler.getUserFriendlyError(
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
                    errorMessage = ErrorHandler.getUserFriendlyError(
                        e,
                        "exporting session",
                        "Failed to export session: ${e.message}",
                    )
                }
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(
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
                errorMessage = ErrorHandler.getUserFriendlyError(
                    error,
                    "exporting session",
                    "Failed to export session: ${error.message}",
                )
            }
        } catch (e: Exception) {
            errorMessage = ErrorHandler.getUserFriendlyError(
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
