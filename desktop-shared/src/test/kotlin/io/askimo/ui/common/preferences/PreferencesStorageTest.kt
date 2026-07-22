/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.preferences

import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.inputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreferencesStorageTest {
    @Test
    fun `application preferences use Askimo home property file`() {
        val root = Files.createTempDirectory("askimo-app-prefs")

        AskimoHome.withTestBase(root).use {
            ApplicationPreferences.clearAll()
            ApplicationPreferences.setProjectSidePanelWidth(512)

            val file = AskimoHome.base().resolve("prefs/app.properties")
            assertTrue(Files.isRegularFile(file))
            assertEquals("512", load(file).getProperty("ui.project_side_panel_width"))
            assertEquals(512, ApplicationPreferences.getProjectSidePanelWidth())
        }
    }

    @Test
    fun `account and device preferences use isolated property files`() {
        val root = Files.createTempDirectory("askimo-account-prefs")

        AskimoHome.withTestBase(root).use {
            val account = AccountPreferences.forAccount("User Name@example.com")
            account.saveLastSyncSeq(42)
            AccountPreferences.device().setHardwareAccelerationEnabled(false)

            val accountsDir = AskimoHome.base().resolve("prefs/accounts")
            val accountFile = accountsDir.resolve("user_name@example.com.properties")
            val deviceFile = accountsDir.resolve("__device__.properties")
            assertEquals("42", load(accountFile).getProperty("sync.last_seq"))
            assertEquals("false", load(deviceFile).getProperty("perf.hardware_acceleration_enabled"))
            assertFalse(AccountPreferences.device().getHardwareAccelerationEnabled())
        }
    }

    private fun load(path: java.nio.file.Path): Properties = Properties().also { properties ->
        path.inputStream().use(properties::load)
    }
}
