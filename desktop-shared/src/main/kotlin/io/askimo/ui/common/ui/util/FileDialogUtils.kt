/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui.util

import io.askimo.core.chat.util.FileTypeSupport
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.path
import java.io.File

/**
 * Utilities for working with file/folder pickers via FileKit.
 *
 * FileKit uses the native OS dialog on every platform:
 *  - macOS  → NSOpenPanel / NSSavePanel
 *  - Windows → IFileOpenDialog / IFileSaveDialog
 *  - Linux   → GTK file chooser (xdg-portal / AWT fallback)
 *
 * All helpers are `suspend` functions — call them from a coroutine scope
 * (e.g. `LaunchedEffect`, a `CoroutineScope` button click handler, or `rememberCoroutineScope`).
 */
object FileDialogUtils {

    /**
     * Opens a native folder picker and returns the selected directory path, or null if cancelled.
     */
    suspend fun pickFolderPath(title: String): String? = FileKit.openDirectoryPicker()?.path

    /**
     * Opens a native single-file picker filtered to [extensions] and returns the
     * selected file path, or null if cancelled.
     * Pass null to [extensions] to allow all supported file types.
     */
    suspend fun pickFilePath(
        title: String,
        extensions: List<String>? = FileTypeSupport.supportedExtensions(),
    ): String? = FileKit.openFilePicker(
        type = FileKitType.File(extensions ?: emptyList()),
    )?.path

    /**
     * Opens a native multi-file picker filtered to [extensions] and returns a list
     * of selected file paths (empty if cancelled).
     * Pass null to [extensions] to allow all supported file types.
     */
    suspend fun pickFilePaths(
        title: String,
        extensions: List<String>? = FileTypeSupport.supportedExtensions(),
    ): List<String> = FileKit.openFilePicker(
        type = FileKitType.File(extensions ?: emptyList()),
        mode = FileKitMode.Multiple(),
    )?.map { it.path } ?: emptyList()

    /**
     * Opens a native image-file picker and returns the selected file path, or null if cancelled.
     */
    suspend fun pickImagePath(title: String): String? = FileKit.openFilePicker(
        type = FileKitType.Image,
    )?.path

    /**
     * Opens a native save dialog and returns the target [File], or null if cancelled.
     *
     * @param suggestedName Default file name shown in the dialog (without extension).
     * @param extension     File extension without dot, e.g. `"pdf"`.
     */
    suspend fun pickSavePath(
        suggestedName: String,
        extension: String,
        title: String = "",
    ): File? = FileKit.openFileSaver(
        suggestedName = suggestedName,
        extension = extension,
        dialogSettings = FileKitDialogSettings.createDefault(),
    )?.let { File(it.path) }
}
