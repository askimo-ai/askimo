/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.di

import io.askimo.core.rag.RagIndexer
import org.koin.dsl.module

/**
 * Koin module for RAG (Retrieval-Augmented Generation) components in the desktop application.
 */
val desktopRagModule = module {
    single(createdAtStart = true) {
        RagIndexer(
            appContext = get(),
            projectRepository = get(),
            resourceCollectionRepository = get(),
        )
    }
}
