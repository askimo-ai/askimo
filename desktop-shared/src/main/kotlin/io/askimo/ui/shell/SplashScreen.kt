/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import io.askimo.core.VersionInfo
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import org.jetbrains.skia.Image

/**
 * Full-screen splash shown immediately on launch before the app is ready.
 *
 * Displays the Askimo logo, app name, and version in the centre.
 * When [isConnecting] is true a subtle "Connecting…" label with a small
 * spinner appears in the bottom-right corner — used by the Pro edition
 * while it restores the server session.
 */
@Composable
fun splashScreen(isConnecting: Boolean = false) {
    val bitmap = remember {
        Image.makeFromEncoded(
            object {}.javaClass.getResourceAsStream("/images/askimo_512.png")?.readBytes()
                ?: error("askimo_512.png not found"),
        ).toComposeImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = "Askimo logo",
                modifier = Modifier.size(80.dp),
            )
            Text(
                text = VersionInfo.name,
                style = AppTextStyles.pageTitle,
            )
            Text(
                text = "v${VersionInfo.version}",
                style = AppTextStyles.caption,
            )
        }

        if (isConnecting) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.extraLarge),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                AppComponents.loadingSpinner(size = 16.dp)
                Text(
                    text = "Connecting…",
                    style = AppTextStyles.caption,
                )
            }
        }
    }
}
