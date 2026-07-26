/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.di

import io.askimo.core.chat.service.ChatDirectiveService
import io.askimo.core.chat.service.ChatSessionExporterService
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.desktop.settings.AIProviderViewModel
import io.askimo.test.extensions.AskimoTestHome
import io.askimo.ui.session.SessionManager
import io.askimo.ui.session.SessionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.test.KoinTest
import kotlin.test.assertNotNull

/**
 * Test to verify all Koin modules are properly configured.
 *
 * This test ensures:
 * - All dependencies can be resolved
 * - No circular dependencies exist
 * - All services are registered correctly
 *
 * This prevents runtime errors like "No definition found for type X"
 */
@AskimoTestHome
class DesktopModuleTest : KoinTest {

    @BeforeEach
    fun setUp() {
        // Reset AppContext before each test
        AppContext.reset()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        // Clean up AppContext after each test
        AppContext.reset()
    }

    @Test
    fun `verify all services can be instantiated`() {
        AppContext.initialize(ExecutionMode.STATEFUL_TOOLS_MODE)

        val koin = startKoin {
            modules(allDesktopModules)
        }.koin

        // Verify services can be retrieved
        assertNotNull(koin.get<AppContext>())
        assertNotNull(koin.get<SessionManager>())
        assertNotNull(koin.get<ChatSessionService>())
        assertNotNull(koin.get<ChatSessionExporterService>())
        assertNotNull(koin.get<ChatDirectiveService>())
    }

    @Test
    fun `verify ViewModels can be instantiated with parameters`() {
        AppContext.initialize(ExecutionMode.STATEFUL_TOOLS_MODE)

        val koin = startKoin {
            modules(allDesktopModules)
        }.koin

        val scope = CoroutineScope(Dispatchers.Default)
        val sessionManager = koin.get<SessionManager>()

        assertNotNull(sessionManager)

        assertNotNull(
            koin.get<SessionsViewModel> {
                parametersOf(
                    scope,
                    sessionManager,
                    { "test-session-id" },
                    { }, // onRenameComplete callback
                )
            },
        )
        assertNotNull(koin.get<AIProviderViewModel> { parametersOf(scope) })
    }
}
