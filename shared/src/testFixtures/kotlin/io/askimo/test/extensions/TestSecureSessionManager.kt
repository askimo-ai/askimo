/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.test.extensions

import io.askimo.core.security.SecureSessionManager

/**
 * Test-safe subclass of [SecureSessionManager] that prefixes all keychain keys with "test_"
 * to prevent test runs from reading or overwriting a developer's real API keys in the keychain.
 */
class TestSecureSessionManager : SecureSessionManager() {
    override fun instanceKey(instanceId: String): String = "test_instance.$instanceId"
}
