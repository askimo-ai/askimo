/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.providers.ModelProvider
import java.time.Instant

/**
 * Fired by [io.askimo.core.providers.ModelCapabilitiesCache] after the image-generation capability
 * probe completes for a model. UI components (e.g. ChatInputField) can listen for this to
 * reactively show/hide/enable the image generation button.
 *
 * @param supportsNativeImage true if model can generate images natively in-chat (multi-modal),
 *                           false if requires explicit toggle mode
 */
data class ImageCapabilityDetectedEvent(
    val provider: ModelProvider,
    val model: String,
    val supportsNativeImage: Boolean,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Image capability for $model ($provider): native=$supportsNativeImage"
}
