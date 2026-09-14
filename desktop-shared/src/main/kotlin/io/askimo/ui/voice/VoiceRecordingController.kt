/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.accessibleFocusable
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

/**
 * Auto-stop cap for voice dictation. Not a Whisper file-size concern (25MB/request allows
 * ~13 minutes at our 16kHz/16-bit mono capture rate) — purely a UX/safety bound so a
 * forgotten/stuck recording can't run indefinitely.
 */
private const val MAX_VOICE_RECORDING_SECONDS = 120

/** Rolling window size for the live waveform — see [VoiceRecordingController.toggle]. */
private const val MAX_WAVEFORM_SAMPLES = 40

/**
 * States for a 🎤 voice-input button.
 * IDLE → RECORDING (mic open, capturing) → TRANSCRIBING (STT in flight) → IDLE.
 */
enum class VoiceRecordingState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
}

/**
 * Owns the full voice-dictation lifecycle (mic capture, waveform sampling, auto-stop timer,
 * STT transcription, error surfacing) so any input surface (chat, agent run, …) can add a
 * 🎤 button by calling [rememberVoiceRecordingController] + [voiceRecordingControls] instead
 * of re-implementing this state machine.
 */
@Stable
class VoiceRecordingController internal constructor(
    private val scope: CoroutineScope,
    private val busyProvider: () -> Boolean,
    private val onTranscript: (String) -> Unit,
    private val errorTitleProvider: () -> String,
) {
    var voiceRecordingState by mutableStateOf(VoiceRecordingState.IDLE)
        private set

    // Rolling window of recent mic amplitude samples (0f..1f), fed from AudioRecorder's
    // capture-thread level callback — drives the live waveform shown while RECORDING.
    var voiceWaveformSamples by mutableStateOf<List<Float>>(emptyList())
        private set

    // Ticking elapsed-seconds counter shown next to the waveform while RECORDING — also
    // drives the [MAX_VOICE_RECORDING_SECONDS] auto-stop in [trackElapsedWhileRecording].
    var recordingElapsedSeconds by mutableStateOf(0)
        private set

    private val audioRecorder = AudioRecorder()

    /** Auto-stop cap surfaced for UI display (e.g. turning the elapsed label red near the end). */
    val maxRecordingSeconds: Int get() = MAX_VOICE_RECORDING_SECONDS

    val isRecording: Boolean get() = audioRecorder.isRecording

    /** Cancels any in-progress recording without transcribing — call when leaving composition. */
    fun cancelIfRecording() {
        if (audioRecorder.isRecording) {
            Thread({ audioRecorder.cancel() }, "askimo-audio-recorder-dispose-cancel").apply {
                isDaemon = true
                start()
            }
        }
        Snapshot.withMutableSnapshot { voiceWaveformSamples = emptyList() }
    }

    /**
     * Shared "stop recording, transcribe, insert text" logic — invoked both when the user
     * manually stops (via [toggle]) and when the recorder is auto-stopped after
     * [MAX_VOICE_RECORDING_SECONDS] (see [trackElapsedWhileRecording]).
     */
    fun stopRecordingAndTranscribe() {
        voiceRecordingState = VoiceRecordingState.TRANSCRIBING
        Snapshot.withMutableSnapshot { voiceWaveformSamples = emptyList() }
        scope.launch {
            try {
                val wavBytes = withContext(Dispatchers.IO) { audioRecorder.stop() }
                val transcript = withContext(Dispatchers.IO) {
                    VoiceServiceRegistry.speechToText(AppConfig.voice)
                        .transcribe(wavBytes, VoiceAudioFormat.WAV)
                }
                if (transcript.isNotBlank()) {
                    onTranscript(transcript)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceServiceException) {
                EventBus.post(AppErrorEvent(title = errorTitleProvider(), message = e.message ?: "Voice transcription failed"))
            } catch (e: Exception) {
                EventBus.post(AppErrorEvent(title = errorTitleProvider(), message = e.message ?: "Voice recording failed"))
            } finally {
                voiceRecordingState = VoiceRecordingState.IDLE
            }
        }
    }

    /**
     * Shared toggle logic — invoked both by the 🎤 button's onClick and by the
     * Cmd/Ctrl+Shift+M keyboard shortcut (TOGGLE_VOICE_RECORDING) so behavior stays identical.
     */
    fun toggle() {
        if (busyProvider()) return
        when (voiceRecordingState) {
            VoiceRecordingState.IDLE -> {
                try {
                    Snapshot.withMutableSnapshot { voiceWaveformSamples = emptyList() }
                    audioRecorder.start { level ->
                        // `level` is raw linear RMS (see PcmAudioEncoder.computeRmsLevel) — normal
                        // speech volume is far below full-scale, so raw values (~0.01-0.1) always
                        // collapse to the waveform's minimum bar height. Apply a perceptual sqrt
                        // curve + gain (like a real VU meter) so speech visibly moves the bars.
                        val boostedLevel = (sqrt(level.coerceIn(0f, 1f)) * 1.6f).coerceIn(0f, 1f)
                        Snapshot.withMutableSnapshot {
                            voiceWaveformSamples = (voiceWaveformSamples + boostedLevel).takeLast(MAX_WAVEFORM_SAMPLES)
                        }
                    }
                    voiceRecordingState = VoiceRecordingState.RECORDING
                } catch (e: MicrophoneUnavailableException) {
                    EventBus.post(AppErrorEvent(title = errorTitleProvider(), message = e.message ?: "Microphone unavailable"))
                }
            }

            VoiceRecordingState.RECORDING -> stopRecordingAndTranscribe()

            VoiceRecordingState.TRANSCRIBING -> {
                // Ignore triggers while a transcription request is in flight.
            }
        }
    }

    /**
     * Auto-stop safety cap — ticks [recordingElapsedSeconds] once per second while RECORDING and
     * triggers [stopRecordingAndTranscribe] once [MAX_VOICE_RECORDING_SECONDS] is reached, so a
     * forgotten/stuck recording can't run indefinitely. Driven by a `LaunchedEffect` keyed on
     * [voiceRecordingState] in [rememberVoiceRecordingController].
     */
    internal suspend fun trackElapsedWhileRecording() {
        if (voiceRecordingState != VoiceRecordingState.RECORDING) return
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

/**
 * Creates and remembers a [VoiceRecordingController] for the current composition.
 *
 * @param busy When `true`, [VoiceRecordingController.toggle] is a no-op (e.g. a message is
 *   already sending/streaming, or an agent run is in progress).
 * @param onTranscript Invoked on the latest recomposition's callback with the non-blank
 *   transcript once STT completes — insert it into the caller's input field.
 */
@Composable
fun rememberVoiceRecordingController(
    busy: Boolean,
    onTranscript: (String) -> Unit,
): VoiceRecordingController {
    val scope = rememberCoroutineScope()
    val busyState = rememberUpdatedState(busy)
    val onTranscriptState = rememberUpdatedState(onTranscript)
    val voiceErrorTitleState = rememberUpdatedState(stringResource("chat.voice.error.title"))

    val controller = remember {
        VoiceRecordingController(
            scope = scope,
            busyProvider = { busyState.value },
            onTranscript = { transcript -> onTranscriptState.value(transcript) },
            errorTitleProvider = { voiceErrorTitleState.value },
        )
    }

    LaunchedEffect(controller.voiceRecordingState) {
        controller.trackElapsedWhileRecording()
    }

    // Cancel any in-progress recording if this composable leaves the composition
    // (e.g. user navigates away mid-recording) to avoid leaking an open mic line.
    DisposableEffect(controller) {
        onDispose { controller.cancelIfRecording() }
    }

    return controller
}

/**
 * Live bar-style waveform driven by [samples] (each in `0f..1f`), most-recent last.
 * Used next to the 🎤 button while [VoiceRecordingState.RECORDING] to give immediate visual
 * feedback that the microphone is actually picking up sound, not just that recording started.
 */
@Composable
fun voiceWaveform(
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
 * Renders the 🎤 mic button plus its live status affordances (pulsing "recording" dot + elapsed
 * timer + waveform, or a "Transcribing…" spinner), driven by [controller]. Emits its pieces
 * directly (no wrapping `Row`) so callers can splice it into an existing `Row`'s content
 *
 * @param enabled Whether the mic button itself can be toggled (e.g. `!isLoading`/`!isRunning`).
 *   The status affordances (dot/waveform/spinner) are shown purely based on
 *   [VoiceRecordingController.voiceRecordingState], regardless of [enabled].
 */
@Composable
fun voiceRecordingControls(
    controller: VoiceRecordingController,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val voiceShortcutHint = KeyMapManager.AppShortcut.TOGGLE_VOICE_RECORDING.getDisplayString()
    val voiceTooltip = when (controller.voiceRecordingState) {
        VoiceRecordingState.IDLE -> stringResource("chat.voice.record", voiceShortcutHint)
        VoiceRecordingState.RECORDING -> stringResource("chat.voice.recording.stop")
        VoiceRecordingState.TRANSCRIBING -> stringResource("chat.voice.transcribing")
    }

    // Persistent (non-hover) "recording" status — a pulsing dot + live waveform reflecting mic
    // input level, so it's obvious at a glance (not just via the tooltip/icon tint) that audio
    // is being captured.
    if (controller.voiceRecordingState == VoiceRecordingState.RECORDING) {
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
        Spacer(modifier = Modifier.width(Spacing.extraSmall))
        Text(
            // Ticks up each second and turns solid red in the final 10s before
            // MAX_VOICE_RECORDING_SECONDS auto-stops the recording — the only user-facing
            // signal of the cap, no separate popup.
            text = stringResource("chat.voice.recording.label.timed", controller.recordingElapsedSeconds),
            style = AppTextStyles.caption,
            color = if (controller.recordingElapsedSeconds >= controller.maxRecordingSeconds - 10) {
                MaterialTheme.colorScheme.error
            } else {
                AppColors.warningColor()
            },
        )
        Spacer(modifier = Modifier.width(Spacing.extraSmall))
        voiceWaveform(
            samples = controller.voiceWaveformSamples,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.width(48.dp).height(18.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.small))
    }

    // Persistent (non-hover) "transcribing" status — the STT request round-trips to a
    // remote/mobile AI service and can take a few seconds, so show a spinner + label at all times.
    if (controller.voiceRecordingState == VoiceRecordingState.TRANSCRIBING) {
        AppComponents.loadingSpinner(
            size = 14.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(Spacing.extraSmall))
        Text(
            text = stringResource("chat.voice.transcribing"),
            style = AppTextStyles.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(Spacing.small))
    }

    themedTooltip(text = voiceTooltip) {
        val micInteractionSource = remember { MutableInteractionSource() }
        IconButton(
            onClick = controller::toggle,
            enabled = enabled && controller.voiceRecordingState != VoiceRecordingState.TRANSCRIBING,
            interactionSource = micInteractionSource,
            modifier = modifier
                .size(28.dp)
                .accessibleFocusable(micInteractionSource)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            // The spinner + "Transcribing…" label to the left already carries the busy
            // indicator during TRANSCRIBING — keep the mic icon here too (dimmed) instead of a
            // second spinner.
            Icon(
                Icons.Default.Mic,
                contentDescription = voiceTooltip,
                tint = when (controller.voiceRecordingState) {
                    VoiceRecordingState.RECORDING -> MaterialTheme.colorScheme.error
                    VoiceRecordingState.TRANSCRIBING -> AppColors.tertiaryIconColor()
                    VoiceRecordingState.IDLE -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
