/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.knowledgesource

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.IndexRemovalEvent
import io.askimo.core.event.internal.IndexingRequestedEvent
import io.askimo.core.rag.container.IndexingContainerType

/**
 * Diff an old and new set of knowledge sources for a container (Project or Resource
 * Collection) and post the appropriate indexing/removal events for the difference.
 *
 * This centralizes the "what changed" logic so every edit dialog (Project, Resource
 * Collection, ...) triggers indexing consistently instead of duplicating the diff
 * and event-posting logic per feature.
 *
 * @param containerId The id of the project or resource collection being edited
 * @param containerType Whether this container is a [IndexingContainerType.PROJECT] or [IndexingContainerType.RESOURCE_COLLECTION]
 * @param oldSources The knowledge sources before the edit
 * @param newSources The knowledge sources after the edit
 * @param watchForChanges Whether newly added sources should be watched for filesystem changes
 */
fun applyKnowledgeSourceDiff(
    containerId: String,
    containerType: IndexingContainerType,
    oldSources: List<KnowledgeSourceConfig>,
    newSources: List<KnowledgeSourceConfig>,
    watchForChanges: Boolean = true,
) {
    // Diff by stable identity (type + resourceIdentifier), not full config equality —
    // otherwise a watch-only toggle (part of equals()/hashCode()) would look like
    // remove+add and trigger a spurious delete + re-index. Watcher changes are handled
    // separately via KnowledgeSourceWatchToggledEvent.
    fun identity(config: KnowledgeSourceConfig) = config::class to config.resourceIdentifier

    val oldByIdentity = oldSources.associateBy(::identity)
    val newByIdentity = newSources.associateBy(::identity)

    val removedSources = oldByIdentity.filterKeys { it !in newByIdentity }.values
    val addedSources = newByIdentity.filterKeys { it !in oldByIdentity }.values

    // Emit removal events for deleted sources
    removedSources.forEach { source ->
        EventBus.post(
            IndexRemovalEvent(
                containerId = containerId,
                containerType = containerType,
                knowledgeSource = source,
                reason = "Knowledge source removed by user",
            ),
        )
    }

    // Emit indexing events for newly added sources
    if (addedSources.isNotEmpty()) {
        EventBus.post(
            IndexingRequestedEvent(
                containerId = containerId,
                containerType = containerType,
                knowledgeSources = addedSources.toList(),
                watchForChanges = watchForChanges,
            ),
        )
    }
}
