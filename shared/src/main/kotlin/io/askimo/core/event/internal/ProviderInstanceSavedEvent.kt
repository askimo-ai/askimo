/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Emitted  after a provider instance is successfully saved (created or updated).
 *
 * Consumers (e.g. [io.askimo.desktop.settings.AIProviderViewModel]) use this to refresh
 * their active-configuration display without direct coupling to the wizard.
 */
data class ProviderInstanceSavedEvent(
    /** Stable ID of the saved [io.askimo.core.providers.ProviderInstance]. */
    val instanceId: String,
    /** User-visible display name of the saved instance. */
    val displayName: String,
    /** True if the instance was newly created; false if an existing one was updated. */
    val isNewInstance: Boolean,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails() = "Provider instance saved: $displayName (new=$isNewInstance, id=$instanceId)"
}
