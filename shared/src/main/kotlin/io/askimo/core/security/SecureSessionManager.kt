/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.security

import io.askimo.core.context.AppContextParams
import io.askimo.core.logging.logger
import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.security.SecureKeyManager.StorageMethod

/**
 * Secure wrapper for [AppContextParams] that handles API key storage/retrieval transparently.
 *
 * Each [ProviderInstance] that has an API key ([HasApiKey]) gets its own keychain entry,
 * keyed by **instance ID** rather than provider type. This allows multiple instances of
 * the same provider type (e.g., two Ollama hosts) to each store their own API key
 * independently.
 *
 * Keychain entry format: `askimo.<instanceId>` (overridable in tests via [instanceKey]).
 */
open class SecureSessionManager {
    private val log = logger<SecureSessionManager>()

    companion object {
        private const val ENCRYPTED_API_KEY_PREFIX = "encrypted:"
        private const val KEYCHAIN_API_KEY_PLACEHOLDER = "***keychain***"
    }

    /**
     * Loads session parameters and populates API keys from secure storage for every instance.
     */
    fun loadSecureSession(appContextParams: AppContextParams): AppContextParams {
        val loadedInstances = appContextParams.providerInstances.map { instance ->
            val settingsCopy = instance.settings.deepCopy()
            if (settingsCopy is HasApiKey) {
                loadApiKeyForInstance(instance.id, settingsCopy)
            }
            instance.copy(settings = settingsCopy)
        }.toMutableList()

        return appContextParams.copy(providerInstances = loadedInstances)
    }

    /**
     * Saves session parameters, storing API keys securely and replacing them with
     * placeholders in the returned copy (safe to persist to YAML).
     */
    fun saveSecureSession(appContextParams: AppContextParams): AppContextParams {
        val sanitizedInstances = appContextParams.providerInstances.map { instance ->
            val settingsCopy = instance.settings.deepCopy()
            if (settingsCopy is HasApiKey && settingsCopy.apiKey.isNotBlank()) {
                saveApiKeyForInstance(instance.id, settingsCopy)
            }
            instance.copy(settings = settingsCopy)
        }.toMutableList()

        return appContextParams.copy(providerInstances = sanitizedInstances)
    }

    /**
     * Returns the keychain storage key for a given instance ID.
     * Override in tests to use a safe prefix and avoid touching real keychain entries.
     */
    protected open fun instanceKey(instanceId: String): String = "instance.$instanceId"

    private fun loadApiKeyForInstance(instanceId: String, settings: HasApiKey) {
        val currentKey = settings.apiKey

        // Skip if already loaded (actual key) or empty
        if (currentKey.isBlank() || isActualApiKey(currentKey)) return

        val secureKey = SecureKeyManager.retrieveSecretKey(instanceKey(instanceId))
        if (secureKey != null) {
            settings.apiKey = secureKey
            log.trace("Loaded API key for instance $instanceId from secure storage")
        } else if (currentKey.startsWith(ENCRYPTED_API_KEY_PREFIX)) {
            val encryptedPart = currentKey.removePrefix(ENCRYPTED_API_KEY_PREFIX)
            val decryptedKey = EncryptionManager.decrypt(encryptedPart)
            if (decryptedKey != null) {
                settings.apiKey = decryptedKey
                log.debug("Decrypted legacy API key for instance $instanceId")
            } else {
                log.warn("Failed to decrypt API key for instance $instanceId")
                settings.apiKey = ""
            }
        }
    }

    private fun saveApiKeyForInstance(instanceId: String, settings: HasApiKey) {
        val apiKey = settings.apiKey

        // Skip if it's already a placeholder or empty
        if (!isActualApiKey(apiKey)) return

        val result = SecureKeyManager.storeSecuredKey(instanceKey(instanceId), apiKey)

        if (result.success) {
            updateApiKeyPlaceholder(settings, result.method)
            result.warningMessage?.let { message -> log.warn(message) }
        } else {
            val encrypted = EncryptionManager.encrypt(apiKey)
            if (encrypted != null) {
                settings.apiKey = "$ENCRYPTED_API_KEY_PREFIX$encrypted"
                log.warn("⚠️ Storing encrypted API key for instance $instanceId in config file (less secure)")
            } else {
                log.warn("❌ Failed to encrypt API key for instance $instanceId — will be stored as plain text")
            }
        }
    }

    private fun updateApiKeyPlaceholder(settings: HasApiKey, method: StorageMethod) {
        settings.apiKey = when (method) {
            StorageMethod.KEYCHAIN -> KEYCHAIN_API_KEY_PLACEHOLDER
            StorageMethod.ENCRYPTED -> KEYCHAIN_API_KEY_PLACEHOLDER
            StorageMethod.INSECURE_FALLBACK -> settings.apiKey // Keep as-is
        }
    }

    private fun isActualApiKey(apiKey: String): Boolean = apiKey.isNotBlank() &&
        apiKey != KEYCHAIN_API_KEY_PLACEHOLDER &&
        !apiKey.startsWith(ENCRYPTED_API_KEY_PREFIX)
}
