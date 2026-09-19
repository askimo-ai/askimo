/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * Composition local that is `true` when a background image is active.
 * Components can read this to make themselves semi-transparent.
 */
val LocalBackgroundActive = compositionLocalOf { false }

/**
 * Wraps [content] with an optional full-app background image.
 *
 * Also establishes the app-wide default [LocalContentColor] (`onSurface`), since this
 * composable wraps the entire app with a plain `Box` rather than a `Surface` — without it
 * [AppTextStyles] tokens would fall back to plain black.
 *
 * When [backgroundImage] is [BackgroundImage.None], this is a transparent pass-through.
 * Otherwise, two layers sit between the photo and the app content:
 * 1. The image fills the space with [ContentScale.Crop].
 * 2. A theme-aware semi-transparent overlay keeps text readable: dark overlay
 *    ([DARK_OVERLAY_ALPHA]) for dark themes so white text pops, light overlay
 *    ([LIGHT_OVERLAY_ALPHA]) for light themes so dark text stays crisp.
 *
 * Background images are intentionally independent of the active [ThemeMode].
 *
 * @param useDarkMode Whether the active colour scheme is dark, so the right overlay is chosen.
 */

@Composable
fun appBackground(
    backgroundImage: BackgroundImage,
    useDarkMode: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    if (backgroundImage is BackgroundImage.None) {
        Box(modifier = modifier) {
            CompositionLocalProvider(LocalContentColor provides onSurface, content = content)
        }
        return
    }

    val painter = remember(backgroundImage) {
        try {
            val bytes: ByteArray? = when (backgroundImage) {
                is BackgroundImage.Preset -> {
                    // Bundled classpath resource — try context classloader, then fall back
                    // to an anonymous object (for modules depending on this one).
                    Thread.currentThread().contextClassLoader
                        ?.getResourceAsStream(backgroundImage.resourcePath)
                        ?.readBytes()
                        ?: object {}.javaClass
                            .getResourceAsStream("/${backgroundImage.resourcePath}")
                            ?.readBytes()
                }

                is BackgroundImage.Custom -> {
                    val file = File(backgroundImage.filePath)
                    if (file.exists()) file.readBytes() else null
                }
            }
            if (bytes != null) {
                BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // Overlay colour/alpha depends on theme: dark → dark overlay so white text stands out;
    // light → light overlay so dark text stands out without overpowering the photo.
    val overlayColor = if (useDarkMode) {
        Color.Black.copy(alpha = DARK_OVERLAY_ALPHA)
    } else {
        Color.White.copy(alpha = LIGHT_OVERLAY_ALPHA)
    }

    Box(modifier = modifier) {
        // Layer 1: background image
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Layer 2: theme-aware overlay so UI stays readable regardless of photo brightness
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor),
            )
        }

        // Layer 3: actual app content — tell children a background image is active
        CompositionLocalProvider(
            LocalBackgroundActive provides (painter != null),
            LocalContentColor provides onSurface,
            content = content,
        )
    }
}

/**
 * Overlay alpha for **dark** themes — dims bright photo areas so white text stays readable.
 */
private const val DARK_OVERLAY_ALPHA = 0.60f

/**
 * Overlay alpha for **light** themes — keeps the image visible while giving dark text a
 * clean surface to sit against.
 */
private const val LIGHT_OVERLAY_ALPHA = 0.65f
