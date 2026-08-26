/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LocalFoldersKnowledgeSourceConfig] is a data class, so `==` compares ALL properties,
 * including [LocalFoldersKnowledgeSourceConfig.watchForChanges]. This matters because
 * `ProjectIndexer.handleWatchToggleEvent` looks up the coordinator for a toggled source
 * via `coordinators.find { it.knowledgeSourceConfig == event.knowledgeSource }` — and
 * `event.knowledgeSource` is the ALREADY-toggled copy (new watchForChanges value), while
 * the live coordinator still holds the config as it was when the source was indexed
 * (old watchForChanges value). This test proves those two are never `==` equal, which
 * means the coordinator lookup by full equality can never find a match after a toggle.
 */
class LocalFoldersKnowledgeSourceConfigEqualityTest {

    @Test
    fun `toggling watchForChanges via copy breaks full equality`() {
        val original = LocalFoldersKnowledgeSourceConfig(
            resourceIdentifier = "/Users/denisfrolkov/StudioProjects/askimo/test_rmd",
            watchForChanges = true,
        )
        val afterToggle = original.copy(watchForChanges = false)

        assertFalse(
            original == afterToggle,
            "A LocalFoldersKnowledgeSourceConfig and its watchForChanges-toggled copy " +
                "should NOT be == equal, since watchForChanges is a compared property " +
                "of this data class",
        )
    }

    @Test
    fun `resourceIdentifier stays equal across the toggle`() {
        val original = LocalFoldersKnowledgeSourceConfig(
            resourceIdentifier = "/Users/denisfrolkov/StudioProjects/askimo/test_rmd",
            watchForChanges = true,
        )
        val afterToggle = original.copy(watchForChanges = false)

        assertTrue(
            original.resourceIdentifier == afterToggle.resourceIdentifier,
            "resourceIdentifier is the only stable identity field across a watch toggle " +
                "— coordinator lookup must match on this, not on full object equality",
        )
    }
}
