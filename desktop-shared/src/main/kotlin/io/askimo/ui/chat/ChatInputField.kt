/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.util.FileContentExtractor
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolRegistry
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ReasoningEffort
import io.askimo.core.util.TimeUtil
import io.askimo.core.util.formatFileSize
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.util.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import java.awt.Cursor
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.collections.minus
import kotlin.collections.plus
import kotlin.math.ceil
import kotlin.ranges.coerceIn

private val log = currentFileLogger()

/**
 * Reusable chat input field component with attachment support.
 *
 * @param inputText Current input text value
 * @param onInputTextChange Callback when input text changes
 * @param attachments List of file attachments
 * @param onAttachmentsChange Callback when attachments list changes
 * @param onSendMessage Callback when send button is clicked, receives the current CreationMode
 * @param isLoading Whether the chat is currently loading
 * @param isThinking Whether the AI is thinking
 * @param onStopResponse Callback to stop the current response
 * @param errorMessage Optional error message to display
 * @param editingMessage Optional message being edited
 * @param onCancelEdit Callback to cancel edit mode
 * @param sessionId Optional session ID for file attachments
 * @param placeholder Optional placeholder text
 * @param modifier Optional modifier for the component
 */
@Composable
fun chatInputField(
    inputText: TextFieldValue,
    onInputTextChange: (TextFieldValue) -> Unit,
    attachments: List<FileAttachmentDTO>,
    onAttachmentsChange: (List<FileAttachmentDTO>) -> Unit,
    onSendMessage: (CreationMode) -> Unit,
    isLoading: Boolean = false,
    isThinking: Boolean = false,
    onStopResponse: () -> Unit = {},
    errorMessage: String? = null,
    editingMessage: ChatMessageDTO? = null,
    onCancelEdit: () -> Unit = {},
    sessionId: String? = null,
    placeholder: String = stringResource("chat.input.placeholder"),
    onEnabledServerIdsChange: ((Set<String>) -> Unit)? = null,
    onNavigateToMcpSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val inputFocusRequester = remember { FocusRequester() }

    // State for creation mode (Chat, Image, etc.)
    // Reset to Chat mode when switching to a different session
    var creationMode by remember(sessionId) { mutableStateOf<CreationMode>(CreationMode.Chat) }

    // Read current provider + model from AppContext — reactive to provider/model switches
    val appParams = AppContext.getInstance().params
    val resolvedProvider: ModelProvider? = appParams.currentProvider.takeIf { it != ModelProvider.UNKNOWN }
    val currentModel: String = appParams.model

    // Whether the current model supports extended reasoning — read from capabilities cache
    val supportsReasoning = remember(resolvedProvider, currentModel) {
        if (resolvedProvider != null && currentModel.isNotBlank()) {
            ModelCapabilitiesCache.supportsThinking(resolvedProvider, currentModel)
        } else {
            false
        }
    }

    // Reasoning effort — state created once; synced from cache via LaunchedEffect when
    // provider/model changes so the remember block itself never re-runs on dropdown clicks.
    var reasoningEffort by remember { mutableStateOf(ReasoningEffort.DEFAULT) }
    var showReasoningDropdown by remember { mutableStateOf(false) }

    // Sync from cache whenever the active provider/model changes (not on every recomposition).
    LaunchedEffect(resolvedProvider, currentModel) {
        reasoningEffort = if (resolvedProvider != null && currentModel.isNotBlank()) {
            ModelCapabilitiesCache.getReasoningLevel(resolvedProvider, currentModel)
        } else {
            ReasoningEffort.DEFAULT
        }
    }

    // Persist user-selected effort back to cache (only fires when the value actually changes).
    LaunchedEffect(reasoningEffort) {
        if (supportsReasoning && resolvedProvider != null && currentModel.isNotBlank()) {
            ModelCapabilitiesCache.setReasoningLevel(resolvedProvider, currentModel, reasoningEffort)
        }
    }

    // Session-scoped set of server IDs enabled by the user via the tools popup.
    // Empty by default — all tools are off until the user opts in.
    var enabledServerIds by remember(sessionId) { mutableStateOf(emptySet<String>()) }

    // Notify caller whenever the user changes the enabled server selection.
    LaunchedEffect(enabledServerIds) {
        onEnabledServerIdsChange?.invoke(enabledServerIds)
    }

    // State for resizable text field.
    // Minimum height accounts for: controls row (44dp) + 1 line of text (24dp) + OutlinedTextField vertical padding (36dp) = 104dp.
    // This value is used as the initial/reset height so the container is always correctly sized
    // even before any LaunchedEffect has had a chance to run (e.g. immediately after a session switch).
    val inlineControlsBottomPadding = 44.dp
    val defaultTextFieldHeight = 104.dp
    var textFieldHeight by remember(sessionId) { mutableStateOf(defaultTextFieldHeight) }
    var manuallyResized by remember(sessionId) { mutableStateOf(false) }

    // Track the actual width of the text field for accurate wrapping calculation
    var textFieldWidthPx by remember { mutableStateOf(0f) }

    // Density for dp to px conversion
    val density = LocalDensity.current

    // Calculate desired height based on text content and actual width
    // This accounts for both explicit newlines AND text wrapping
    val estimatedLineCount = remember(inputText.text, textFieldWidthPx) {
        if (inputText.text.isEmpty()) return@remember 1

        // Calculate average character width dynamically
        // Approximate: bodyMedium font is ~14sp, average char width is ~0.6 of font size
        val fontSizePx = with(density) { 14.sp.toPx() }
        val avgCharWidthPx = fontSizePx * 0.6f

        // Calculate characters that fit per line based on actual text field width
        // Subtract standard text field horizontal padding.
        val textFieldPaddingPx = with(density) { 48.dp.toPx() }
        val availableWidthPx = textFieldWidthPx - textFieldPaddingPx

        val charsPerLine = if (availableWidthPx > 0 && avgCharWidthPx > 0) {
            (availableWidthPx / avgCharWidthPx).toInt().coerceAtLeast(20) // Minimum 20 chars
        } else {
            80 // Fallback if width not measured yet
        }

        // Count total lines accounting for wrapping
        // Split by explicit newlines first
        var totalLines = 0
        inputText.text.split('\n').forEach { line ->
            if (line.isEmpty()) {
                totalLines += 1 // Empty line still takes space
            } else {
                // Calculate how many visual lines this text line will take
                val wrappedLines = ceil(line.length.toFloat() / charsPerLine).toInt()
                totalLines += wrappedLines
            }
        }

        totalLines.coerceAtLeast(1)
    }

    // Approximate height per line (can be adjusted based on your text style)
    val lineHeight = 24.dp
    val padding = 36.dp // Top and bottom padding for the text field
    // Reserve extra vertical room so bottom-left inline controls do not overlap typed text.
    val calculatedHeight = (lineHeight * estimatedLineCount) + padding + inlineControlsBottomPadding
    val maxVisibleInputLines = ((textFieldHeight - inlineControlsBottomPadding - 1.dp - 32.dp) / lineHeight)
        .toInt()
        .coerceAtLeast(1)

    // Reset height and creation mode to default when message is sent (detected by empty input text)
    LaunchedEffect(inputText.text) {
        if (inputText.text.isEmpty()) {
            textFieldHeight = defaultTextFieldHeight
            manuallyResized = false
            creationMode = CreationMode.Chat
        }
    }

    // Focus input field when entering edit mode
    LaunchedEffect(editingMessage) {
        if (editingMessage != null) {
            inputFocusRequester.requestFocus()
        }
    }

    val selectFileTitle = stringResource("chat.select.file")
    val scope = rememberCoroutineScope()
    val openFileDialog = {
        scope.launch {
            val paths = FileDialogUtils.pickFilePaths(selectFileTitle)
            if (paths.isNotEmpty()) {
                try {
                    val maxFileSizeBytes = AppConfig.indexing.maxFileBytes
                    val files = paths.map { File(it) }
                    val invalidFiles = files.filter { it.length() > maxFileSizeBytes }

                    if (invalidFiles.isNotEmpty()) {
                        val firstInvalidFile = invalidFiles.first()
                        EventBus.post(
                            AppErrorEvent(
                                title = "File Too Large",
                                message = "File '${firstInvalidFile.name}' is too large (${formatFileSize(firstInvalidFile.length())}). Maximum allowed size is ${formatFileSize(maxFileSizeBytes)}.",
                            ),
                        )
                    } else {
                        val newAttachments = files.map { file ->
                            FileAttachmentDTO(
                                id = UUID.randomUUID().toString(),
                                messageId = "",
                                sessionId = sessionId ?: "",
                                fileName = file.name,
                                mimeType = file.extension,
                                size = file.length(),
                                createdAt = Instant.now(),
                                content = null,
                                filePath = file.absolutePath,
                            )
                        }
                        onAttachmentsChange(attachments + newAttachments)
                    }
                } catch (e: Exception) {
                    log.error("Error adding file attachments: ${e.message}", e)
                }
            }
        }
        Unit
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { keyEvent ->
                val shortcut = KeyMapManager.handleKeyEvent(keyEvent)
                when (shortcut) {
                    KeyMapManager.AppShortcut.ATTACH_FILE -> {
                        if (!isLoading) {
                            openFileDialog()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            },
    ) {
        // Edit mode banner
        if (editingMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.small),
                colors = AppComponents.bannerCardColors(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = editingMessage.timestamp?.let { timestamp ->
                                val formattedTime = TimeUtil.formatDisplay(timestamp)
                                stringResource("message.editing.banner.from", formattedTime)
                            } ?: stringResource("message.editing.banner"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    IconButton(
                        onClick = onCancelEdit,
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource("message.cancel.edit"),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // File attachments display
        if (attachments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                attachments.forEach { attachment ->
                    fileAttachmentItem(
                        attachment = attachment,
                        onRemove = {
                            onAttachmentsChange(attachments - attachment)
                        },
                    )
                }
            }
        }

        // Wrap in BoxWithConstraints to get max available height
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val maxAvailableHeight = maxHeight
            val maxTextFieldHeight = (maxAvailableHeight * 0.5f).coerceAtLeast(defaultTextFieldHeight)

            // Auto-calculate height if not manually resized
            LaunchedEffect(sessionId, calculatedHeight, manuallyResized) {
                if (!manuallyResized) {
                    textFieldHeight = calculatedHeight.coerceIn(defaultTextFieldHeight, maxTextFieldHeight)
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        // Resize handle — scoped to the text field column only so it
                        // doesn't overlap the send button.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .pointerHoverIcon(
                                    PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)),
                                )
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        val newHeight = textFieldHeight - dragAmount.toDp()
                                        textFieldHeight = newHeight.coerceIn(defaultTextFieldHeight, maxTextFieldHeight)
                                        manuallyResized = true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Visual grip indicator
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(2.dp),
                                    ),
                            )
                        }

                        // Custom two-section input container:
                        // ┌─────────────────────────────────────────┐
                        // │  Text area  (weight 1f, scrollable)     │
                        // ├─────────────────────────────────────────┤
                        // │  Controls row  (fixed height)           │
                        // └─────────────────────────────────────────┘
                        // Text can never overlap the controls because they are structurally separate.
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        val containerBorderColor = when {
                            errorMessage != null -> MaterialTheme.colorScheme.error
                            isFocused -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        val containerBorderWidth = if (isFocused || errorMessage != null) 2.dp else 1.dp

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(textFieldHeight)
                                .border(containerBorderWidth, containerBorderColor, RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp)),
                        ) {
                            // ── Text area ──────────────────────────────────────────────────
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = onInputTextChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .focusRequester(inputFocusRequester)
                                    .onGloballyPositioned { coordinates ->
                                        textFieldWidthPx = coordinates.size.width.toFloat()
                                    }
                                    .onPreviewKeyEvent { keyEvent ->
                                        val shortcut = KeyMapManager.handleKeyEvent(keyEvent)
                                        when (shortcut) {
                                            KeyMapManager.AppShortcut.NEW_LINE -> {
                                                val cursorPosition = inputText.selection.start
                                                val textBeforeCursor = inputText.text.substring(0, cursorPosition)
                                                val textAfterCursor = inputText.text.substring(cursorPosition)
                                                val newText = textBeforeCursor + "\n" + textAfterCursor
                                                val newCursorPosition = cursorPosition + 1
                                                onInputTextChange(
                                                    TextFieldValue(
                                                        text = newText,
                                                        selection = TextRange(newCursorPosition),
                                                    ),
                                                )
                                                true
                                            }

                                            KeyMapManager.AppShortcut.SEND_MESSAGE -> {
                                                if (inputText.text.isNotBlank() && !isLoading && !isThinking) {
                                                    onSendMessage(creationMode)
                                                }
                                                true
                                            }

                                            else -> false
                                        }
                                    },
                                placeholder = { Text(placeholder) },
                                maxLines = maxVisibleInputLines,
                                interactionSource = interactionSource,
                                // Border is provided by the outer container; keep OutlinedTextField's own border transparent.
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    errorBorderColor = Color.Transparent,
                                    focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    cursorColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            )

                            // ── Separator ──────────────────────────────────────────────────
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surface,
                                thickness = 0.5.dp,
                            )

                            // ── Controls row ───────────────────────────────────────────────
                            // Layout: [attach | image | tools | image-mode-chip] <spacer> [reasoning chip]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(inlineControlsBottomPadding)
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // ── Left: action controls ──────────────────────────────────
                                themedTooltip(
                                    text = stringResource("chat.attach.file", Platform.modifierKey),
                                ) {
                                    IconButton(
                                        onClick = openFileDialog,
                                        enabled = !isLoading,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            Icons.Default.AttachFile,
                                            contentDescription = stringResource("chat.attach.file.menu"),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(2.dp))

                                themedTooltip(
                                    text = stringResource("chat.create.image.menu"),
                                ) {
                                    IconButton(
                                        onClick = {
                                            creationMode = if (creationMode is CreationMode.Image) {
                                                CreationMode.Chat
                                            } else {
                                                CreationMode.Image
                                            }
                                        },
                                        enabled = !isLoading,
                                        colors = if (creationMode is CreationMode.Image) {
                                            AppComponents.primaryIconButtonColors()
                                        } else {
                                            IconButtonDefaults.iconButtonColors()
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = stringResource("chat.create.image.menu"),
                                            tint = if (creationMode is CreationMode.Image) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(2.dp))

                                toolsIndicatorButton(
                                    sessionId = sessionId,
                                    isLoading = isLoading,
                                    enabledServerIds = enabledServerIds,
                                    onEnabledServerIdsChange = { updated ->
                                        enabledServerIds = updated
                                    },
                                    onNavigateToMcpSettings = onNavigateToMcpSettings,
                                    iconSize = 28.dp,
                                )

                                // Image mode chip — contextually tied to the image toggle above
                                if (creationMode is CreationMode.Image) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        tonalElevation = 2.dp,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = stringResource("chat.create.image.mode"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource("chat.create.image.mode.cancel"),
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clickable { creationMode = CreationMode.Chat }
                                                    .pointerHoverIcon(PointerIcon.Hand),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                }

                                // ── Push reasoning chip to the far right ───────────────────
                                Spacer(modifier = Modifier.weight(1f))

                                // Reasoning effort chip — only shown for models that support it;
                                // positioned far right as a model-setting indicator.
                                if (supportsReasoning) {
                                    Box {
                                        themedTooltip(text = stringResource("chat.reasoning.effort.tooltip")) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                tonalElevation = 2.dp,
                                                modifier = Modifier
                                                    .clickable(
                                                        enabled = !isLoading,
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                        onClick = { showReasoningDropdown = true },
                                                    )
                                                    .pointerHoverIcon(PointerIcon.Hand),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(
                                                        start = Spacing.small,
                                                        end = Spacing.small,
                                                        top = 3.dp,
                                                        bottom = 3.dp,
                                                    ),
                                                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = stringResource("chat.reasoning.effort.label") + ":",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                                    )
                                                    Text(
                                                        text = reasoningEffort.value.replaceFirstChar { it.uppercase() },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Default.KeyboardArrowDown,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(12.dp),
                                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                }
                                            }
                                        }

                                        dropdownMenu(
                                            expanded = showReasoningDropdown,
                                            onDismissRequest = { showReasoningDropdown = false },
                                        ) {
                                            ReasoningEffort.entries.forEach { effort ->
                                                val label = when (effort) {
                                                    ReasoningEffort.LOW -> stringResource("chat.reasoning.effort.low")
                                                    ReasoningEffort.MEDIUM -> stringResource("chat.reasoning.effort.medium")
                                                    ReasoningEffort.HIGH -> stringResource("chat.reasoning.effort.high")
                                                }
                                                val description = when (effort) {
                                                    ReasoningEffort.LOW -> stringResource("chat.reasoning.effort.low.description")
                                                    ReasoningEffort.MEDIUM -> stringResource("chat.reasoning.effort.medium.description")
                                                    ReasoningEffort.HIGH -> stringResource("chat.reasoning.effort.high.description")
                                                }
                                                themedTooltip(text = description) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = label,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = if (effort == reasoningEffort) FontWeight.Bold else FontWeight.Normal,
                                                            )
                                                        },
                                                        trailingIcon = null,
                                                        onClick = {
                                                            reasoningEffort = effort
                                                            showReasoningDropdown = false
                                                        },
                                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                                        colors = AppComponents.menuItemColors(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Error text shown below the container
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(contentAlignment = Alignment.Center) {
                        if (isLoading || isThinking) {
                            IconButton(
                                onClick = onStopResponse,
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Chat stop",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            themedTooltip(
                                text = if (editingMessage != null) {
                                    stringResource("message.update.regenerate")
                                } else {
                                    stringResource("message.send")
                                },
                            ) {
                                IconButton(
                                    onClick = { onSendMessage(creationMode) },
                                    enabled = inputText.text.isNotBlank(),
                                    colors = AppComponents.primaryIconButtonColors(),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Icon(
                                        if (editingMessage != null) Icons.Default.Edit else Icons.AutoMirrored.Filled.Send,
                                        contentDescription = if (editingMessage != null) {
                                            stringResource("message.update.regenerate")
                                        } else {
                                            stringResource("message.send")
                                        },
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tools indicator button that shows available MCP servers and tools.
 * Displays tool count badge and opens a popup with server/tool hierarchy.
 *
 * @param sessionId Optional session ID to determine if this is a project chat
 * @param isLoading Whether the chat is currently loading
 */
@Composable
private fun toolsIndicatorButton(
    sessionId: String?,
    isLoading: Boolean,
    enabledServerIds: Set<String>,
    onEnabledServerIdsChange: (Set<String>) -> Unit,
    onNavigateToMcpSettings: (() -> Unit)? = null,
    iconSize: Dp = 36.dp,
) {
    var showToolsPopup by remember { mutableStateOf(false) }
    var mcpServers by remember { mutableStateOf<List<McpServerInfo>>(emptyList()) }
    var isLoadingServers by remember { mutableStateOf(false) }

    // Get services
    val globalMcpService = remember {
        try {
            KoinJavaComponent.get<McpInstanceService>(
                McpInstanceService::class.java,
            )
        } catch (_: Exception) {
            null
        }
    }
    // Load MCP servers eagerly once the composable is attached, and cache for the lifetime
    // of this composable. Re-opening the popup is instant — no loading spinner shown again.
    LaunchedEffect(Unit) {
        // Skip if already loaded for this projectId
        if (mcpServers.isNotEmpty()) return@LaunchedEffect

        isLoadingServers = true
        val servers = mutableListOf<McpServerInfo>()

        // Add built-in Askimo tools first
        val builtInTools = ToolRegistry.getIntentBased()
        if (builtInTools.isNotEmpty()) {
            servers.add(
                McpServerInfo(
                    name = "Askimo Built-in Tools",
                    id = ToolRegistry.BUILTIN_SERVER_ID,
                    isGlobal = true,
                    isBuiltIn = true,
                    tools = builtInTools,
                ),
            )
        }

        mcpServers = withContext(Dispatchers.IO) {
            // Load global MCP servers with their tools
            globalMcpService?.getInstances()?.filter { it.enabled }?.forEach { instance ->
                val tools = globalMcpService.listTools(instance.id)
                    .getOrElse { e ->
                        log.error("Error loading tools for global server ${instance.name}", e)
                        EventBus.emit(
                            AppErrorEvent(
                                title = LocalizationManager.getString("error.app.title"),
                                message = LocalizationManager.getString(
                                    "error.app.message",
                                    e.message ?: instance.name,
                                ),
                                cause = e,
                            ),
                        )
                        emptyList()
                    }
                servers.add(McpServerInfo(instance.name, instance.id, isGlobal = true, tools = tools))
            }

            servers
        }
        isLoadingServers = false
    }

    val totalServers = mcpServers.size
    val enabledServers = mcpServers.count { it.id in enabledServerIds }
    val hasDisabled = enabledServers < totalServers && totalServers > 0

    Box {
        themedTooltip(
            text = if (hasDisabled) {
                stringResource("chat.tools.button.disabled", (totalServers - enabledServers).toString())
            } else {
                stringResource("chat.tools.button")
            },
        ) {
            IconButton(
                onClick = { showToolsPopup = true },
                enabled = !isLoading,
                modifier = Modifier
                    .size(iconSize)
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                BadgedBox(
                    badge = {
                        if (totalServers > 0) {
                            Badge(
                                containerColor = if (hasDisabled) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                contentColor = if (hasDisabled) {
                                    MaterialTheme.colorScheme.onSecondary
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                            ) {
                                Text(
                                    text = enabledServers.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = stringResource("chat.tools.button"),
                        tint = if (hasDisabled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (showToolsPopup) {
            Popup(
                onDismissRequest = { showToolsPopup = false },
                alignment = Alignment.BottomStart,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 300.dp, max = 420.dp)
                        .heightIn(max = 400.dp),
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource("chat.tools.popup.title"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        HorizontalDivider()

                        // Content
                        when {
                            isLoadingServers -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource("chat.tools.popup.loading"))
                                }
                            }

                            mcpServers.isEmpty() -> {
                                Text(
                                    text = stringResource("chat.tools.popup.no.servers.global"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            else -> {
                                // List MCP servers
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    mcpServers.forEach { server ->
                                        mcpServerItem(
                                            server = server,
                                            isEnabled = server.id in enabledServerIds,
                                            onToggle = {
                                                onEnabledServerIdsChange(
                                                    if (server.id in enabledServerIds) {
                                                        enabledServerIds - server.id
                                                    } else {
                                                        enabledServerIds + server.id
                                                    },
                                                )
                                            },
                                            onCloseAll = { showToolsPopup = false },
                                        )
                                    }
                                }
                            }
                        }

                        // Footer link to MCP settings
                        if (onNavigateToMcpSettings != null) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = {
                                        showToolsPopup = false
                                        onNavigateToMcpSettings()
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource("chat.tools.popup.manage"),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Data class representing MCP server or built-in tools information.
 */
private data class McpServerInfo(
    val name: String,
    val id: String,
    val isGlobal: Boolean,
    val isBuiltIn: Boolean = false,
    val tools: List<ToolConfig> = emptyList(),
    val isLoadingTools: Boolean = false,
    val toolsError: String? = null,
)

/**
 * Displays a single MCP server item with a toggle checkbox and a dropdown submenu for its tools.
 *
 * The card has two separate interaction zones:
 * - Checkbox (left): toggles the server enabled/disabled for this session.
 * - Chevron / card body (right): opens the tools submenu (read-only tool list).
 */
@Composable
private fun mcpServerItem(
    server: McpServerInfo,
    isEnabled: Boolean = true,
    onToggle: () -> Unit = {},
    onCloseAll: () -> Unit = {},
) {
    var showToolsSubmenu by remember { mutableStateOf(false) }

    val contentAlpha = if (isEnabled) 1f else 0.4f

    Box {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = AppComponents.surfaceVariantCardColors(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = server.tools.isNotEmpty(),
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showToolsSubmenu = true },
                    )
                    .pointerHoverIcon(
                        if (server.tools.isNotEmpty()) PointerIcon.Hand else PointerIcon.Default,
                    )
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier
                        .size(36.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* Handled by onCheckedChange */ },
                        ),
                )

                // Server info
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                        if (server.tools.isNotEmpty()) {
                            Badge(
                                containerColor = if (server.isBuiltIn) {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f * contentAlpha)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f * contentAlpha)
                                },
                                contentColor = if (server.isBuiltIn) {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = contentAlpha)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                                },
                            ) {
                                Text(
                                    text = server.tools.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Text(
                        text = if (server.isBuiltIn) {
                            stringResource("chat.tools.server.scope.builtin")
                        } else if (server.isGlobal) {
                            stringResource("chat.tools.server.scope.global")
                        } else {
                            stringResource("chat.tools.server.scope.project")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    )
                }

                // Submenu indicator — only shown when there are tools to browse
                if (server.tools.isNotEmpty()) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Submenu dropdown with tools
        dropdownMenu(
            expanded = showToolsSubmenu,
            onDismissRequest = {
                showToolsSubmenu = false
                onCloseAll()
            },
            offset = DpOffset(x = 200.dp, y = (-48).dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 350.dp, max = 450.dp)
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                server.tools.forEachIndexed { index, tool ->
                    val toolName = tool.specification.name()
                    val toolDescription =
                        tool.specification.description() ?: stringResource("chat.tools.tool.no.description")
                    val rowBackground = if (index % 2 == 0) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }

                    Box(modifier = Modifier.background(rowBackground)) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                ) {
                                    Text(
                                        text = "${index + 1}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(20.dp),
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = toolName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = toolDescription,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            onClick = { /* Tools are read-only for now */ },
                            colors = AppComponents.menuItemColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun fileAttachmentItem(
    attachment: FileAttachmentDTO,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var previewContent by remember { mutableStateOf<String?>(null) }
    var isLoadingPreview by remember { mutableStateOf(false) }

    // Determine if the file is previewable as text — delegate to FileContentExtractor
    val isTextFile = FileContentExtractor.isTextFile(attachment.fileName)

    LaunchedEffect(expanded) {
        if (expanded && isTextFile && previewContent == null && !isLoadingPreview) {
            isLoadingPreview = true
            previewContent = withContext(Dispatchers.IO) {
                try {
                    val file = attachment.filePath?.let { File(it) }
                    if (file != null && file.exists() && file.length() < 512 * 1024) {
                        // Read up to 200 lines for preview
                        file.bufferedReader().use { reader ->
                            val lines = reader.readLines()
                            val preview = lines.take(200).joinToString("\n")
                            if (lines.size > 200) {
                                "$preview\n… (${LocalizationManager.getString("chat.attachment.preview.more.lines", lines.size - 200)})"
                            } else {
                                preview
                            }
                        }
                    } else if (file != null && !file.exists()) {
                        LocalizationManager.getString("chat.attachment.preview.file.not.found")
                    } else {
                        LocalizationManager.getString("chat.attachment.preview.too.large")
                    }
                } catch (e: Exception) {
                    LocalizationManager.getString("chat.attachment.preview.cannot.read", e.message ?: "")
                }
            }
            isLoadingPreview = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.surfaceVariantCardColors(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isTextFile) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { expanded = !expanded },
                            ).pointerHoverIcon(PointerIcon.Hand)
                        } else {
                            Modifier
                        },
                    )
                    .padding(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Column {
                        Text(
                            text = attachment.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatFileSize(attachment.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isTextFile) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse preview" else "Expand preview",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(24.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource("chat.attachment.remove"),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Inline preview panel
            if (expanded && isTextFile) {
                HorizontalDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    when {
                        isLoadingPreview -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    text = stringResource("chat.attachment.preview.loading"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        previewContent != null -> {
                            val scrollState = rememberScrollState()
                            Box(
                                modifier = Modifier.verticalScroll(scrollState),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = previewContent!!,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
