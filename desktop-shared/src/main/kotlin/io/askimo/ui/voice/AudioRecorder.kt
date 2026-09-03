/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.logging.logger
import io.askimo.core.util.PcmAudioEncoder
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/**
 * Thrown when the microphone cannot be opened — typically because the OS denied audio
 * permission (macOS TCC prompt declined/never granted) or no input device is available.
 * Callers should catch this and surface a user-facing message (e.g. via `AppErrorEvent`)
 * rather than a raw stack trace.
 */
class MicrophoneUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Captures microphone audio using [javax.sound.sampled] — no extra native dependencies needed.
 * Records 16kHz mono 16-bit PCM, which is natively accepted by Whisper-family STT APIs.
 *
 * Captured PCM is streamed to a temporary file as it arrives rather than buffered in memory,
 * so memory use stays flat (a few KB — just the read chunk) regardless of recording length.
 * [stop] streams that file straight into the WAV encoder (via [PcmAudioEncoder], which holds
 * the pure/hardware-free RMS + WAV-encoding logic so it can be unit-tested from `:cli` without
 * an audio device) — the recording is never fully materialized as a single in-memory byte
 * array — and deletes the temp file once done.
 *
 * Usage:
 * ```
 * val recorder = AudioRecorder()
 * recorder.start()          // begins capturing on a background thread
 * ...
 * val wavBytes = recorder.stop()   // returns a complete WAV file's bytes
 * // or recorder.cancel() to discard without producing output
 * ```
 *
 * Not thread-safe for concurrent start/stop calls from multiple threads — intended to be driven
 * from a single UI-owned coroutine/state machine (see the 🎤 button in `ChatInputField.kt`).
 */
class AudioRecorder {
    private val log = logger<AudioRecorder>()

    companion object {
        private const val SAMPLE_RATE = 16_000f
        private const val SAMPLE_SIZE_BITS = 16
        private const val CHANNELS = 1
        private const val SIGNED = true
        private const val BIG_ENDIAN = false
        private const val READ_BUFFER_SIZE = 4096
    }

    private val format = AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, SIGNED, BIG_ENDIAN)

    @Volatile private var line: TargetDataLine? = null
    private var captureThread: Thread? = null

    // Captured PCM is streamed straight to a temp file instead of an in-memory buffer, so
    // memory use stays flat (a few KB for the read chunk) no matter how long the user records
    // for. `writeLock` guards `pcmFile`/`fileOut` since they're written from the capture thread
    // and read/closed from whichever thread calls stop()/cancel().
    private val writeLock = Any()
    private var pcmFile: Path? = null
    private var fileOut: BufferedOutputStream? = null

    /** True while actively capturing audio. */
    val isRecording: Boolean get() = line?.isOpen == true

    /**
     * Opens the microphone and begins capturing on a background thread, streaming raw PCM
     * bytes to a temporary file as they arrive (see [pcmFile]) rather than an in-memory buffer.
     *
     * @param onLevel Optional callback invoked on the capture thread after each chunk is read,
     *   with the chunk's normalized RMS amplitude in `0f..1f` (0 = silence, 1 = full-scale).
     *   Intended to drive a live "recording" UI indicator (e.g. a waveform/level meter) — this
     *   is a lightweight, high-frequency signal, not audio data, so callers can safely mutate
     *   Compose state directly from it (see the 🎤 button in `ChatInputField.kt`).
     * @throws MicrophoneUnavailableException if no input device is available, the OS denies
     *   access, or a temporary file for the capture buffer cannot be created.
     */
    fun start(onLevel: ((Float) -> Unit)? = null) {
        check(!isRecording) { "AudioRecorder is already recording" }

        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            throw MicrophoneUnavailableException("No microphone input line supports the required audio format")
        }

        val targetLine = try {
            (AudioSystem.getLine(info) as TargetDataLine).also {
                it.open(format)
                it.start()
            }
        } catch (e: LineUnavailableException) {
            throw MicrophoneUnavailableException(
                "Could not open the microphone. Check system permissions for Askimo under " +
                    "Privacy & Security > Microphone. (${e.message})",
                e,
            )
        } catch (e: SecurityException) {
            throw MicrophoneUnavailableException("Microphone access was denied by the operating system.", e)
        }

        val tempFile = try {
            Files.createTempFile("askimo-audio-", ".pcm").also { it.toFile().deleteOnExit() }
        } catch (e: Exception) {
            targetLine.close()
            throw MicrophoneUnavailableException(
                "Could not allocate temporary storage for the recording. (${e.message})",
                e,
            )
        }

        synchronized(writeLock) {
            pcmFile = tempFile
            fileOut = BufferedOutputStream(Files.newOutputStream(tempFile))
        }

        line = targetLine
        captureThread = Thread({
            val readBuffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (targetLine.isOpen) {
                    val bytesRead = targetLine.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        synchronized(writeLock) {
                            try {
                                fileOut?.write(readBuffer, 0, bytesRead)
                            } catch (e: Exception) {
                                log.warn("Failed writing captured audio to temp file", e)
                            }
                        }
                        if (onLevel != null) {
                            onLevel(PcmAudioEncoder.computeRmsLevel(readBuffer, bytesRead))
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("Audio capture thread stopped", e)
            }
        }, "askimo-audio-recorder").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stops recording and returns the captured audio encoded as a complete WAV file.
     * Safe to call even if [start] was never called (returns an empty WAV).
     *
     * The temp file written during capture is streamed directly into the WAV encoder (never
     * fully loaded into a byte array) and deleted once encoding completes.
     */
    fun stop(): ByteArray {
        val targetLine = line
        if (targetLine == null) {
            cleanupTempFile()
            return PcmAudioEncoder.encodeFileAsWav(format, null, 0L)
        }
        targetLine.stop()
        targetLine.close()
        line = null
        captureThread?.join(2_000)
        captureThread = null

        val file = closeAndTakeTempFile()
        return try {
            val frameCount = file?.let { runCatching { Files.size(it) }.getOrDefault(0L) / format.frameSize } ?: 0L
            PcmAudioEncoder.encodeFileAsWav(format, file, frameCount)
        } finally {
            cleanupTempFile(file)
        }
    }

    /** Stops recording and discards the captured audio without producing output. */
    fun cancel() {
        line?.let {
            it.stop()
            it.close()
        }
        line = null
        captureThread?.join(2_000)
        captureThread = null

        val file = closeAndTakeTempFile()
        cleanupTempFile(file)
    }

    /** Flushes/closes the current temp-file output stream (if any) and returns its path. */
    private fun closeAndTakeTempFile(): Path? = synchronized(writeLock) {
        try {
            fileOut?.flush()
            fileOut?.close()
        } catch (e: Exception) {
            log.debug("Failed closing temp audio file stream", e)
        }
        fileOut = null
        val file = pcmFile
        pcmFile = null
        file
    }

    /** Deletes [file] (defaults to the current [pcmFile]) if present, ignoring failures. */
    private fun cleanupTempFile(file: Path? = synchronized(writeLock) { pcmFile }) {
        file?.let {
            try {
                Files.deleteIfExists(it)
            } catch (e: Exception) {
                log.debug("Failed to delete temp audio file {}", it, e)
            }
        }
    }
}
