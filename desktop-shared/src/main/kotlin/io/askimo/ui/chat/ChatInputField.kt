/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.DirectiveScope
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.service.ChatDirectiveService
import io.askimo.core.chat.util.FileContentExtractor
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.core.event.internal.ImageCapabilityDetectedEvent
import io.askimo.core.event.internal.ReasoningEffortChangedEvent
import io.askimo.core.event.internal.ThinkingSupportDetectedEvent
import io.askimo.core.event.internal.ToolSupportDetectedEvent
import io.askimo.core.event.system.ShellErrorEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolRegistry
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.memory.MemoryPressureLevel
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ReasoningEffort
import io.askimo.core.util.TimeUtil
import io.askimo.core.util.formatFileSize
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.keymap.onImeAwarePreviewKeyEvent
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.LocalFontScale
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedRichTooltip
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.session.manageDirectivesDialog
import io.askimo.ui.session.newDirectiveDialog
import io.askimo.ui.util.Platform
import io.askimo.ui.voice.AudioRecorder
import io.askimo.ui.voice.MicrophoneUnavailableException
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import io.askimo.ui.voice.VoiceServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlin.math.sqrt
import kotlin.ranges.coerceIn
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = currentFileLogger()

/**
 * Auto-stop cap for voice dictation. Not a Whisper file-size concern (25MB/request allows
 * ~13 minutes at our 16kHz/16-bit mono capture rate) — purely a UX/safety bound so a
 * forgotten/stuck recording can't run indefinitely — see the auto-stop
 * `LaunchedEffect` inside `chatInputField`.
 */
private const val MAX_VOICE_RECORDING_SECONDS = 120

/**
 * States for the 🎤 voice-input button in the controls row.
 * IDLE → RECORDING (mic open, capturing) → TRANSCRIBING (STT in flight) → IDLE.
 */
private enum class VoiceRecordingState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
}

/**
 * Live bar-style waveform driven by [samples] (each in `0f..1f`), most-recent last.
 * Used next to the 🎤 button while [VoiceRecordingState.RECORDING] to give immediate visual
 * feedback that the microphone is actually picking up sound, not just that recording started.
 */
@Composable
private fun voiceWaveform(
    samples: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (samples.isEmpty()) return@Canvas
        val barWidth = size.width / samples.size
        val gap = barWidth * 0.3f
        samples.forEachIndexed { index, level ->
            val barHeight = (level * size.height).coerceIn(2f, size.height)
            drawRect(
                color = color,
                topLeft = Offset(index * barWidth, (size.height - barHeight) / 2f),
                size = Size((barWidth - gap).coerceAtLeast(1f), barHeight),
            )
        }
    }
}

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
    selectedDirective: String? = null,
    onToggleDirective: (String?) -> Unit = {},
    isProjectSession: Boolean = false,
    onWebSearchInRagChange: ((Boolean) -> Unit)? = null,
    memoryPressureLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL,
    memoryUtilization: Float = 0f,
    memoryUsedTokens: Int = 0,
    memoryBudgetTokens: Int = 0,
    isCompressing: Boolean = false,
    isContextSizeLearned: Boolean = false,
    onCompressMemory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val inputFocusRequester = remember { FocusRequester() }

    // State for creation mode (Chat, Image, etc.)
    // Reset to Chat mode when switching to a different session
    var creationMode by remember(sessionId) { mutableStateOf<CreationMode>(CreationMode.Chat) }

    // Read current provider + model from AppContext — reactive to provider/model switches
    val appParams = AppContext.getInstance().params
    val resolvedProvider: ModelProvider? = appParams.activeProviderType.takeIf { it != ModelProvider.UNKNOWN }
    val currentModel: String = appParams.model

    // Whether the current model supports extended reasoning.
    var supportsReasoning by remember(resolvedProvider, currentModel) {
        mutableStateOf(
            if (resolvedProvider != null && currentModel.isNotBlank()) {
                ModelCapabilitiesCache.supportsThinking(resolvedProvider, currentModel)
            } else {
                false
            },
        )
    }

    // Whether the current model supports tool calling.
    // Only disabled when the probe has run AND returned false — unknown (null) stays enabled.
    var modelSupportsTools by remember(resolvedProvider, currentModel) {
        mutableStateOf(
            if (resolvedProvider != null && currentModel.isNotBlank() &&
                ModelCapabilitiesCache.hasTestedToolSupport(resolvedProvider, currentModel)
            ) {
                ModelCapabilitiesCache.supportsTools(resolvedProvider, currentModel)
            } else {
                true // Not yet probed — optimistically keep enabled
            },
        )
    }

    // Listen for the probe result and update when it arrives for the active model.
    LaunchedEffect(resolvedProvider, currentModel) {
        EventBus.internalEvents.collect { event ->
            if (event is ThinkingSupportDetectedEvent &&
                event.provider == resolvedProvider &&
                event.model == currentModel
            ) {
                supportsReasoning = event.supportsThinking
            }
        }
    }

    // Listen for tool support probe result and update reactively.
    LaunchedEffect(resolvedProvider, currentModel) {
        EventBus.internalEvents.collect { event ->
            if (event is ToolSupportDetectedEvent &&
                event.provider == resolvedProvider &&
                event.model == currentModel
            ) {
                modelSupportsTools = event.supportsTools
            }
        }
    }

    // Whether the current model supports native image generation (in-chat).
    var supportsNativeImageGeneration by remember(resolvedProvider, currentModel) {
        mutableStateOf(
            if (resolvedProvider != null && currentModel.isNotBlank()) {
                ModelCapabilitiesCache.supportsImage(resolvedProvider, currentModel)
            } else {
                false
            },
        )
    }

    val configuredImageModel = appParams.activeInstance?.settings?.imageModel.orEmpty()
    val hasConfiguredImageModel = configuredImageModel.isNotBlank()
    val providerNotSetLabel = stringResource("provider.not.set")
    val missingImageModelTitle = stringResource("chat.image.model.missing.title")

    // Listen for image capability probe results and update when it arrives for the active model.
    LaunchedEffect(resolvedProvider, currentModel) {
        EventBus.internalEvents.collect { event ->
            if (event is ImageCapabilityDetectedEvent &&
                event.provider == resolvedProvider &&
                event.model == currentModel
            ) {
                supportsNativeImageGeneration = event.supportsNativeImage
            }
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
            EventBus.emit(
                ReasoningEffortChangedEvent(
                    provider = resolvedProvider,
                    model = currentModel,
                    newEffort = reasoningEffort,
                ),
            )
        }
    }

    // Session-scoped set of server IDs enabled by the user via the tools popup.
    // Empty by default — all tools are off until the user opts in.
    var enabledServerIds by remember(sessionId) { mutableStateOf(emptySet<String>()) }

    // Session-scoped web-search-in-RAG toggle. Resets when the session changes.
    var webSearchInRag by remember(sessionId) { mutableStateOf(false) }

    // Cache web-search enabled flag — AppConfig.webSearch hits the OS keychain on every
    // call (SecureKeyManager.retrieveSecretKey). Read off the UI thread so the first
    // composition is never blocked (avoids navigation lag into ProjectView).
    var webSearchEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        webSearchEnabled = withContext(Dispatchers.IO) { AppConfig.webSearch.enabled }
    }

    // ── Voice input (🎤) ─────────────────────────────────────────────────────
    // Cache the enabled flag the same way as webSearchEnabled above — AppConfig.voice
    // also resolves a key from the OS keychain, so keep it off the UI thread.
    // Hidden entirely when disabled (default) — zero UI impact for existing users.
    var voiceInputEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        voiceInputEnabled = withContext(Dispatchers.IO) { AppConfig.voice.enabled }
    }
    var voiceRecordingState by remember { mutableStateOf(VoiceRecordingState.IDLE) }
    val audioRecorder = remember { AudioRecorder() }
    val voiceErrorTitle = stringResource("chat.voice.error.title")

    // Rolling window of recent mic amplitude samples (0f..1f), fed from AudioRecorder's
    // capture-thread level callback — drives the live waveform shown while RECORDING.
    val voiceWaveformSamples = remember { mutableStateListOf<Float>() }
    val maxWaveformSamples = 40

    // Ticking elapsed-seconds counter shown next to the waveform while RECORDING — also
    // drives the [MAX_VOICE_RECORDING_SECONDS] auto-stop below.
    var recordingElapsedSeconds by remember { mutableStateOf(0) }

    // Cancel any in-progress recording if this composable leaves the composition
    // (e.g. user navigates away mid-recording) to avoid leaking an open mic line.
    DisposableEffect(Unit) {
        onDispose {
            if (audioRecorder.isRecording) {
                Thread({ audioRecorder.cancel() }, "askimo-audio-recorder-dispose-cancel").apply {
                    isDaemon = true
                    start()
                }
            }
            Snapshot.withMutableSnapshot { voiceWaveformSamples.clear() }
        }
    }

    // Notify caller whenever the user changes the enabled server selection.
    LaunchedEffect(enabledServerIds) {
        onEnabledServerIdsChange?.invoke(enabledServerIds)
    }

    // Directives chip popup / dialog state
    var directivePopupExpanded by remember { mutableStateOf(false) }
    var showNewDirectiveDialog by remember { mutableStateOf(false) }
    var showManageDirectivesDialog by remember { mutableStateOf(false) }

    val directiveService = remember {
        KoinJavaComponent.get<ChatDirectiveService>(ChatDirectiveService::class.java)
    }
    var availableDirectives by remember { mutableStateOf<List<ChatDirective>>(emptyList()) }
    var defaultDirectiveId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        availableDirectives = directiveService.listAllDirectives()
        defaultDirectiveId = directiveService.getGlobalDefaultDirectiveId()
    }

    // State for resizable text field.
    val fontScale = LocalFontScale.current
    val inlineControlsBottomPadding = 44.dp
    val lineHeight = 24.dp * fontScale
    val padding = 36.dp
    val defaultTextFieldHeight = (padding + lineHeight + inlineControlsBottomPadding)
        .coerceAtLeast(104.dp)
    // Re-key on fontScale so the height resets when the user changes font size mid-session.
    var textFieldHeight by remember(sessionId, fontScale) { mutableStateOf(defaultTextFieldHeight) }
    var manuallyResized by remember(sessionId, fontScale) { mutableStateOf(false) }

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

    // Always reflects the latest recomposition's inputText — read via .value at the point the
    // transcript is applied below (not captured by direct closure over `inputText`), so edits the
    // user makes to the input field *while* transcription is in flight aren't overwritten by a
    // stale snapshot of the text as it existed when the coroutine was launched.
    val latestInputText by rememberUpdatedState(inputText)

    // Shared "stop recording, transcribe, insert text" logic — invoked both when the user
    // manually stops (toggleVoiceRecording below) and when the recorder is auto-stopped after
    // [MAX_VOICE_RECORDING_SECONDS] (see the LaunchedEffect right below toggleVoiceRecording).
    val stopRecordingAndTranscribe: () -> Unit = {
        voiceRecordingState = VoiceRecordingState.TRANSCRIBING
        Snapshot.withMutableSnapshot { voiceWaveformSamples.clear() }
        scope.launch {
            try {
                val wavBytes = withContext(Dispatchers.IO) { audioRecorder.stop() }
                val transcript = withContext(Dispatchers.IO) {
                    VoiceServiceRegistry.speechToText(AppConfig.voice)
                        .transcribe(wavBytes, VoiceAudioFormat.WAV)
                }
                if (transcript.isNotBlank()) {
                    val currentText = latestInputText.text
                    val newText = if (currentText.isBlank()) {
                        transcript
                    } else {
                        "$currentText $transcript"
                    }
                    onInputTextChange(
                        TextFieldValue(text = newText, selection = TextRange(newText.length)),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceServiceException) {
                EventBus.post(AppErrorEvent(title = voiceErrorTitle, message = e.message ?: "Voice transcription failed"))
            } catch (e: Exception) {
                EventBus.post(AppErrorEvent(title = voiceErrorTitle, message = e.message ?: "Voice recording failed"))
            } finally {
                voiceRecordingState = VoiceRecordingState.IDLE
            }
        }
    }

    // Shared toggle logic — invoked both by the 🎤 button's onClick and by the
    // Cmd/Ctrl+Shift+M keyboard shortcut (TOGGLE_VOICE_RECORDING) so behavior stays identical.
    val toggleVoiceRecording: () -> Unit = toggle@{
        if (!voiceInputEnabled || isLoading) return@toggle
        when (voiceRecordingState) {
            VoiceRecordingState.IDLE -> {
                try {
                    Snapshot.withMutableSnapshot { voiceWaveformSamples.clear() }
                    audioRecorder.start { level ->
                        // `level` is raw linear RMS (see PcmAudioEncoder.computeRmsLevel) — normal
                        // speech volume is far below full-scale, so raw values (~0.01-0.1) always
                        // collapse to the waveform's minimum bar height. Apply a perceptual sqrt
                        // curve + gain (like a real VU meter) so speech visibly moves the bars.
                        val boostedLevel = (sqrt(level.coerceIn(0f, 1f)) * 1.6f).coerceIn(0f, 1f)
                        Snapshot.withMutableSnapshot {
                            voiceWaveformSamples.add(boostedLevel)
                            if (voiceWaveformSamples.size > maxWaveformSamples) {
                                runCatching { voiceWaveformSamples.removeAt(0) }
                            }
                        }
                    }
                    voiceRecordingState = VoiceRecordingState.RECORDING
                } catch (e: MicrophoneUnavailableException) {
                    EventBus.post(AppErrorEvent(title = voiceErrorTitle, message = e.message ?: "Microphone unavailable"))
                }
            }

            VoiceRecordingState.RECORDING -> stopRecordingAndTranscribe()

            VoiceRecordingState.TRANSCRIBING -> {
                // Ignore triggers while a transcription request is in flight.
            }
        }
    }

    // Auto-stop safety cap — ticks recordingElapsedSeconds once per second while RECORDING and
    // triggers stopRecordingAndTranscribe() once [MAX_VOICE_RECORDING_SECONDS] is reached, so a
    // forgotten/stuck recording can't run indefinitely. See [MAX_VOICE_RECORDING_SECONDS] doc.
    LaunchedEffect(voiceRecordingState) {
        if (voiceRecordingState == VoiceRecordingState.RECORDING) {
            val startedAt = System.currentTimeMillis()
            recordingElapsedSeconds = 0
            while (voiceRecordingState == VoiceRecordingState.RECORDING) {
                delay(1.seconds)
                if (voiceRecordingState != VoiceRecordingState.RECORDING) break
                recordingElapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                if (recordingElapsedSeconds >= MAX_VOICE_RECORDING_SECONDS) {
                    stopRecordingAndTranscribe()
                    break
                }
            }
        }
    }

    // ── Rotating placeholder hints ─────────────────────────────────────────────
    // Cycles through discoverability hints (web search, URL, attach) only while
    // the input is empty and the AI is not responding.
    val placeholderHints = listOf(
        placeholder,
        stringResource("chat.input.placeholder.hint.attach", Platform.modifierKey),
    )
    var placeholderIndex by remember { mutableStateOf(0) }
    val activePlaceholder = if (inputText.text.isEmpty() && !isLoading && !isThinking) {
        placeholderHints[placeholderIndex]
    } else {
        placeholder
    }
    LaunchedEffect(inputText.text, isLoading, isThinking) {
        if (inputText.text.isEmpty() && !isLoading && !isThinking) {
            while (true) {
                delay(5_000L.milliseconds)
                placeholderIndex = (placeholderIndex + 1) % placeholderHints.size
            }
        } else {
            placeholderIndex = 0
        }
    }
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
            .onImeAwarePreviewKeyEvent(inputText.composition) { keyEvent ->
                when (KeyMapManager.handleKeyEvent(keyEvent)) {
                    KeyMapManager.AppShortcut.ATTACH_FILE -> {
                        if (!isLoading) {
                            openFileDialog()
                            true
                        } else {
                            false
                        }
                    }

                    KeyMapManager.AppShortcut.TOGGLE_VOICE_RECORDING -> {
                        if (voiceInputEnabled && !isLoading) {
                            toggleVoiceRecording()
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
                            style = AppTextStyles.body,
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
                            .onImeAwarePreviewKeyEvent(inputText.composition) { keyEvent ->
                                when (KeyMapManager.handleKeyEvent(keyEvent)) {
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
                        placeholder = { Text(activePlaceholder) },
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

                    // ── Controls row ───────────────────────────────────────────────
                    // Layout: [attach | image | tools | directive | image-mode-chip] <spacer> [memory | reasoning chip | voice | send/stop]
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

                        // Image button — only show if model requires explicit toggle mode
                        // For multi-modal models (native image generation), hide this button
                        if (!supportsNativeImageGeneration) {
                            themedTooltip(
                                text = stringResource("chat.create.image.menu"),
                            ) {
                                IconButton(
                                    onClick = {
                                        if (creationMode is CreationMode.Image) {
                                            creationMode = CreationMode.Chat
                                        } else if (!hasConfiguredImageModel) {
                                            // Keep icon visible but prevent toggling into image mode
                                            creationMode = CreationMode.Chat
                                            val providerName = resolvedProvider?.name ?: providerNotSetLabel
                                            EventBus.post(
                                                AppErrorEvent(
                                                    title = missingImageModelTitle,
                                                    message = LocalizationManager.getString("chat.image.model.missing.message", providerName),
                                                ),
                                            )
                                        } else {
                                            creationMode = CreationMode.Image
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

                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        toolsIndicatorButton(
                            sessionId = sessionId,
                            isLoading = isLoading,
                            enabledServerIds = enabledServerIds,
                            onEnabledServerIdsChange = { updated ->
                                enabledServerIds = updated
                            },
                            onNavigateToMcpSettings = onNavigateToMcpSettings,
                            iconSize = 28.dp,
                            modelSupportsTools = modelSupportsTools,
                        )

                        // ── Directive chip — inline in controls row ─────────────────
                        Spacer(modifier = Modifier.width(6.dp))
                        directiveChip(
                            availableDirectives = availableDirectives,
                            selectedDirective = selectedDirective,
                            isLoading = isLoading,
                            onToggleDirective = onToggleDirective,
                            directivePopupExpanded = directivePopupExpanded,
                            onDirectivePopupExpandedChange = { directivePopupExpanded = it },
                            onShowNewDirectiveDialog = { showNewDirectiveDialog = true },
                            onShowManageDirectivesDialog = { showManageDirectivesDialog = true },
                        )

                        // ── Web search in RAG chip — only in project sessions when web search is configured ──
                        if (isProjectSession && webSearchEnabled) {
                            Spacer(modifier = Modifier.width(4.dp))
                            webSearchRagChip(
                                active = webSearchInRag,
                                isLoading = isLoading,
                                onToggle = {
                                    webSearchInRag = !webSearchInRag
                                    onWebSearchInRagChange?.invoke(webSearchInRag)
                                },
                            )
                        }

                        // Image mode chip — only show when user explicitly toggles to Image mode
                        // and the model requires explicit toggle (not native image generation)
                        if (creationMode is CreationMode.Image && !supportsNativeImageGeneration) {
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
                                        style = AppTextStyles.hint,
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

                        // ── Push right-side controls to the far right ─────────────
                        Spacer(modifier = Modifier.weight(1f))

                        // ── Memory usage chip — grouped with reasoning chip on far right ──
                        memoryUsageChip(
                            utilization = memoryUtilization,
                            pressureLevel = memoryPressureLevel,
                            isCompressing = isCompressing,
                            isContextSizeLearned = isContextSizeLearned,
                            onCompress = onCompressMemory,
                            usedTokens = memoryUsedTokens,
                            budgetTokens = memoryBudgetTokens,
                        )

                        // Reasoning effort chip — only shown for models that support it;
                        // positioned far right as a model-setting indicator.
                        if (supportsReasoning) {
                            Spacer(modifier = Modifier.width(8.dp))
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
                                                style = AppTextStyles.hint,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                            )
                                            Text(
                                                text = reasoningEffort.value.replaceFirstChar { it.uppercase() },
                                                style = AppTextStyles.hint,
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
                                            ReasoningEffort.OFF -> stringResource("chat.reasoning.effort.off")
                                            ReasoningEffort.LOW -> stringResource("chat.reasoning.effort.low")
                                            ReasoningEffort.MEDIUM -> stringResource("chat.reasoning.effort.medium")
                                            ReasoningEffort.HIGH -> stringResource("chat.reasoning.effort.high")
                                        }
                                        val description = when (effort) {
                                            ReasoningEffort.OFF -> stringResource("chat.reasoning.effort.off.description")
                                            ReasoningEffort.LOW -> stringResource("chat.reasoning.effort.low.description")
                                            ReasoningEffort.MEDIUM -> stringResource("chat.reasoning.effort.medium.description")
                                            ReasoningEffort.HIGH -> stringResource("chat.reasoning.effort.high.description")
                                        }
                                        themedTooltip(text = description) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = label,
                                                        style = AppTextStyles.body,
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

                        // ── Voice input (🎤) — hidden entirely when disabled in Settings > Voice ──
                        // Grouped near Send since it's an input-modality toggle (dictation),
                        // not a content-attachment action like Attach/Image on the left.
                        if (voiceInputEnabled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            val voiceShortcutHint = KeyMapManager.AppShortcut.TOGGLE_VOICE_RECORDING.getDisplayString()
                            val voiceTooltip = when (voiceRecordingState) {
                                VoiceRecordingState.IDLE -> stringResource("chat.voice.record", voiceShortcutHint)
                                VoiceRecordingState.RECORDING -> stringResource("chat.voice.recording.stop")
                                VoiceRecordingState.TRANSCRIBING -> stringResource("chat.voice.transcribing")
                            }

                            // Persistent (non-hover) "recording" status — a pulsing dot + live
                            // waveform reflecting mic input level, so it's obvious at a glance
                            // (not just via the tooltip/icon tint) that audio is being captured.
                            if (voiceRecordingState == VoiceRecordingState.RECORDING) {
                                val infiniteTransition = rememberInfiniteTransition(label = "voiceRecordingPulse")
                                val pulseAlpha by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.25f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 700),
                                        repeatMode = RepeatMode.Reverse,
                                    ),
                                    label = "voiceRecordingPulseAlpha",
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha),
                                            shape = CircleShape,
                                        ),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    // Ticks up each second and turns solid red in the final 10s
                                    // before MAX_VOICE_RECORDING_SECONDS auto-stops the recording —
                                    // the only user-facing signal of the cap, no separate popup.
                                    text = stringResource("chat.voice.recording.label.timed", recordingElapsedSeconds),
                                    style = AppTextStyles.caption,
                                    color = if (recordingElapsedSeconds >= MAX_VOICE_RECORDING_SECONDS - 10) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                    },
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                voiceWaveform(
                                    samples = voiceWaveformSamples,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.width(48.dp).height(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            themedTooltip(text = voiceTooltip) {
                                IconButton(
                                    onClick = toggleVoiceRecording,
                                    enabled = !isLoading && voiceRecordingState != VoiceRecordingState.TRANSCRIBING,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    if (voiceRecordingState == VoiceRecordingState.TRANSCRIBING) {
                                        AppComponents.loadingSpinner(size = 16.dp)
                                    } else {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = voiceTooltip,
                                            tint = if (voiceRecordingState == VoiceRecordingState.RECORDING) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // ── Send / Stop button — beside reasoning chip ──────────────
                        Spacer(modifier = Modifier.width(12.dp))
                        if (isLoading || isThinking) {
                            IconButton(
                                onClick = onStopResponse,
                                modifier = Modifier
                                    .size(28.dp)
                                    .pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Chat stop",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
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
                                    colors = IconButtonDefaults.filledIconButtonColors(),
                                    modifier = Modifier
                                        .size(28.dp)
                                        .pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Icon(
                                        if (editingMessage != null) Icons.Default.Edit else Icons.Default.ArrowUpward,
                                        contentDescription = if (editingMessage != null) {
                                            stringResource("message.update.regenerate")
                                        } else {
                                            stringResource("message.send")
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Error text shown below the container
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = AppTextStyles.errorText,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
            }
        }
    }

    // ── Directive dialogs ────────────────────────────────────────────────────
    // Owned here so the chip is their natural trigger point.
    if (showNewDirectiveDialog) {
        newDirectiveDialog(
            onDismiss = { showNewDirectiveDialog = false },
            onConfirm = { name, content, applyToCurrent ->
                val newDirective = directiveService.createDirective(name, content)
                availableDirectives = directiveService.listAllDirectives()
                if (applyToCurrent) onToggleDirective(newDirective.id)
                showNewDirectiveDialog = false
            },
        )
    }

    if (showManageDirectivesDialog) {
        manageDirectivesDialog(
            directives = availableDirectives,
            onDismiss = { showManageDirectivesDialog = false },
            onAdd = { name, content, applyToCurrent ->
                val newDirective = directiveService.createDirective(name, content)
                availableDirectives = directiveService.listAllDirectives()
                if (applyToCurrent) onToggleDirective(newDirective.id)
            },
            onUpdate = { id, newName, newContent ->
                directiveService.updateDirective(id, newName, newContent)
                availableDirectives = directiveService.listAllDirectives()
            },
            onDelete = { id ->
                directiveService.deleteDirective(id)
                if (selectedDirective == id) onToggleDirective(null)
                availableDirectives = directiveService.listAllDirectives()
            },
            onExport = { directiveService.exportToJson() },
            onImport = { json ->
                val result = directiveService.importFromJson(json)
                availableDirectives = directiveService.listAllDirectives()
                result
            },
            defaultDirectiveId = defaultDirectiveId,
            onSetDefault = { id ->
                directiveService.setGlobalDefaultDirectiveId(id)
                defaultDirectiveId = directiveService.getGlobalDefaultDirectiveId()
            },
        )
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
    modelSupportsTools: Boolean = false,
) {
    val scope = rememberCoroutineScope()
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
                val tools = globalMcpService.listActiveTools(instance.id)
                    .getOrElse { e ->
                        log.error("Error loading tools for global server ${instance.name}", e)
                        EventBus.emit(
                            ShellErrorEvent(
                                title = "MCP Tool Error",
                                errorMessage = LocalizationManager.getString(
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
    val hasAnyEnabled = enabledServers > 0
    val hasDisabled = enabledServers < totalServers && totalServers > 0

    Box {
        themedTooltip(
            text = when {
                !modelSupportsTools -> stringResource("chat.tools.button.model.no.support")
                hasDisabled -> stringResource("chat.tools.button.disabled", (totalServers - enabledServers).toString())
                else -> stringResource("chat.tools.button")
            },
        ) {
            // Inline chip: [🔧 3] or [🔧 3/5] — avoids all badge clipping issues
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when {
                    !modelSupportsTools -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    !hasAnyEnabled -> Color.Transparent
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
                tonalElevation = if (hasAnyEnabled && modelSupportsTools) 2.dp else 0.dp,
                modifier = Modifier
                    .height(iconSize)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        enabled = !isLoading && modelSupportsTools,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showToolsPopup = true },
                    )
                    .pointerHoverIcon(if (modelSupportsTools) PointerIcon.Hand else PointerIcon.Default),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = stringResource("chat.tools.button"),
                        tint = when {
                            !modelSupportsTools -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            !hasAnyEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            hasAnyEnabled -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    if (totalServers > 0 && modelSupportsTools) {
                        Text(
                            text = "$enabledServers/$totalServers",
                            style = AppTextStyles.hint,
                            color = if (hasDisabled) {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        )
                    }
                }
            }
        }

        if (showToolsPopup) {
            AppComponents.anchoredPopup(
                positionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(
                        x = anchorBounds.left,
                        y = anchorBounds.top - popupContentSize.height - 4,
                    )
                },
                onDismissRequest = { showToolsPopup = false },
                modifier = Modifier.widthIn(min = 300.dp, max = 420.dp),
            ) {
                // Header: title on left, settings icon button on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Spacing.medium,
                            end = Spacing.extraSmall,
                            top = Spacing.small,
                            bottom = Spacing.small,
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("chat.tools.popup.title"),
                        style = AppTextStyles.sectionTitle,
                    )
                    if (onNavigateToMcpSettings != null) {
                        themedTooltip(text = stringResource("chat.tools.popup.manage")) {
                            IconButton(
                                onClick = {
                                    showToolsPopup = false
                                    onNavigateToMcpSettings()
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = stringResource("chat.tools.popup.manage"),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Content
                when {
                    isLoadingServers -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppComponents.loadingSpinner(size = 24.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource("chat.tools.popup.loading"))
                        }
                    }

                    mcpServers.isEmpty() -> {
                        Text(
                            text = stringResource("chat.tools.popup.no.servers.global"),
                            style = AppTextStyles.bodySecondary,
                            modifier = Modifier.padding(
                                horizontal = Spacing.medium,
                                vertical = Spacing.medium,
                            ),
                        )
                    }

                    else -> {
                        // Server list — scrollable, capped at max height; popup expands freely
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.small),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
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
                                    onToolSelectedChange = { toolName, selected ->
                                        if (server.isBuiltIn) return@mcpServerItem

                                        // Keep popup state reactive to instant user toggles.
                                        mcpServers = mcpServers.map { currentServer ->
                                            if (currentServer.id == server.id) {
                                                currentServer.copy(
                                                    tools = currentServer.tools.map { currentTool ->
                                                        if (currentTool.specification.name() == toolName) {
                                                            currentTool.copy(selected = selected)
                                                        } else {
                                                            currentTool
                                                        }
                                                    },
                                                )
                                            } else {
                                                currentServer
                                            }
                                        }

                                        scope.launch(Dispatchers.IO) {
                                            globalMcpService?.setToolSelected(server.id, toolName, selected)
                                        }
                                    },
                                )
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
    onToolSelectedChange: (toolName: String, selected: Boolean) -> Unit = { _, _ -> },
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
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Checkbox(
                        checked = isEnabled,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier
                            .size(36.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            enabled = server.tools.isNotEmpty(),
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                // One-click flow: disabled -> enable + open tools, enabled -> open tools only.
                                if (!isEnabled) onToggle()
                                showToolsSubmenu = true
                            },
                        )
                        .pointerHoverIcon(
                            if (server.tools.isNotEmpty()) PointerIcon.Hand else PointerIcon.Default,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Server info
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = server.name,
                                style = AppTextStyles.body,
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
                                        style = AppTextStyles.hint,
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
                            style = AppTextStyles.caption,
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
        }

        // Submenu popup with tools — top-right of the MCP row to avoid overlapping server rows.
        if (showToolsSubmenu) {
            AppComponents.anchoredPopup(
                positionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val spacingPx = 8
                        val preferredRightX = anchorBounds.right + spacingPx
                        val fallbackLeftX = anchorBounds.left - popupContentSize.width - spacingPx
                        val x = when {
                            preferredRightX + popupContentSize.width <= windowSize.width -> preferredRightX
                            fallbackLeftX >= 0 -> fallbackLeftX
                            else -> (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        }

                        val preferredY = anchorBounds.top - spacingPx
                        val y = preferredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))

                        return IntOffset(x = x, y = y)
                    }
                },
                onDismissRequest = { showToolsSubmenu = false },
                modifier = Modifier.widthIn(min = 450.dp, max = 600.dp),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    server.tools.forEachIndexed { index, tool ->
                        val toolName = tool.specification.name()
                        val toolDescription =
                            tool.specification.description() ?: stringResource("chat.tools.tool.no.description")
                        val canToggleTool = !server.isBuiltIn && tool.enabled
                        val toolSelected = tool.enabled && tool.selected
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
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    ) {
                                        CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                            Checkbox(
                                                checked = toolSelected,
                                                enabled = canToggleTool,
                                                onCheckedChange = { checked ->
                                                    onToolSelectedChange(toolName, checked)
                                                },
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .pointerHoverIcon(
                                                        if (canToggleTool) PointerIcon.Hand else PointerIcon.Default,
                                                    ),
                                            )
                                        }
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = toolName,
                                                style = AppTextStyles.body,
                                            )
                                            themedTooltip(text = toolDescription, placement = TooltipPlacement.RIGHT) {
                                                Text(
                                                    text = toolDescription,
                                                    style = AppTextStyles.caption,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    if (canToggleTool) {
                                        onToolSelectedChange(toolName, !toolSelected)
                                    }
                                },
                                enabled = canToggleTool,
                                colors = AppComponents.menuItemColors(),
                            )
                        }
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
                            style = AppTextStyles.caption,
                        )
                        Text(
                            text = formatFileSize(attachment.size),
                            style = AppTextStyles.hint,
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
                                AppComponents.loadingSpinner(size = 14.dp)
                                Text(
                                    text = stringResource("chat.attachment.preview.loading"),
                                    style = AppTextStyles.hint,
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
                                        style = AppTextStyles.hint,
                                        fontFamily = FontFamily.Monospace,
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
 * Directive selector chip + upward-opening popup.
 *
 * Shows the currently active directive name (or a generic label when none is selected).
 * Clicking the chip opens an upward popup with a scrollable directive list, checkboxes to
 * toggle selection, and footer actions to create / manage directives.
 */
@Composable
private fun directiveChip(
    availableDirectives: List<ChatDirective>,
    selectedDirective: String?,
    isLoading: Boolean,
    onToggleDirective: (String?) -> Unit,
    directivePopupExpanded: Boolean,
    onDirectivePopupExpandedChange: (Boolean) -> Unit,
    onShowNewDirectiveDialog: () -> Unit,
    onShowManageDirectivesDialog: () -> Unit,
) {
    val activeDirective = availableDirectives.find { it.id == selectedDirective }
    val activeDirectiveName = activeDirective?.name
    val uriHandler = LocalUriHandler.current

    val chipAnchor: @Composable (@Composable () -> Unit) -> Unit =
        if (activeDirective != null) {
            { content ->
                themedRichTooltip(
                    tooltipContent = {
                        Column(
                            modifier = Modifier
                                .widthIn(min = 400.dp, max = 500.dp)
                                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Text(
                                text = activeDirective.name,
                                style = AppTextStyles.caption,
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            Text(
                                text = activeDirective.content,
                                style = AppTextStyles.hint,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    content = content,
                )
            }
        } else {
            { content ->
                themedTooltip(text = stringResource("chat.directive"), content = content)
            }
        }

    Box {
        chipAnchor {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selectedDirective != null) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                },
                tonalElevation = 2.dp,
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        enabled = !isLoading,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onDirectivePopupExpandedChange(true) },
                    )
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = stringResource("chat.directive"),
                        tint = if (selectedDirective != null) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = activeDirectiveName ?: stringResource("chat.directive"),
                        style = AppTextStyles.hint,
                        color = if (selectedDirective != null) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 80.dp),
                    )
                }
            }
        }

        // Upward-opening popup anchored to the chip's top edge
        if (directivePopupExpanded) {
            AppComponents.anchoredPopup(
                positionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(
                        x = anchorBounds.left,
                        y = anchorBounds.top - popupContentSize.height - 4,
                    )
                },
                onDismissRequest = { onDirectivePopupExpandedChange(false) },
                modifier = Modifier.widthIn(min = 350.dp, max = 420.dp),
            ) {
                // Header: title on left, action icon buttons on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Spacing.medium,
                            end = Spacing.extraSmall,
                            top = Spacing.small,
                            bottom = Spacing.small,
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("chat.directive"),
                        style = AppTextStyles.sectionTitle,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        themedTooltip(text = stringResource("chat.directive.new")) {
                            IconButton(
                                onClick = {
                                    onShowNewDirectiveDialog()
                                    onDirectivePopupExpandedChange(false)
                                },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource("chat.directive.new"),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        themedTooltip(text = stringResource("chat.directive.manage")) {
                            IconButton(
                                onClick = {
                                    onShowManageDirectivesDialog()
                                    onDirectivePopupExpandedChange(false)
                                },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = stringResource("chat.directive.manage"),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        themedTooltip(text = stringResource("chat.directive.learn.more")) {
                            IconButton(
                                onClick = {
                                    uriHandler.openUri("https://$DOMAIN/docs/desktop/directives/")
                                    onDirectivePopupExpandedChange(false)
                                },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = stringResource("chat.directive.learn.more"),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Directive rows — scrollable list capped at max height; popup expands freely
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (availableDirectives.isEmpty()) {
                        Text(
                            text = stringResource("chat.directive.empty"),
                            style = AppTextStyles.bodySecondary,
                            modifier = Modifier.padding(
                                horizontal = Spacing.medium,
                                vertical = Spacing.medium,
                            ),
                        )
                    }
                    availableDirectives.forEach { directive ->
                        val isSelected = selectedDirective == directive.id
                        themedRichTooltip(
                            placement = TooltipPlacement.RIGHT,
                            tooltipContent = {
                                Column(
                                    modifier = Modifier
                                        .widthIn(min = 350.dp, max = 420.dp)
                                        .padding(
                                            horizontal = Spacing.medium,
                                            vertical = Spacing.small,
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    Text(
                                        text = directive.name,
                                        style = AppTextStyles.caption,
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                    Text(
                                        text = directive.content,
                                        style = AppTextStyles.hint,
                                        maxLines = 10,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onToggleDirective(if (isSelected) null else directive.id)
                                    }
                                    .padding(
                                        start = Spacing.extraSmall,
                                        end = Spacing.small,
                                        top = 2.dp,
                                        bottom = 2.dp,
                                    )
                                    .pointerHoverIcon(PointerIcon.Hand),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            onToggleDirective(if (checked) directive.id else null)
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .pointerHoverIcon(PointerIcon.Hand),
                                    )
                                }
                                Text(
                                    text = directive.name,
                                    style = AppTextStyles.body,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (directive.scope == DirectiveScope.TEAM) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                    ) {
                                        Text(
                                            text = stringResource("directive.scope.team"),
                                            style = AppTextStyles.hint,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
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
}

/**
 * Chip that toggles live web search inclusion in the RAG retrieval pipeline.
 *
 * Shown in project chat sessions when [AppConfig.webSearch.enabled] is true.
 * When active the chip is highlighted and the [HybridContentRetriever] will
 * include live web results (via the configured [SearchBackend]) in its RRF fusion.
 */
@Composable
private fun webSearchRagChip(
    active: Boolean,
    isLoading: Boolean,
    onToggle: () -> Unit,
) {
    themedTooltip(
        text = if (active) {
            stringResource("chat.rag.web_search.tooltip.on")
        } else {
            stringResource("chat.rag.web_search.tooltip.off")
        },
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            },
            tonalElevation = if (active) 2.dp else 0.dp,
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    enabled = !isLoading,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (active) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                )
                Text(
                    text = stringResource("chat.rag.web_search.chip"),
                    style = AppTextStyles.hint,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                )
            }
        }
    }
}

/**
 * Circular arc chip that shows current memory utilisation.
 *
 * - Arc colour is [MemoryPressureLevel.NORMAL] → primary, WARNING → amber, CRITICAL → error.
 * - At WARNING or CRITICAL the chip becomes clickable (calls [onCompress]).
 * - While [isCompressing] is true the arc is replaced with a [CircularProgressIndicator].
 */
@Composable
private fun memoryUsageChip(
    utilization: Float,
    pressureLevel: MemoryPressureLevel,
    isCompressing: Boolean,
    isContextSizeLearned: Boolean,
    onCompress: (() -> Unit)?,
    usedTokens: Int = 0,
    budgetTokens: Int = 0,
) {
    val fillColor = when (pressureLevel) {
        MemoryPressureLevel.NORMAL -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        MemoryPressureLevel.WARNING -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        MemoryPressureLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val chipSize = 20.dp
    val strokeDp = 2.5.dp

    fun formatTokens(n: Int): String = when {
        n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000f)}M"
        n >= 1_000 -> "${"%.1f".format(n / 1_000f)}K"
        else -> "$n"
    }

    val tooltipText = when {
        isCompressing -> stringResource("memory.pressure.compressing")

        !isContextSizeLearned -> {
            if (usedTokens > 0) {
                "${formatTokens(usedTokens)} tokens used — ${stringResource("memory.pressure.chip.tooltip.unknown")}"
            } else {
                stringResource("memory.pressure.chip.tooltip.unknown")
            }
        }

        budgetTokens > 0 -> {
            val pct = (utilization * 100).toInt()
            val actionHint = when (pressureLevel) {
                MemoryPressureLevel.NORMAL -> stringResource("memory.pressure.chip.tooltip.normal")
                MemoryPressureLevel.WARNING -> stringResource("memory.pressure.chip.tooltip.warning")
                MemoryPressureLevel.CRITICAL -> stringResource("memory.pressure.chip.tooltip.critical")
            }
            "${formatTokens(usedTokens)} / ${formatTokens(budgetTokens)} tokens ($pct%) — $actionHint"
        }

        else -> "${(utilization * 100).toInt()}% ${stringResource("memory.pressure.chip.tooltip.normal")}"
    }

    val isClickable = isContextSizeLearned && pressureLevel != MemoryPressureLevel.NORMAL && onCompress != null && !isCompressing

    themedTooltip(text = tooltipText) {
        Box(
            modifier = Modifier
                .size(chipSize)
                .then(
                    if (isClickable) {
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onCompress() },
                            )
                            .pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isCompressing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(chipSize),
                    strokeWidth = strokeDp,
                    color = fillColor,
                )
            } else if (!isContextSizeLearned) {
                // Context size not yet calibrated — show a dashed ring with a "?" label
                Canvas(modifier = Modifier.size(chipSize)) {
                    val stroke = Stroke(
                        width = strokeDp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(4f, 3f),
                            0f,
                        ),
                    )
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke,
                    )
                }
                androidx.compose.material3.Text(
                    text = "?",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            } else {
                Canvas(modifier = Modifier.size(chipSize)) {
                    val stroke = Stroke(width = strokeDp.toPx(), cap = StrokeCap.Round)
                    // Track (full circle)
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke,
                    )
                    // Fill arc proportional to utilization
                    if (utilization > 0f) {
                        drawArc(
                            color = fillColor,
                            startAngle = -90f,
                            sweepAngle = 360f * utilization,
                            useCenter = false,
                            style = stroke,
                        )
                    }
                }
            }
        }
    }
}
