/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.service

import io.askimo.core.logging.logger
import io.askimo.core.util.ProcessBuilderExt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * Exception thrown when Mermaid CLI is not available on the system.
 */
class MermaidCliNotAvailableException(message: String) : Exception(message)

/** Maximum time to wait for `npm install -g @mermaid-js/mermaid-cli` to finish. */
private const val INSTALL_TIMEOUT_MINUTES = 10L

/**
 * Service for converting Mermaid diagrams to SVG using local Mermaid CLI.
 *
 * This service uses the locally installed mermaid-cli (mmdc) to render Mermaid diagrams
 * as SVG images. This approach ensures privacy by keeping all data local and works offline.
 */
class MermaidSvgService {
    private val log = logger<MermaidSvgService>()

    @Volatile
    private var cachedAvailability: Boolean? = null

    /**
     * Checks if Mermaid CLI is available on the system.
     * This result is cached after the first check to avoid multiple expensive checks.
     *
     * Performs two sequential checks so logs clearly distinguish the failure cause:
     * 1. `node --version` — confirms Node.js is on PATH (enriched by [ProcessBuilderExt])
     * 2. `mmdc --version` — confirms the Mermaid CLI binary is installed
     *
     * PATH is automatically enriched by [ProcessBuilderExt] on all platforms to cover
     * nvm, Homebrew, and npm global bin directories that are absent in desktop-app PATH.
     *
     * @return true if both Node.js and mmdc are installed and accessible
     */
    fun isMermaidCliAvailable(): Boolean {
        cachedAvailability?.let { return it }

        val result = try {
            // ── Step 1: check Node.js ─────────────────────────────────────────
            log.debug("Mermaid check step 1: checking Node.js...")
            val nodeCheck = ProcessBuilderExt("node", "--version")
                .redirectErrorStream(true)
                .start()
            val nodeOutput = nodeCheck.inputStream.bufferedReader().readText().trim()
            val nodeAvailable = nodeCheck.waitFor(5, TimeUnit.SECONDS) && nodeCheck.exitValue() == 0

            if (!nodeAvailable) {
                log.warn("Mermaid check step 1 FAILED: Node.js not found on PATH. Install Node.js to enable Mermaid rendering.")
                false
            } else {
                log.debug("Mermaid check step 1 OK: Node.js found ({})", nodeOutput)

                log.debug("Mermaid check step 2: checking mmdc (Mermaid CLI)...")
                val process = ProcessBuilderExt("mmdc", "--version")
                    .redirectErrorStream(true)
                    .start()

                // Read output in a separate thread to avoid blocking
                val outputBuilder = StringBuilder()
                val outputReader = Thread {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            outputBuilder.append(line).append("\n")
                        }
                    }
                }
                outputReader.start()

                val completed = process.waitFor(10, TimeUnit.SECONDS)
                outputReader.join(1_000)

                if (!completed) {
                    log.warn("Mermaid check step 2 FAILED: mmdc timed out")
                    process.destroyForcibly()
                    false
                } else {
                    val exitCode = process.exitValue()
                    val output = outputBuilder.toString().trim()
                    val available = exitCode == 0

                    if (available) {
                        log.debug("Mermaid check step 2 OK: mmdc found ({})", output)
                    } else {
                        log.warn(
                            "Mermaid check step 2 FAILED: Node.js is present but mmdc not found " +
                                "(exit={}). Install with: npm install -g @mermaid-js/mermaid-cli. Output: {}",
                            exitCode,
                            output,
                        )
                    }
                    available
                }
            }
        } catch (e: Exception) {
            log.warn("Mermaid CLI availability check threw an exception: {}", e.message)
            false
        }

        cachedAvailability = result
        return result
    }

    /**
     * Clears the cached CLI availability so the next call to [isMermaidCliAvailable]
     * performs a fresh check. Used by the setup screen's "Check Again" flow and after
     * an in-app installation completes.
     */
    fun resetAvailabilityCache() {
        cachedAvailability = null
    }

    /**
     * Checks if npm is available on the system. Not cached — the setup screen calls
     * this once to decide whether the one-click install button can be offered.
     *
     * @return true if npm is installed and accessible
     */
    fun isNpmAvailable(): Boolean = try {
        val process = ProcessBuilderExt("npm", "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val available = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        if (available) {
            log.debug("npm found ({})", output)
        } else {
            log.warn("npm not found on PATH")
        }
        available
    } catch (e: Exception) {
        log.warn("npm availability check threw an exception: {}", e.message)
        false
    }

    /**
     * Installs Mermaid CLI globally by running `npm install -g @mermaid-js/mermaid-cli`,
     * streaming the installer output line by line.
     *
     * On success the cached availability is reset so the next
     * [isMermaidCliAvailable] call re-checks and finds the freshly installed CLI.
     *
     * @param onOutput Invoked for each line of installer output (stdout and stderr merged).
     *                 May be called from a background thread.
     * @return true if the installation completed successfully
     */
    suspend fun installMermaidCli(onOutput: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            log.info("Installing Mermaid CLI: npm install -g @mermaid-js/mermaid-cli")
            val process = ProcessBuilderExt("npm", "install", "-g", "@mermaid-js/mermaid-cli")
                .redirectErrorStream(true)
                .start()

            // Read output in a separate thread so a hung installer cannot block us;
            // destroying the process on timeout closes the stream and unblocks the reader
            val outputReader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> onOutput(line) }
                    }
                } catch (e: Exception) {
                    log.debug("Installer output stream closed: {}", e.message)
                }
            }
            outputReader.start()

            val completed = process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!completed) {
                log.warn("Mermaid CLI installation timed out after {} minutes", INSTALL_TIMEOUT_MINUTES)
                process.destroyForcibly()
                outputReader.join(1_000)
                onOutput("Installation timed out after $INSTALL_TIMEOUT_MINUTES minutes.")
                return@withContext false
            }
            outputReader.join(1_000)

            val success = process.exitValue() == 0
            if (success) {
                log.info("Mermaid CLI installed successfully")
                resetAvailabilityCache()
            } else {
                log.warn("Mermaid CLI installation failed with exit code {}", process.exitValue())
            }
            success
        } catch (e: Exception) {
            log.warn("Mermaid CLI installation threw an exception: {}", e.message)
            onOutput(e.message ?: "Unknown installation error")
            false
        }
    }

    /**
     * Converts a Mermaid diagram to PNG using local Mermaid CLI.
     *
     * @param diagram The Mermaid diagram definition
     * @param theme The theme to use (default, dark, forest, neutral)
     * @param backgroundColor The background color as hex string (e.g., "#ffffff")
     * @return The PNG content as a byte array
     * @throws MermaidCliNotAvailableException if mermaid-cli is not installed
     * @throws IOException if the conversion fails
     */
    suspend fun convertToPng(diagram: String, theme: String = "default", backgroundColor: String = "#ffffff"): ByteArray = withContext(Dispatchers.IO) {
        if (!isMermaidCliAvailable()) {
            throw MermaidCliNotAvailableException(
                "Mermaid CLI (mmdc) is not available. Please install it globally: npm install -g @mermaid-js/mermaid-cli",
            )
        }

        val tempDir = Files.createTempDirectory("askimo-mermaid")
        val inputFile = tempDir.resolve("diagram.mmd")
        val outputFile = tempDir.resolve("diagram.png")

        try {
            // Replace literal escape sequences with actual characters
            // This handles cases where the diagram comes from JSON with escaped characters
            val normalizedDiagram = diagram
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")

            // Auto-fix common AI-generated syntax issues before sending to mmdc
            val sanitizedDiagram = sanitizeDiagram(normalizedDiagram)

            Files.writeString(inputFile, sanitizedDiagram, StandardOpenOption.CREATE)

            val process = ProcessBuilderExt(
                "mmdc",
                "-i", inputFile.toString(),
                "-o", outputFile.toString(),
                "-t", theme,
                "-b", backgroundColor,
                "-s", "3", // scale 3 → crisp on HiDPI / 4K displays
            ).redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()

            val exitCode = process.waitFor(30, TimeUnit.SECONDS)

            if (!exitCode || process.exitValue() != 0) {
                // Strip the JS stack trace — keep only the meaningful parse error lines
                val parseError = output.lines()
                    .takeWhile { !it.trimStart().startsWith("at ") && !it.trimStart().startsWith("Parser3.parseError") }
                    .joinToString("\n")
                    .trim()
                    .ifEmpty { output.lines().take(4).joinToString("\n").trim() }
                log.error("Mermaid CLI failed.\ndiagram:\n{}\nerror:\n{}", diagram, output)
                throw IOException(parseError)
            }

            if (!Files.exists(outputFile)) {
                throw IOException("Mermaid CLI did not produce output file")
            }

            val pngData = Files.readAllBytes(outputFile)

            // mmdc sometimes exits 0 but renders a PNG of the error message instead of the diagram.
            // Detect this by scanning the raw PNG bytes for known error text — scale-independent.
            val pngText = String(pngData, Charsets.ISO_8859_1)
            val isErrorImage = pngText.contains("Parse error") ||
                pngText.contains("Syntax error") ||
                pngText.contains("Generating single mermaid chart") ||
                pngText.contains("UnknownDiagramError") ||
                pngText.contains("No diagram type detected") ||
                pngText.contains("Error:")
            if (isErrorImage) {
                log.error("Mermaid CLI produced an error image ({} bytes) — diagram has silent syntax errors.", pngData.size)
                val embeddedError = output.lines()
                    .takeWhile { !it.trimStart().startsWith("at ") }
                    .joinToString(" ")
                    .trim()
                    .ifEmpty { "Diagram has runtime syntax errors. Check node format and arrow syntax." }
                throw IOException(embeddedError)
            }

            log.debug("Successfully converted diagram to PNG (size: {} bytes) with theme: {}", pngData.size, theme)

            pngData
        } catch (e: MermaidCliNotAvailableException) {
            throw e
        } catch (e: IOException) {
            throw e // already has the clean parse error message
        } catch (e: Exception) {
            log.error("Failed to convert Mermaid diagram to PNG", e)
            throw IOException("Failed to convert Mermaid diagram: ${e.message}", e)
        } finally {
            try {
                Files.deleteIfExists(inputFile)
                Files.deleteIfExists(outputFile)
                Files.deleteIfExists(tempDir)
            } catch (e: Exception) {
                log.warn("Failed to clean up temp files", e)
            }
        }
    }

    /**
     * Auto-fixes common AI-generated Mermaid syntax errors before rendering.
     * Handles: unquoted node/edge labels with special chars, style directives,
     * single-% comments, and inline trailing comments.
     */
    internal fun sanitizeDiagram(diagram: String): String {
        val lines = diagram.lines()
        val result = mutableListOf<String>()

        // Regex to find edge labels |text| where text has special chars and is NOT already quoted
        val unquotedEdgeLabel = Regex("""\|([^|"'][^|]*[(:\/&][^|]*)\|""")

        // Drop style/classDef/class directives
        val styleDirective = Regex("""^\s*(style|classDef|class)\s+""")

        for (line in lines) {
            if (styleDirective.containsMatchIn(line)) {
                log.debug("Sanitizer: removed style directive: {}", line.trim())
                continue
            }

            var fixed = line

            // Fix single-% comments → %% (must be own-line comment starting with single %)
            if (Regex("""^\s*%(?!%)""").containsMatchIn(fixed)) {
                fixed = fixed.replaceFirst(Regex("""^(\s*)%(?!%)"""), "$1%%")
                log.debug("Sanitizer: fixed single-% comment: {}", fixed.trim())
            }

            // Strip inline trailing %% comments after a statement on the same line
            // e.g.  C --> F; %% Pass context  →  C --> F;
            // Pure %% comment lines (nothing before %%) are preserved intact.
            if (!fixed.trimStart().startsWith("%%") && Regex("""\S.*%%""").containsMatchIn(fixed)) {
                val stripped = fixed.replace(Regex("""\s*%%.*$"""), "").trimEnd()
                if (stripped != fixed) {
                    log.debug("Sanitizer: stripped inline comment from: {}", line.trim())
                    fixed = stripped
                }
            }

            // Fix unquoted edge labels: |text with (parens) or colons|  →  |"text with (parens) or colons"|
            fixed = unquotedEdgeLabel.replace(fixed) { mr ->
                val label = mr.groupValues[1].trim()
                """|"$label"|"""
            }

            // Fix unquoted [ ] node labels with special chars → ["label"]
            // Skips cylinder [( )] — those must stay plain text
            fixed = Regex("""(\w+)\[([^"\[\](][^\[\]]*[(:\/&][^\[\]]*)\]""").replace(fixed) { mr ->
                val id = mr.groupValues[1]
                val label = mr.groupValues[2].trim()
                """$id["$label"]"""
            }

            // Fix unquoted { } node labels with special chars → {"label"}
            fixed = Regex("""(\w+)\{([^"{}][^{}]*[(:\/&][^{}]*)\}""").replace(fixed) { mr ->
                val id = mr.groupValues[1]
                val label = mr.groupValues[2].trim()
                """$id{"$label"}"""
            }

            result.add(fixed)
        }

        val sanitized = result.joinToString("\n")
        if (sanitized != diagram) {
            log.debug("Sanitizer modified diagram:\nBEFORE:\n{}\nAFTER:\n{}", diagram, sanitized)
        }
        return sanitized
    }
}
