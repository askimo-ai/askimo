/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider

/** Factory for a specific [TextToSpeechService] implementation, keyed by [provider]. */
interface TextToSpeechFactory {
    val provider: VoiceProvider
    fun create(config: VoiceConfig): TextToSpeechService
}
