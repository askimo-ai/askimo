/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import io.askimo.ui.common.ui.util.FileDialogUtils
import java.util.UUID

/**
 * Helper class for browsing and adding knowledge sources (folders, files, URLs).
 *
 * Both the folder and file pickers intentionally use [JFileChooser] (non-native) rather than
 * the platform-native dialog — this is a deliberate UX exception for the "Add Reference"
 * workflow to provide consistent approve-button labels and richer filter controls.
 */
class KnowledgeSourceBrowser(
    private val browseFolderTitle: String,
    private val browseFileTitle: String,
    /** Label for the confirm button in the folder chooser dialog (e.g. "Select"). */
    private val folderApproveButtonText: String = "Select",
    /** Hint text shown inside the folder chooser dialog to guide navigation. */
    private val folderNavigationHint: String? = null,
    /** Label for the confirm button in the file chooser dialog (e.g. "Select"). */
    private val fileApproveButtonText: String = "Select",
) {
    /**
     * Browse for a folder and return a [KnowledgeSourceItem.Folder] if selected.
     *
     * Uses [FileDialogUtils.pickFolderPathWithChooser] so the confirm button reads
     * the localized [folderApproveButtonText] instead of the platform default "Open".
     */
    suspend fun browseForFolder(): KnowledgeSourceItem.Folder? {
        val folderPath = FileDialogUtils.pickFolderPathWithChooser(
            title = browseFolderTitle,
            approveButtonText = folderApproveButtonText,
            navigationHint = folderNavigationHint
                ?: "Tip: Double-click a folder to open it, then click \u201c$folderApproveButtonText\u201d to choose it",
        )
        return folderPath?.let {
            KnowledgeSourceItem.Folder(
                id = UUID.randomUUID().toString(),
                path = it,
                isValid = validateFolder(it),
            )
        }
    }

    /**
     * Browse for files using a [JFileChooser]-based picker (non-native, consistent with
     * [browseForFolder]) and return a list of [KnowledgeSourceItem.File].
     */
    suspend fun browseForFiles(): List<KnowledgeSourceItem.File> = FileDialogUtils.pickFilePathsWithChooser(
        title = browseFileTitle,
        approveButtonText = fileApproveButtonText,
    ).map { path ->
        KnowledgeSourceItem.File(
            id = UUID.randomUUID().toString(),
            path = path,
            isValid = validateFile(path),
        )
    }

    /**
     * Handle adding sources based on type info.
     * Returns a list of new sources to add (empty list for URL type, which needs separate dialog).
     */
    suspend fun handleAddSource(
        typeInfo: KnowledgeSourceItem.TypeInfo,
        onShowUrlDialog: () -> Unit,
    ): List<KnowledgeSourceItem> = when (typeInfo) {
        KnowledgeSourceItem.TypeInfo.FOLDER -> browseForFolder()?.let { listOf(it) } ?: emptyList()

        KnowledgeSourceItem.TypeInfo.FILE -> browseForFiles()

        KnowledgeSourceItem.TypeInfo.URL -> {
            onShowUrlDialog()
            emptyList()
        }
    }
}
