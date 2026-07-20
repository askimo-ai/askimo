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
 * Uses the native OS file dialogs for a consistent, modern experience.
 */
class KnowledgeSourceBrowser(
    private val browseFileTitle: String,
) {
    /**
     * Browse for a folder using the native OS dialog and return a [KnowledgeSourceItem.Folder] if selected.
     */
    suspend fun browseForFolder(): KnowledgeSourceItem.Folder? {
        val folderPath = FileDialogUtils.pickFolderPath()
        return folderPath?.let {
            KnowledgeSourceItem.Folder(
                id = UUID.randomUUID().toString(),
                path = it,
                isValid = validateFolder(it),
            )
        }
    }

    /**
     * Browse for files using the native OS dialog and return a list of [KnowledgeSourceItem.File].
     */
    suspend fun browseForFiles(): List<KnowledgeSourceItem.File> = FileDialogUtils.pickFilePaths(
        title = browseFileTitle,
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
