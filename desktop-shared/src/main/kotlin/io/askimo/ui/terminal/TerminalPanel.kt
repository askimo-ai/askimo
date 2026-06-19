/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import io.askimo.core.util.AskimoHome
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.delay
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicScrollBarUI
import kotlin.ranges.coerceIn

/**
 * Terminal panel component with tabbed interface using JediTerm.
 * Supports multiple terminal tabs, creating new tabs, and closing tabs.
 */
@Composable
fun terminalPanel(
    onClose: () -> Unit,
    panelHeight: Dp = 300.dp,
    onHeightChange: (Dp) -> Unit = {},
    pendingCommand: PendingTerminalCommand? = null,
    modifier: Modifier = Modifier,
) {
    // Get theme colors from Material3
    val backgroundColor = MaterialTheme.colorScheme.surface
    val foregroundColor = MaterialTheme.colorScheme.onSurface

    // Askimo workspace — used as the working directory for all terminal tabs
    val workspaceDir = remember {
        AskimoHome.base().resolve("workspace").toFile().also { it.mkdirs() }.absolutePath
    }

    // Terminal tabs state
    val terminalTabs = remember { mutableStateListOf<TerminalTab>() }
    var selectedTabId by remember { mutableStateOf<String?>(null) }
    var renamingTabId by remember { mutableStateOf<String?>(null) }
    var renameDialogText by remember { mutableStateOf("") }

    // Initialize with one terminal tab
    if (terminalTabs.isEmpty()) {
        val initialTab = TerminalTab(
            id = UUID.randomUUID().toString(),
            title = "Terminal 1",
            widget = createTerminalWidget(
                backgroundColor = Color(backgroundColor.toArgb()),
                foregroundColor = Color(foregroundColor.toArgb()),
                workDir = workspaceDir,
            ),
        )
        terminalTabs.add(initialTab)
        selectedTabId = initialTab.id
    }

    // Ensure selectedTabId is valid
    if (selectedTabId == null || terminalTabs.none { it.id == selectedTabId }) {
        selectedTabId = terminalTabs.firstOrNull()?.id
    }

    // Inject pending command into the active terminal tab.
    // Wait until the terminal is fully started and the connector is ready
    // before writing — avoids double-injection from composition racing with PTY startup.
    LaunchedEffect(pendingCommand) {
        if (pendingCommand == null) return@LaunchedEffect

        val activeTab = terminalTabs.firstOrNull { it.id == selectedTabId }
            ?: terminalTabs.firstOrNull()
            ?: return@LaunchedEffect

        // Wait for the terminal to finish its PTY startup before writing
        delay(2000)

        val connector = activeTab.widget.ttyConnector ?: return@LaunchedEffect
        connector.write(pendingCommand.code)
        if (pendingCommand.couldExecute) connector.write("\n")
    }

    // Calculate the selected index for ScrollableTabRow
    val selectedTabIndex = terminalTabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)

    // Get density for pixel to Dp conversion
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Track accumulated drag for smooth resize
    var accumulatedDragY by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Calculate current display height (original + accumulated drag)
    val currentDisplayHeight = if (isDragging) {
        val dragDp = with(density) { accumulatedDragY.toDp() }
        (panelHeight - dragDp).coerceIn(150.dp, 800.dp)
    } else {
        panelHeight
    }

    // Start all terminals when created
    DisposableEffect(terminalTabs.size) {
        terminalTabs.forEach { tab ->
            if (!tab.isStarted) {
                tab.widget.start()
                tab.isStarted = true
            }
        }
        onDispose {
            // Cleanup handled individually when tabs are removed
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(currentDisplayHeight)
            .background(backgroundColor)
            .clipToBounds(), // Clip to bounds to prevent white overflow
    ) {
        // Draggable resize handle (top edge)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            accumulatedDragY = 0f
                        },
                        onDragEnd = {
                            // Apply the accumulated drag to the actual height state
                            if (accumulatedDragY != 0f) {
                                val dragDp = with(density) { accumulatedDragY.toDp() }
                                val newHeight = (panelHeight - dragDp).coerceIn(150.dp, 800.dp)
                                onHeightChange(newHeight)
                            }
                            isDragging = false
                            accumulatedDragY = 0f
                        },
                        onDragCancel = {
                            isDragging = false
                            accumulatedDragY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Accumulate the drag movement
                            accumulatedDragY += dragAmount.y
                        },
                    )
                },
        )

        // Terminal header with tabs
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tabs - use key to force recomposition when tabs change
                key(terminalTabs.size, selectedTabIndex) {
                    SecondaryScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        edgePadding = 0.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        divider = {},
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        terminalTabs.forEachIndexed { index, tab ->
                            var showContextMenu by remember { mutableStateOf(false) }

                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabId = tab.id },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                    ) {
                                        // Tab title — single click switches tab, double click renames
                                        @OptIn(ExperimentalFoundationApi::class)
                                        Text(
                                            text = tab.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.combinedClickable(
                                                onClick = { selectedTabId = tab.id },
                                                onDoubleClick = {
                                                    renamingTabId = tab.id
                                                    renameDialogText = tab.title
                                                },
                                                onLongClick = { showContextMenu = true },
                                            ),
                                        )

                                        // Context menu
                                        DropdownMenu(
                                            expanded = showContextMenu,
                                            onDismissRequest = { showContextMenu = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                onClick = {
                                                    renamingTabId = tab.id
                                                    renameDialogText = tab.title
                                                    showContextMenu = false
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Close") },
                                                onClick = {
                                                    closeTab(
                                                        tabs = terminalTabs,
                                                        tabId = tab.id,
                                                        selectedTabId = selectedTabId,
                                                        onSelectedTabIdChange = { newId -> selectedTabId = newId },
                                                        backgroundColor = Color(backgroundColor.toArgb()),
                                                        foregroundColor = Color(foregroundColor.toArgb()),
                                                        workDir = workspaceDir,
                                                    )
                                                    showContextMenu = false
                                                },
                                            )
                                        }

                                        // Close button (always visible)
                                        IconButton(
                                            onClick = {
                                                closeTab(
                                                    tabs = terminalTabs,
                                                    tabId = tab.id,
                                                    selectedTabId = selectedTabId,
                                                    onSelectedTabIdChange = { newId -> selectedTabId = newId },
                                                    backgroundColor = Color(backgroundColor.toArgb()),
                                                    foregroundColor = Color(foregroundColor.toArgb()),
                                                    workDir = workspaceDir,
                                                )
                                            },
                                            modifier = Modifier
                                                .size(24.dp)
                                                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close tab",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }

                // Add new terminal button
                IconButton(
                    onClick = {
                        val newTab = TerminalTab(
                            id = UUID.randomUUID().toString(),
                            title = "Terminal ${terminalTabs.size + 1}",
                            widget = createTerminalWidget(
                                backgroundColor = Color(backgroundColor.toArgb()),
                                foregroundColor = Color(foregroundColor.toArgb()),
                                workDir = workspaceDir,
                            ),
                        )
                        terminalTabs.add(newTab)
                        selectedTabId = newTab.id
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Terminal",
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Close panel button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Terminal Panel",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Terminal content - show selected tab
        // Add an opaque background Box to prevent any white from showing through
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(backgroundColor), // This ensures dark background always shows
        ) {
            if (terminalTabs.isNotEmpty() && selectedTabIndex < terminalTabs.size) {
                val currentTab = terminalTabs[selectedTabIndex]
                // Use key() to force SwingPanel recreation when tab changes
                key(currentTab.id) {
                    SwingPanel(
                        factory = { currentTab.widget.component },
                        background = backgroundColor,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // Rename dialog
    if (renamingTabId != null) {
        Dialog(
            onDismissRequest = {
                renamingTabId = null
            },
        ) {
            Surface(
                modifier = Modifier
                    .width(300.dp),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    Text(
                        text = "Rename Terminal",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val focusRequester = remember { FocusRequester() }

                    TextField(
                        value = renameDialogText,
                        onValueChange = { renameDialogText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        label = { Text("Terminal Name") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        secondaryButton(
                            onClick = { renamingTabId = null },
                        ) {
                            Text("Cancel")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        primaryButton(
                            onClick = {
                                if (renameDialogText.isNotBlank()) {
                                    val tabIndex = terminalTabs.indexOfFirst { it.id == renamingTabId }
                                    if (tabIndex != -1) {
                                        terminalTabs[tabIndex] = terminalTabs[tabIndex].copy(title = renameDialogText.trim())
                                    }
                                }
                                renamingTabId = null
                            },
                        ) {
                            Text("Rename")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Closes a terminal tab. If it's the last tab, creates a new one.
 */
private fun closeTab(
    tabs: MutableList<TerminalTab>,
    tabId: String,
    selectedTabId: String?,
    onSelectedTabIdChange: (String) -> Unit,
    backgroundColor: Color,
    foregroundColor: Color,
    workDir: String,
) {
    val indexToClose = tabs.indexOfFirst { it.id == tabId }
    if (indexToClose == -1) return

    val tabToClose = tabs[indexToClose]

    // Close the terminal widget
    try {
        tabToClose.widget.close()
    } catch (_: Exception) {
        // Ignore errors during close
    }

    // If this is the last tab, create a new one before removing
    if (tabs.size == 1) {
        val newTab = TerminalTab(
            id = UUID.randomUUID().toString(),
            title = "Terminal 1",
            widget = createTerminalWidget(
                backgroundColor = backgroundColor,
                foregroundColor = foregroundColor,
                workDir = workDir,
            ),
            isStarted = false,
        )
        // Start the new terminal immediately so it can accept input
        newTab.widget.start()
        newTab.isStarted = true

        tabs.add(newTab)
        tabs.removeAt(indexToClose)
        onSelectedTabIdChange(newTab.id)
    } else {
        // Remove the tab
        tabs.removeAt(indexToClose)

        // If we closed the selected tab, select another one
        if (tabId == selectedTabId) {
            // Select the tab at the same position, or the last tab if we closed the last one
            val newSelectedTab = if (indexToClose < tabs.size) {
                tabs[indexToClose]
            } else {
                tabs.last()
            }
            onSelectedTabIdChange(newSelectedTab.id)
        }
    }
}

/**
 * Represents a terminal tab with its widget and metadata.
 */
private data class TerminalTab(
    val id: String,
    val title: String,
    val widget: JediTermWidget,
    var isStarted: Boolean = false,
)

/**
 * Creates and configures a JediTerm terminal widget with theme colors.
 */
private fun createTerminalWidget(
    backgroundColor: Color,
    foregroundColor: Color,
    workDir: String,
): JediTermWidget {
    val settingsProvider = object : DefaultSettingsProvider() {
        override fun getDefaultBackground(): TerminalColor = TerminalColor {
            com.jediterm.core.Color(backgroundColor.red, backgroundColor.green, backgroundColor.blue)
        }

        override fun getDefaultForeground(): TerminalColor = TerminalColor {
            com.jediterm.core.Color(foregroundColor.red, foregroundColor.green, foregroundColor.blue)
        }

        // Enable antialiasing for better text rendering
        override fun useAntialiasing(): Boolean = true

        // CRITICAL: Disable copy on select - this prevents automatic selection behavior
        override fun copyOnSelect(): Boolean = false

        // CRITICAL: Disable paste on middle mouse button
        override fun pasteOnMiddleMouseClick(): Boolean = false

        // Don't fill character backgrounds to prevent grid effect
        override fun shouldFillCharacterBackgroundIncludingLineSpacing(): Boolean = false

        // Set proper buffer size to prevent out of bounds errors
        override fun getBufferMaxLinesCount(): Int = 10000
    }

    return JediTermWidget(settingsProvider).apply {
        val ttyConnector = createTtyConnector(workDir)
        this.ttyConnector = ttyConnector

        // Force initial size to prevent buffer errors
        // This ensures the terminal buffer is properly initialized before rendering
        try {
            terminalPanel?.setSize(800, 600)

            // Apply theme colors to scrollbar
            terminalPanel?.let { panel ->
                applyThemeToScrollBar(panel, backgroundColor, foregroundColor)
            }
        } catch (_: Exception) {
            // Ignore if setting size fails
        }
    }
}

/**
 * Applies theme colors to JediTerm's scrollbar and other UI components.
 */
private fun applyThemeToScrollBar(panel: JComponent, bgColor: Color, fgColor: Color) {
    try {
        // Find and style the vertical scrollbar
        for (component in panel.components) {
            if (component is JScrollBar) {
                component.apply {
                    background = bgColor
                    foreground = fgColor

                    // Custom scrollbar UI with theme colors
                    setUI(object : BasicScrollBarUI() {
                        override fun configureScrollBarColors() {
                            // Thumb color (the draggable part)
                            thumbColor = Color(
                                (fgColor.red * 0.3 + bgColor.red * 0.7).toInt(),
                                (fgColor.green * 0.3 + bgColor.green * 0.7).toInt(),
                                (fgColor.blue * 0.3 + bgColor.blue * 0.7).toInt(),
                            )
                            thumbDarkShadowColor = thumbColor
                            thumbHighlightColor = thumbColor
                            thumbLightShadowColor = thumbColor

                            // Track color (the background)
                            trackColor = bgColor
                            trackHighlightColor = bgColor
                        }

                        override fun createDecreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }

                        override fun createIncreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                    })
                }
            }
        }

        // Calculate outline/border color (similar to AppComponents.outlineVariant)
        val outlineColor = Color(
            (fgColor.red * 0.15 + bgColor.red * 0.85).toInt(),
            (fgColor.green * 0.15 + bgColor.green * 0.85).toInt(),
            (fgColor.blue * 0.15 + bgColor.blue * 0.85).toInt(),
        )

        // Calculate surfaceVariant color (for text field background)
        val surfaceVariantColor = Color(
            (fgColor.red * 0.08 + bgColor.red * 0.92).toInt(),
            (fgColor.green * 0.08 + bgColor.green * 0.92).toInt(),
            (fgColor.blue * 0.08 + bgColor.blue * 0.92).toInt(),
        )

        // Calculate button background (subtle)
        val buttonBgColor = Color(
            (fgColor.red * 0.12 + bgColor.red * 0.88).toInt(),
            (fgColor.green * 0.12 + bgColor.green * 0.88).toInt(),
            (fgColor.blue * 0.12 + bgColor.blue * 0.88).toInt(),
        )

        // Set UIManager defaults for find dialog and other components
        UIManager.put("TextField.background", surfaceVariantColor)
        UIManager.put("TextField.foreground", fgColor)
        UIManager.put("TextField.caretForeground", fgColor)
        UIManager.put("TextField.border", BorderFactory.createLineBorder(outlineColor, 1))

        UIManager.put("Panel.background", bgColor)
        UIManager.put("Panel.foreground", fgColor)

        UIManager.put("Button.background", buttonBgColor)
        UIManager.put("Button.foreground", fgColor)

        // Checkbox styling for "Ignore Case" checkbox in find dialog
        UIManager.put("CheckBox.background", bgColor)
        UIManager.put("CheckBox.foreground", fgColor)
        UIManager.put("CheckBox.select", fgColor) // Checkmark color
        UIManager.put("CheckBox.border", BorderFactory.createLineBorder(outlineColor, 1))

        // Label styling (for "Find:" label)
        UIManager.put("Label.background", bgColor)
        UIManager.put("Label.foreground", fgColor)
    } catch (_: Exception) {
        // Ignore if theming fails
    }
}

/**
 * Creates a PTY connector for the terminal.
 * Automatically detects the shell based on the operating system.
 */
private fun createTtyConnector(workDir: String): TtyConnector {
    val shell = getShellCommand()

    val envs = mutableMapOf<String, String>()
    envs.putAll(System.getenv())
    envs["TERM"] = "xterm-256color"

    // Fix DEVELOPER_DIR for macOS to avoid xcrun errors
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        // Only set DEVELOPER_DIR if it's not already set or points to invalid path
        val currentDevDir = envs["DEVELOPER_DIR"]
        if (currentDevDir == null || !File(currentDevDir).exists()) {
            // Find valid developer directory by checking common paths directly
            val validPath = findXcodeDeveloperPath()
            if (validPath != null) {
                envs["DEVELOPER_DIR"] = validPath
            } else {
                // If no valid path found, remove DEVELOPER_DIR to avoid confusing error messages
                envs.remove("DEVELOPER_DIR")
            }
        }
    }

    val process = PtyProcessBuilder()
        .setCommand(arrayOf(shell))
        .setDirectory(workDir)
        .setEnvironment(envs)
        .start()

    return PtyTtyConnector(process)
}

/**
 * Finds the Xcode developer path on macOS by checking common locations.
 * Does NOT execute xcode-select to avoid triggering xcrun errors.
 */
private fun findXcodeDeveloperPath(): String? {
    // Check common paths in order of preference
    val commonPaths = listOf(
        "/Library/Developer/CommandLineTools", // Most common - Command Line Tools
        "/Applications/Xcode.app/Contents/Developer", // Full Xcode installation
        "/Applications/Xcode-beta.app/Contents/Developer", // Beta Xcode
    )

    return commonPaths.firstOrNull { path ->
        val dir = File(path)
        dir.exists() && dir.isDirectory
    }
}

/**
 * Determines the appropriate shell command based on the operating system.
 */
private fun getShellCommand(): String {
    val os = System.getProperty("os.name").lowercase()

    return when {
        os.contains("win") -> {
            // Try PowerShell first, fall back to cmd
            val pwsh = System.getenv("SystemRoot")?.let { "$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" }
            if (pwsh != null && File(pwsh).exists()) {
                pwsh
            } else {
                "cmd.exe"
            }
        }

        os.contains("mac") -> {
            // macOS: prefer zsh (default since Catalina)
            System.getenv("SHELL") ?: "/bin/zsh"
        }

        else -> {
            // Linux/Unix: use user's default shell
            System.getenv("SHELL") ?: "/bin/bash"
        }
    }
}

/**
 * A command waiting to be injected into the active terminal tab.
 *
 * @param code          The script/command text to send.
 * @param couldExecute  When true the terminal submits immediately (appends \n).
 *                      When false the command is only pasted so the user can review it.
 */
data class PendingTerminalCommand(
    val code: String,
    val couldExecute: Boolean = false,
)

/**
 * Wrapper for PtyProcess to implement TtyConnector interface.
 */
private class PtyTtyConnector(private val process: PtyProcess) : TtyConnector {

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val bytes = ByteArray(length)
        val bytesRead = process.inputStream.read(bytes, 0, length)
        if (bytesRead <= 0) return bytesRead

        val str = String(bytes, 0, bytesRead, StandardCharsets.UTF_8)
        str.toCharArray(buf, offset, 0, minOf(str.length, length))
        return minOf(str.length, length)
    }

    override fun write(bytes: ByteArray) {
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override fun write(string: String) {
        write(string.toByteArray(StandardCharsets.UTF_8))
    }

    override fun isConnected(): Boolean = process.isAlive

    override fun waitFor(): Int = process.waitFor()

    override fun close() {
        process.destroy()
    }

    override fun getName(): String = "Local Terminal"

    override fun ready(): Boolean = true

    @Suppress("OVERRIDE_DEPRECATION")
    override fun resize(termWinSize: Dimension) {
        if (process.isAlive) {
            try {
                process.winSize = WinSize(termWinSize.width, termWinSize.height)
            } catch (_: Exception) {
                // Ignore resize errors
            }
        }
    }
}
