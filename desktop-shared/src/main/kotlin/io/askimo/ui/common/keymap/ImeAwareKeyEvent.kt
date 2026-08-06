/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.keymap

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange

/**
 * A drop-in replacement for [onPreviewKeyEvent] that automatically suppresses shortcut
 * interception during active IME composition sessions (Korean, Japanese, Chinese, etc.).
 *
 * ## Why this matters
 * On Compose Multiplatform desktop, [onPreviewKeyEvent] fires for **every** AWT key event,
 * including those that occur while the IME is mid-composition (e.g. assembling a Korean
 * syllable). If a shortcut handler consumes Space or Enter at that point, the IME never
 * receives the event and the composed character is dropped or joined to the next word.
 *
 * ## Usage
 * Replace every `.onPreviewKeyEvent { ... }` on a text field (or its ancestor) with:
 * ```kotlin
 * .onImeAwarePreviewKeyEvent(textFieldValue.composition) { keyEvent -> ... }
 * ```
 *
 * @param composition Pass [androidx.compose.ui.text.input.TextFieldValue.composition].
 *   When non-null the IME has an active composition in progress and all key events are
 *   passed through unconsumed to let the IME handle them natively.
 * @param onKeyEvent The shortcut handler — identical contract to [onPreviewKeyEvent].
 *   Return `true` to consume the event, `false` to let it propagate.
 */
fun Modifier.onImeAwarePreviewKeyEvent(
    composition: TextRange?,
    onKeyEvent: (KeyEvent) -> Boolean,
): Modifier = onPreviewKeyEvent { keyEvent ->
    if (composition != null) return@onPreviewKeyEvent false
    onKeyEvent(keyEvent)
}
