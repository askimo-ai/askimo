/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider

/** Factory for a specific [SpeechToTextService] implementation, keyed by [provider]. */
interface SpeechToTextFactory {
    val provider: VoiceProvider
    fun create(config: VoiceConfig): SpeechToTextService
}
