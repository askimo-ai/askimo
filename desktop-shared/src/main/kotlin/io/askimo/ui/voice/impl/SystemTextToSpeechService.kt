/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl

import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.logging.logger
import io.askimo.ui.voice.TextToSpeechFactory
import io.askimo.ui.voice.TextToSpeechService
import io.askimo.ui.voice.VoiceAudioFormat
import io.askimo.ui.voice.VoiceServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists

/** The subset of [SystemTextToSpeechService] behavior that differs per OS. */
internal enum class SystemTtsPlatform {
    MACOS,
    WINDOWS,
    LINUX,
}

/**
 * Text-to-speech via the operating system's built-in TTS engine, invoked as a subprocess
 * ([ProcessBuilder]) — no network, no API key, no model download.
 *
 *  - **macOS**: `say`, writing directly to a WAV file via `-o`/`--data-format`.
 *  - **Windows**: PowerShell + `System.Speech.Synthesis.SpeechSynthesizer`, writing a WAV file.
 *    Text is piped via **stdin** (not embedded in the script) to avoid quoting/escaping bugs.
 *  - **Linux**: `espeak-ng --stdout`, streaming WAV bytes directly to stdout (no temp file).
 *    Requires `espeak-ng` to be installed (most distros package it).
 *
 * Text is always passed via stdin rather than as a command-line argument, so shell-special
 * characters are never an issue — [ProcessBuilder] never spawns a shell, and stdin bytes are
 * opaque to the OS tool.
 *
 * [VoiceConfig.ttsVoice] is forwarded as the OS voice name when non-blank (e.g. "Samantha" on
 * macOS, "Microsoft David Desktop" on Windows, "en-us" on espeak-ng). The Settings UI offers a
 * dropdown of voices actually installed on the machine, populated via [listAvailableVoices]. If a
 * stale/invalid voice name is present anyway (hand-edited config, or an uninstalled voice),
 * [synthesize] retries once without it instead of failing outright.
 *
 * [VoiceConfig.ttsSpeed] is linearly mapped from Askimo's normalized 0.25x-4.0x range onto each
 * OS tool's own rate scale via [speedArgs]/[windowsRate] — approximate, since the platforms don't
 * share a common unit (words-per-minute vs. a unitless -10..10 scale). [VoiceConfig.ttsModel] has
 * no equivalent here and is ignored.
 */
class SystemTextToSpeechService(private val config: VoiceConfig) : TextToSpeechService {
    private val log = logger<SystemTextToSpeechService>()

    override val outputFormat: VoiceAudioFormat = VoiceAudioFormat.WAV

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val platform = detectPlatform()
        try {
            synthesizeOnce(platform, text, useConfiguredVoice = true)
        } catch (e: VoiceServiceException) {
            // A leftover voice name from another provider (e.g. OpenAI's "alloy") or hand-edited
            // config isn't a valid OS voice and makes `say`/espeak-ng fail outright — retry once
            // without it so the OS falls back to its own default voice.
            if (config.ttsVoice.isNotBlank()) {
                log.warn("System TTS failed with configured voice '${config.ttsVoice}', retrying with the OS default voice", e)
                synthesizeOnce(platform, text, useConfiguredVoice = false)
            } else {
                throw e
            }
        } catch (e: IOException) {
            log.warn("System TTS tool not found/launchable", e)
            throw VoiceServiceException(notFoundMessage(platform), e)
        } catch (e: Exception) {
            log.warn("System TTS failed", e)
            throw VoiceServiceException("System TTS failed: ${e.message}", e)
        }
    }

    private fun synthesizeOnce(platform: SystemTtsPlatform, text: String, useConfiguredVoice: Boolean): ByteArray = try {
        when (platform) {
            SystemTtsPlatform.LINUX -> synthesizeViaStdout(linuxCommand(useConfiguredVoice), text)
            SystemTtsPlatform.MACOS -> synthesizeViaTempFile(macCommandAndFile(useConfiguredVoice), text)
            SystemTtsPlatform.WINDOWS -> synthesizeViaTempFile(windowsCommandAndFile(useConfiguredVoice), text)
        }
    } catch (e: IOException) {
        log.warn("System TTS tool not found/launchable", e)
        throw VoiceServiceException(notFoundMessage(platform), e)
    }

    /** Runs [command], writing [text] to its stdin, and returns its stdout bytes as-is (Linux/espeak-ng). */
    private fun synthesizeViaStdout(command: List<String>, text: String): ByteArray {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.outputStream.use { it.write(stdinPayload(text)) }
        val audioBytes = process.inputStream.readBytes()
        val errorBytes = process.errorStream.readBytes()
        awaitOrKill(process)
        if (process.exitValue() != 0 || audioBytes.isEmpty()) {
            throw VoiceServiceException(
                "System TTS command failed (exit ${process.exitValue()}): " +
                    errorBytes.toString(Charsets.UTF_8).ifBlank { "no output" },
            )
        }
        return audioBytes
    }

    /** Runs [commandAndFile]'s command, writing [text] to its stdin, then reads the audio file it produced. */
    private fun synthesizeViaTempFile(commandAndFile: CommandAndFile, text: String): ByteArray {
        val (command, outFile) = commandAndFile
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.use { it.write(stdinPayload(text)) }
            val output = process.inputStream.readBytes()
            awaitOrKill(process)
            if (process.exitValue() != 0) {
                throw VoiceServiceException(
                    "System TTS command failed (exit ${process.exitValue()}): " +
                        output.toString(Charsets.UTF_8).ifBlank { "no output" },
                )
            }
            val bytes = Files.readAllBytes(outFile)
            if (bytes.isEmpty()) {
                throw VoiceServiceException("System TTS produced no audio output")
            }
            return bytes
        } finally {
            outFile.deleteIfExists()
        }
    }

    /**
     * Ensures [text] ends with sentence-closing punctuation and a trailing newline before
     * encoding it for stdin — guards against two EOF-timing issues seen with real OS TTS
     * engines: (1) text ending exactly at punctuation can race the engine's sentence-boundary
     * detection against immediate EOF, truncating the final utterance; (2) text with no
     * punctuation at all containing an ambiguous word can leave the engine waiting for
     * disambiguating context that never arrives. Appending a period (if missing) plus a newline
     * gives an unambiguous "sentence is complete" signal for both cases, without being audible.
     */
    private fun stdinPayload(text: String): ByteArray {
        val closed = if (text.trimEnd().lastOrNull()?.let { it in ".!?" } == true) text else "$text."
        return (closed + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun awaitOrKill(process: Process) {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw VoiceServiceException("System TTS timed out after 30s")
        }
    }

    private data class CommandAndFile(val command: List<String>, val outFile: java.nio.file.Path)

    /**
     * Maps Askimo's normalized 0.25x-4.0x [VoiceConfig.ttsSpeed] onto [platform]'s native rate
     * scale. macOS/espeak-ng use words-per-minute, so a plain multiplicative scale around a
     * ~175-180 wpm "1.0x" baseline works; Windows is handled separately via [windowsRate].
     */
    internal fun speedArgs(platform: SystemTtsPlatform, speed: Double): List<String> {
        val clampedSpeed = speed.coerceIn(0.25, 4.0)
        return when (platform) {
            SystemTtsPlatform.MACOS -> listOf("-r", (180.0 * clampedSpeed).toInt().coerceIn(90, 720).toString())
            SystemTtsPlatform.LINUX -> listOf("-s", (175.0 * clampedSpeed).toInt().coerceIn(20, 600).toString())
            SystemTtsPlatform.WINDOWS -> emptyList() // handled inline in windowsCommandAndFile's script
        }
    }

    /** Windows' `SpeechSynthesizer.Rate`: a unitless -10..10 scale, piecewise-linear around 1.0x = 0. */
    internal fun windowsRate(speed: Double): Int {
        val clampedSpeed = speed.coerceIn(0.25, 4.0)
        val rate = if (clampedSpeed <= 1.0) {
            (clampedSpeed - 1.0) / (1.0 - 0.25) * 10
        } else {
            (clampedSpeed - 1.0) / (4.0 - 1.0) * 10
        }
        return rate.toInt().coerceIn(-10, 10)
    }

    /**
     * Lists voice names/identifiers installed on this machine, suitable for [VoiceConfig.ttsVoice].
     * Each OS has its own naming scheme (plain names on macOS, locale codes on espeak-ng, display
     * names on Windows) so these are **not** interchangeable across platforms. Returns an empty
     * list (rather than throwing) if the underlying tool isn't installed/launchable.
     */
    suspend fun listAvailableVoices(): List<String> = withContext(Dispatchers.IO) {
        try {
            when (detectPlatform()) {
                SystemTtsPlatform.MACOS -> listMacVoices()
                SystemTtsPlatform.LINUX -> listLinuxVoices()
                SystemTtsPlatform.WINDOWS -> listWindowsVoices()
            }
        } catch (e: Exception) {
            log.warn("Could not list system TTS voices", e)
            emptyList()
        }
    }

    /** `say -v '?'` prints one voice per line: "<name>   <locale>   # <description>". */
    private fun listMacVoices(): List<String> {
        val process = ProcessBuilder("say", "-v", "?").redirectErrorStream(false).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        awaitOrKill(process)
        if (process.exitValue() != 0) return emptyList()
        return output.lineSequence()
            .mapNotNull { line -> Regex("""^(\S.*?)\s{2,}\S+\s+#""").find(line)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    /**
     * `espeak-ng --voices` prints a header row followed by one row per voice; the 2nd column
     * ("Language") is the identifier accepted by `-v` (e.g. "en-us"), not the human-readable
     * 4th column ("VoiceName"), which `-v` does not accept.
     */
    private fun listLinuxVoices(): List<String> {
        val process = ProcessBuilder("espeak-ng", "--voices").redirectErrorStream(false).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        awaitOrKill(process)
        if (process.exitValue() != 0) return emptyList()
        return output.lineSequence()
            .drop(1) // header row: "Pty Language Age/Gender VoiceName File Other Languages"
            .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    /** PowerShell + System.Speech's `GetInstalledVoices()`, printing just each voice's display name. */
    private fun listWindowsVoices(): List<String> {
        val script = """
            Add-Type -AssemblyName System.Speech
            (New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() |
                ForEach-Object { ${'$'}_.VoiceInfo.Name }
        """.trimIndent()
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(false).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        awaitOrKill(process)
        if (process.exitValue() != 0) return emptyList()
        return output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()
    }

    /**
     * `say` reads text from stdin when no literal text argument is given. `--data-format` +
     * a `.wav` output extension together make it emit a real linear-PCM WAV container (its
     * default without that flag is AIFF/ALAC), matching the declared [outputFormat].
     */
    private fun macCommandAndFile(useConfiguredVoice: Boolean): CommandAndFile {
        val outFile = Files.createTempFile("askimo-tts-", ".wav")
        val cmd = buildList {
            add("say")
            if (useConfiguredVoice) {
                config.ttsVoice.takeIf { it.isNotBlank() }?.let {
                    add("-v")
                    add(it)
                }
            }
            addAll(speedArgs(SystemTtsPlatform.MACOS, config.ttsSpeed))
            add("--data-format=LEI16@22050")
            add("-o")
            add(outFile.toString())
        }
        return CommandAndFile(cmd, outFile)
    }

    /** `espeak-ng --stdout` streams WAV bytes to stdout and reads the phrase from stdin. */
    private fun linuxCommand(useConfiguredVoice: Boolean): List<String> = buildList {
        add("espeak-ng")
        if (useConfiguredVoice) {
            config.ttsVoice.takeIf { it.isNotBlank() }?.let {
                add("-v")
                add(it)
            }
        }
        addAll(speedArgs(SystemTtsPlatform.LINUX, config.ttsSpeed))
        add("--stdout")
    }

    /** PowerShell + System.Speech, reading the phrase from stdin to avoid script-injection/escaping. */
    private fun windowsCommandAndFile(useConfiguredVoice: Boolean): CommandAndFile {
        val outFile = Files.createTempFile("askimo-tts-", ".wav")
        val voiceLine = config.ttsVoice.takeIf { useConfiguredVoice && it.isNotBlank() }
            ?.let { "try { \$s.SelectVoice('${it.replace("'", "''")}') } catch {}" }
            ?: ""
        val script = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            $voiceLine
            ${'$'}s.Rate = ${windowsRate(config.ttsSpeed)}
            ${'$'}s.SetOutputToWaveFile('${outFile.toString().replace("'", "''")}')
            ${'$'}text = [Console]::In.ReadToEnd()
            ${'$'}s.Speak(${'$'}text)
            ${'$'}s.Dispose()
        """.trimIndent()
        val cmd = listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
        return CommandAndFile(cmd, outFile)
    }

    private fun detectPlatform(): SystemTtsPlatform {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> SystemTtsPlatform.MACOS
            os.contains("win") -> SystemTtsPlatform.WINDOWS
            else -> SystemTtsPlatform.LINUX
        }
    }

    private fun notFoundMessage(platform: SystemTtsPlatform): String = when (platform) {
        SystemTtsPlatform.LINUX ->
            "System TTS requires espeak-ng. Install it with your package manager, e.g. `sudo apt install espeak-ng`."

        SystemTtsPlatform.MACOS ->
            "System TTS requires the built-in `say` command, which should always be present on macOS. " +
                "If this persists, check your PATH."

        SystemTtsPlatform.WINDOWS ->
            "System TTS requires PowerShell, which should always be present on Windows. " +
                "If this persists, check your PATH."
    }
}

object SystemTextToSpeechFactory : TextToSpeechFactory {
    override val provider: VoiceProvider = VoiceProvider.SYSTEM
    override fun create(config: VoiceConfig): TextToSpeechService = SystemTextToSpeechService(config)
}
