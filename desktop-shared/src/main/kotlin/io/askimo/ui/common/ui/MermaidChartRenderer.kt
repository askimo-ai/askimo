/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.DiagramFixedEvent
import io.askimo.core.logging.currentFileLogger
import io.askimo.tools.chart.MermaidChartData
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.service.MermaidCliNotAvailableException
import io.askimo.ui.service.MermaidSvgService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.skia.Image as SkiaImage

private val log = currentFileLogger()

private const val MAX_AI_RETRIES = 3

/**
 * Cache for rendered Mermaid diagrams.
 * Uses a thread-safe memory cache for concurrent chart renders and a temp-dir
 * disk cache for persistence across app sessions.
 */
private object DiagramCache {
    // ConcurrentHashMap so multiple LaunchedEffect coroutines can read/write safely in parallel
    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private val cacheDir = File(
        System.getProperty("java.io.tmpdir"),
        "askimo-diagram-cache",
    ).apply {
        mkdirs()
    }

    fun getCacheKey(diagram: String, theme: String, backgroundColor: String): String {
        val content = "$diagram|$theme|$backgroundColor"
        return content.hashCode().toString()
    }

    fun get(key: String): ByteArray? {
        memoryCache[key]?.let {
            log.debug("✅ Cache HIT (memory) for key: {}", key)
            return it
        }

        // Check disk cache in temp
        val cacheFile = File(cacheDir, "$key.png")
        return if (cacheFile.exists()) {
            try {
                cacheFile.readBytes().also {
                    memoryCache[key] = it // Store in memory for faster access
                    log.debug("✅ Cache HIT (disk) for key: {}, size: {} bytes", key, it.size)
                }
            } catch (e: Exception) {
                log.warn("Failed to read cache file: {}", e.message)
                null
            }
        } else {
            log.debug("❌ Cache MISS for key: {}", key)
            null
        }
    }

    fun put(key: String, data: ByteArray) {
        memoryCache[key] = data
        log.debug("📝 Cached in memory: key={}, size={} bytes", key, data.size)

        // Store in temp directory
        try {
            val cacheFile = File(cacheDir, "$key.png")
            cacheFile.writeBytes(data)
            log.debug("💾 Cached to disk: {}, size={} bytes", cacheFile.absolutePath, data.size)
        } catch (e: Exception) {
            log.warn("Failed to write cache file: {}", e.message)
        }
    }
}

/**
 * Renders Mermaid diagrams using local Mermaid CLI to generate PNG.
 *
 * This renderer uses the locally installed mermaid-cli to convert Mermaid diagrams
 * to PNG images, ensuring privacy by keeping all data local and working offline.
 */
@Composable
fun mermaidChart(
    data: MermaidChartData,
    modifier: Modifier = Modifier,
    entityId: String? = null,
) {
    val mermaidService = remember { MermaidSvgService() }
    var imageData by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isMermaidCliAvailable by remember { mutableStateOf<Boolean?>(null) }
    var zoomLevel by remember { mutableStateOf(0.5f) }
    var showFullScreenDialog by remember { mutableStateOf(false) }
    var sanitizedDiagramCode by remember { mutableStateOf("") }
    var retryCount by remember(data.diagram) { mutableStateOf(0) }
    // Holds an AI-fixed diagram to use on retry; null means use the original
    var fixedDiagram by remember(data.diagram) { mutableStateOf<String?>(null) }

    // Detect if we're in dark mode based on background luminance
    val isDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // Get the actual surface color from the app theme to match backgrounds perfectly
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backgroundColor = remember(surfaceColor) {
        val red = (surfaceColor.red * 255).toInt()
        val green = (surfaceColor.green * 255).toInt()
        val blue = (surfaceColor.blue * 255).toInt()
        "#%02x%02x%02x".format(red, green, blue)
    }

    // Re-run when diagram changes or when a fixed diagram arrives from AI retry
    LaunchedEffect(data.diagram, isDarkMode, backgroundColor, fixedDiagram) {
        isLoading = true
        error = null
        var pendingFixedDiagram: String? = null

        val theme = if (data.theme.isNotEmpty() && data.theme.lowercase() != "default") {
            data.theme.lowercase()
        } else if (isDarkMode) {
            "dark"
        } else {
            "default"
        }

        val diagramSource = fixedDiagram ?: data.diagram
        val cacheKey = DiagramCache.getCacheKey(diagramSource, theme, backgroundColor)
        val cachedImage = DiagramCache.get(cacheKey)

        if (cachedImage != null) {
            log.debug("✅ Loaded diagram from cache ({} bytes)", cachedImage.size)
            imageData = cachedImage
            error = null
            isMermaidCliAvailable = true
        } else {
            log.debug("Cache miss, checking Mermaid CLI availability...")

            withContext(Dispatchers.IO) {
                isMermaidCliAvailable = mermaidService.isMermaidCliAvailable()
            }

            log.debug("Mermaid CLI available: {}", isMermaidCliAvailable)

            if (isMermaidCliAvailable == true) {
                log.debug("Converting diagram to PNG...")
                try {
                    val rendered = mermaidService.convertToPng(diagramSource, theme, backgroundColor)
                    imageData = rendered
                    error = null

                    // Store in cache for next time
                    DiagramCache.put(cacheKey, rendered)
                    log.debug("✅ Successfully rendered and cached diagram ({} bytes)", rendered.size)

                    // If this was an AI-fixed diagram, notify so the message can be persisted
                    if (fixedDiagram != null && entityId != null) {
                        EventBus.post(
                            DiagramFixedEvent(
                                entityId = entityId,
                                originalDiagram = data.diagram,
                                fixedDiagram = fixedDiagram!!,
                            ),
                        )
                        log.debug("Posted DiagramFixedEvent for entity {}", entityId)
                    }
                } catch (_: MermaidCliNotAvailableException) {
                    log.warn("Mermaid CLI became unavailable during conversion")
                    error = "mermaid_cli_not_available"
                    isMermaidCliAvailable = false
                } catch (e: CancellationException) {
                    // Composition left — don't treat this as a diagram error, just propagate
                    log.debug("LaunchedEffect cancelled (composition left), skipping AI fix")
                    throw e
                } catch (e: Exception) {
                    val parseError = e.message ?: "Unknown render error"
                    val attemptNum = retryCount + 1
                    log.error("Failed to convert diagram (attempt {}/{}): {}", attemptNum, MAX_AI_RETRIES + 1, parseError)

                    // Only attempt AI fix up to MAX_AI_RETRIES times
                    if (retryCount < MAX_AI_RETRIES) {
                        retryCount++
                        log.debug("Asking AI to fix diagram (attempt {} of {})...", retryCount, MAX_AI_RETRIES)
                        try {
                            val fixPrompt = """
                                The following Mermaid diagram failed to render with this error:

                                ERROR:
                                $parseError

                                ORIGINAL DIAGRAM:
                                ${data.diagram}

                                Rules you MUST follow when fixing:
                                1. Emoji characters inside node labels are NOT supported — remove all emojis
                                2. Edge/link labels (inside `|...|`) with parentheses, colons, or slashes MUST be quoted:
                                   BAD:  -->|1. User Input (Query)| B
                                   GOOD: -->|"1. User Input (Query)"| B
                                3. subgraph titles MUST use ONLY the `id["Title"]` form — no unquoted text before the bracket:
                                   BAD:  subgraph Core Logic / Business Services["Core Logic"]
                                   GOOD: subgraph CoreLogic["Core Logic / Business Services"]
                                4. Node labels with colons, parentheses, or ampersands MUST be double-quoted:
                                   BAD:  B{Presentation Layer: Desktop UI}
                                   GOOD: B{"Presentation Layer: Desktop UI"}
                                5. Cylinder/database nodes `[(Label)]` — label MUST be plain text, NOT a quoted string:
                                   BAD:  F1[( "AI Provider API: OpenAI" )]
                                   GOOD: F1[(AI Provider API OpenAI)]
                                6. Do NOT use `style`, `classDef`, or `class` styling directives — remove them entirely
                                6b. If the diagram uses `requirementDiagram` or any other unsupported type (error says "UnknownDiagramError" or "No diagram type detected"), convert it to a supported equivalent — use `flowchart TD` to represent requirements as nodes and edges
                                7. Comments MUST use `%%` (double percent) on their OWN line — never after a statement, never single `%`:
                                   BAD:  % Data/Flow Connections  or  A --> B; %% comment
                                   GOOD: %% Data/Flow Connections  (on its own line)
                                8. subgraph IDs MUST be unique and MUST NOT match any node ID, AND no edge inside a subgraph may use the subgraph's own ID as source or target — all cause cycle errors:
                                   BAD:  subgraph D["Core Service Layer"] ... D --> R1  (D is both subgraph ID and edge source)
                                   BAD:  E_Models["label"] ... subgraph E_Models ... E_Models --> F1
                                   GOOD: subgraph ServiceLayer["Core Service Layer"] ... SvcNode --> R1  (ServiceLayer never used as node/edge endpoint)
                                9. An edge can only carry ONE label — never chain two label syntaxes on the same edge:
                                   BAD:  A -- "label1" --> |"label2"| B
                                   GOOD: A -->|"label1"| B   or   A -- "label1" --> B
                                10. Do NOT change the diagram type unless it is itself invalid

                                Please fix the diagram syntax and return ONLY the corrected Mermaid diagram code, with no explanation, no markdown fences, and no extra text.
                            """.trimIndent()
                            val aiFixed: String = withContext(Dispatchers.IO) {
                                withTimeout(30_000L.milliseconds) {
                                    val model = AppContext.getInstance().createChatModel()
                                    model.chat(fixPrompt)
                                }
                            }
                            // Strip any accidental code fences the AI may add
                            val cleaned = aiFixed
                                .replace(Regex("""^```(?:mermaid)?\s*\n?"""), "")
                                .replace(Regex("""\n?```\s*$"""), "")
                                .trim()
                            log.debug("AI returned fixed diagram:\n{}", cleaned)
                            pendingFixedDiagram = cleaned
                        } catch (_: TimeoutCancellationException) {
                            log.warn("AI fix timed out after 30s")
                            error = parseError
                        } catch (aiEx: CancellationException) {
                            // Composition left during AI fix — propagate, don't swallow
                            log.debug("AI fix cancelled (composition left), propagating")
                            throw aiEx
                        } catch (aiEx: Exception) {
                            log.error("AI fix attempt failed", aiEx)
                            error = parseError
                        }
                    } else {
                        log.warn("Diagram render failed after {} AI fix attempts, showing error", MAX_AI_RETRIES)
                        error = parseError
                    }
                }
            }
        }

        // Always runs — keep spinner visible only when a retry is pending
        if (pendingFixedDiagram != null) {
            fixedDiagram = pendingFixedDiagram // triggers LaunchedEffect re-run; isLoading stays true
        } else {
            isLoading = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        log.debug(
            "Rendering UI - CLI: {}, isLoading: {}, error: {}, imageData: {}",
            isMermaidCliAvailable,
            isLoading,
            error,
            imageData?.size,
        )
        when {
            // Still checking if CLI is available
            isMermaidCliAvailable == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(Spacing.large))
                        Text("Checking Mermaid CLI...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // CLI is not available - show setup instructions
            isMermaidCliAvailable == false -> {
                log.debug("Showing setup instructions (CLI not available)")
                mermaidSetupInstructions(
                    diagram = data.diagram,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Loading PNG (CLI is available, converting diagram)
            isLoading && isMermaidCliAvailable == true -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(Spacing.large))
                        Text(stringResource("mermaid.rendering.progress"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Error rendering (CLI is available but conversion failed)
            error != null && error != "mermaid_cli_not_available" && isMermaidCliAvailable == true -> {
                Box(modifier = Modifier.fillMaxSize().padding(Spacing.large), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource("mermaid.error.rendering.title"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(Spacing.large))
                        Text(
                            text = stringResource("mermaid.error.rendering.raw.label"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = data.diagram,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(Spacing.small).background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ).padding(Spacing.small),
                        )
                    }
                }
            }

            // Success - render PNG (CLI is available and conversion succeeded)
            imageData != null && isMermaidCliAvailable == true -> {
                log.debug("Rendering PNG image")
                diagramViewer(
                    imageData = imageData!!,
                    sanitizedDiagram = sanitizedDiagramCode,
                    zoomLevel = zoomLevel,
                    onZoomIn = { zoomLevel = (zoomLevel + 0.1f).coerceAtMost(3f) },
                    onZoomOut = { zoomLevel = (zoomLevel - 0.1f).coerceAtLeast(0.5f) },
                    onResetZoom = { zoomLevel = 1f },
                    onFullScreen = { showFullScreenDialog = true },
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                )

                // Full-screen dialog
                if (showFullScreenDialog && imageData != null) {
                    fullScreenDiagramDialog(
                        imageData = imageData!!,
                        sanitizedDiagram = sanitizedDiagramCode,
                        onDismiss = { showFullScreenDialog = false },
                    )
                }
            }

            // Fallback - should not normally reach here
            else -> {
                log.warn(
                    "Unexpected state - CLI: {}, isLoading: {}, error: {}, imageData: {}",
                    isMermaidCliAvailable,
                    isLoading,
                    error,
                    imageData?.size,
                )
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("mermaid.error.unexpected.state"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Composable that displays setup instructions when Mermaid CLI is not available.
 */
@Composable
private fun mermaidSetupInstructions(
    diagram: String,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val setupLink = stringResource("mermaid.setup.instructions.link")

    Column(
        modifier = modifier
            .padding(Spacing.extraLarge)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Spacing.large))

        Text(
            text = stringResource("mermaid.setup.required.title"),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Text(
            text = stringResource("mermaid.setup.privacy.note"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Spacing.extraLarge))

        // Setup instructions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(Spacing.large),
        ) {
            Text(
                text = stringResource("mermaid.setup.instructions.title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            Text(
                text = stringResource("mermaid.setup.instructions.text"),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            Text(
                text = setupLink,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(Spacing.medium),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.extraLarge))

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Button(
                onClick = {
                    try {
                        Desktop.getDesktop().browse(URI(setupLink))
                    } catch (_: Exception) {
                        // Ignore browser open failures
                    }
                },
            ) {
                Text(stringResource("mermaid.setup.button.open.guide"))
            }

            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(diagram))
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource("mermaid.setup.button.copy.diagram"))
            }
        }

        Spacer(modifier = Modifier.height(Spacing.extraLarge))

        // Show raw diagram
        Text(
            text = stringResource("mermaid.diagram.source.label"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Text(
            text = diagram,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(Spacing.medium),
        )
    }
}

/**
 * Shows a file chooser dialog and saves the image data as PNG.
 */
private fun downloadDiagramAsPng(imageData: ByteArray, log: Logger) {
    runBlocking {
        val file = FileDialogUtils.pickSavePath(
            suggestedName = "mermaid-diagram",
            extension = "png",
            title = "Save Diagram",
        ) ?: return@runBlocking
        try {
            file.writeBytes(imageData)
            log.debug("Diagram saved to: {}", file.absolutePath)
        } catch (e: Exception) {
            log.error("Failed to save diagram", e)
        }
    }
}

/**
 * Reusable diagram viewer with zoom controls and download button.
 */
@Composable
private fun diagramViewer(
    imageData: ByteArray,
    sanitizedDiagram: String,
    zoomLevel: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onFullScreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    var showCopyFeedback by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Row(modifier = modifier) {
        // Diagram
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            diagramImage(
                imageData = imageData,
                zoomLevel = zoomLevel,
                modifier = Modifier.fillMaxSize(),
            )

            // Copy feedback
            if (showCopyFeedback) {
                Text(
                    text = stringResource("mermaid.feedback.copied"),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = Spacing.large, vertical = Spacing.small),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Zoom controls
        Column(
            modifier = Modifier
                .padding(start = if (onFullScreen != null) Spacing.small else Spacing.large)
                .width(if (onFullScreen != null) 48.dp else 56.dp),
            verticalArrangement = if (onFullScreen != null) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Fullscreen button (only in inline view)
            if (onFullScreen != null) {
                themedTooltip(
                    text = stringResource("mermaid.button.expand"),
                    placement = TooltipPlacement.LEFT,
                ) {
                    IconButton(
                        onClick = onFullScreen,
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Fullscreen, stringResource("mermaid.button.expand"))
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))
            }

            // Copy diagram code button
            themedTooltip(
                text = stringResource("mermaid.button.copy.code"),
                placement = TooltipPlacement.LEFT,
            ) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(sanitizedDiagram))
                        showCopyFeedback = true
                        coroutineScope.launch {
                            delay(2000.milliseconds)
                            showCopyFeedback = false
                        }
                    },
                    modifier = Modifier
                        .size(if (onFullScreen != null) 32.dp else 48.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        stringResource("mermaid.button.copy.code"),
                        modifier = if (onFullScreen != null) Modifier else Modifier.size(32.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (onFullScreen != null) 8.dp else 16.dp))

            // Download button
            themedTooltip(
                text = stringResource("mermaid.button.download"),
                placement = TooltipPlacement.LEFT,
            ) {
                IconButton(
                    onClick = { downloadDiagramAsPng(imageData, log) },
                    modifier = Modifier
                        .size(if (onFullScreen != null) 32.dp else 48.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.Download,
                        stringResource("mermaid.button.download"),
                        modifier = if (onFullScreen != null) Modifier else Modifier.size(32.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (onFullScreen != null) 8.dp else 16.dp))

            // Zoom in
            themedTooltip(
                text = stringResource("mermaid.button.zoom.in"),
                placement = TooltipPlacement.LEFT,
            ) {
                IconButton(
                    onClick = onZoomIn,
                    modifier = Modifier
                        .size(if (onFullScreen != null) 32.dp else 48.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.Add,
                        stringResource("mermaid.button.zoom.in"),
                        modifier = if (onFullScreen != null) Modifier else Modifier.size(32.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (onFullScreen != null) 8.dp else 16.dp))

            // Reset zoom
            themedTooltip(
                text = stringResource("mermaid.button.reset.zoom"),
                placement = TooltipPlacement.LEFT,
            ) {
                IconButton(
                    onClick = onResetZoom,
                    modifier = Modifier
                        .size(if (onFullScreen != null) 32.dp else 48.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        stringResource("mermaid.button.reset.zoom"),
                        modifier = if (onFullScreen != null) Modifier else Modifier.size(32.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (onFullScreen != null) 8.dp else 16.dp))

            // Zoom out
            themedTooltip(
                text = stringResource("mermaid.button.zoom.out"),
                placement = TooltipPlacement.LEFT,
            ) {
                IconButton(
                    onClick = onZoomOut,
                    modifier = Modifier
                        .size(if (onFullScreen != null) 32.dp else 48.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.Remove,
                        stringResource("mermaid.button.zoom.out"),
                        modifier = if (onFullScreen != null) Modifier else Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

/**
 * Composable that renders PNG image data with zoom support and scrolling for large images.
 */
@Composable
private fun diagramImage(
    imageData: ByteArray,
    zoomLevel: Float,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(imageData) {
        try {
            SkiaImage.makeFromEncoded(imageData).toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    if (imageBitmap != null) {
        val scaledWidth = (imageBitmap.width * zoomLevel).dp
        val scaledHeight = (imageBitmap.height * zoomLevel).dp

        Box(modifier = modifier) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(horizontalScrollState)
                    .padding(end = Spacing.medium, bottom = Spacing.medium)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val newHorizontalScroll = (horizontalScrollState.value - dragAmount.x).coerceIn(
                                    0f,
                                    horizontalScrollState.maxValue.toFloat(),
                                )
                                val newVerticalScroll = (verticalScrollState.value - dragAmount.y).coerceIn(
                                    0f,
                                    verticalScrollState.maxValue.toFloat(),
                                )
                                horizontalScrollState.scrollTo(newHorizontalScroll.toInt())
                                verticalScrollState.scrollTo(newVerticalScroll.toInt())
                            }
                        }
                    },
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Mermaid Diagram",
                    modifier = Modifier
                        .width(scaledWidth)
                        .height(scaledHeight),
                )
            }

            // Vertical scrollbar
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd),
                adapter = rememberScrollbarAdapter(verticalScrollState),
                style = AppComponents.scrollbarStyle(),
            )

            // Horizontal scrollbar
            HorizontalScrollbar(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                adapter = rememberScrollbarAdapter(horizontalScrollState),
                style = AppComponents.scrollbarStyle(),
            )
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("mermaid.error.failed.load.image"),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Full-screen dialog to display diagram at larger size with zoom controls.
 */
@Composable
private fun fullScreenDiagramDialog(
    imageData: ByteArray,
    sanitizedDiagram: String,
    onDismiss: () -> Unit,
) {
    var dialogZoomLevel by remember { mutableStateOf(0.5f) }

    Window(
        onCloseRequest = onDismiss,
        state = rememberWindowState(
            width = 1200.dp,
            height = 800.dp,
            position = WindowPosition(Alignment.Center),
        ),
        title = "Diagram Viewer",
        resizable = true,
        alwaysOnTop = true, // Makes it modal-like
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            diagramViewer(
                imageData = imageData,
                sanitizedDiagram = sanitizedDiagram,
                zoomLevel = dialogZoomLevel,
                onZoomIn = { dialogZoomLevel = (dialogZoomLevel + 0.2f).coerceAtMost(5f) },
                onZoomOut = { dialogZoomLevel = (dialogZoomLevel - 0.2f).coerceAtLeast(0.5f) },
                onResetZoom = { dialogZoomLevel = 1f },
                onFullScreen = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.large),
            )
        }
    }
}
