/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.security

import io.askimo.core.logging.logger
import io.askimo.core.security.SecureKeyManager.StorageMethod.ENCRYPTED
import io.askimo.core.security.SecureKeyManager.StorageMethod.INSECURE_FALLBACK
import io.askimo.core.security.SecureKeyManager.StorageMethod.KEYCHAIN
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Secure API key storage manager that tries keychain first, then falls back to encryption.
 * Provides warnings when API keys are stored insecurely.
 */
object SecureKeyManager {
    private val log = logger<SecureKeyManager>()

    private fun encryptedStorageFile(): Path = AskimoHome.base().resolve(".encrypted-keys")

    enum class StorageMethod {
        KEYCHAIN,
        ENCRYPTED,
        INSECURE_FALLBACK,
    }

    data class StorageResult(
        val success: Boolean,
        val method: StorageMethod,
        val warningMessage: String? = null,
    )

    /**
     * Stores an API key securely, trying keychain first, then encryption fallback.
     *
     * @param keyIdentifier The unique name (e.g., "openai", "anthropic")
     * @param secretKey The API key to store
     * @return StorageResult indicating success/failure and storage method used
     */
    fun storeSecuredKey(
        keyIdentifier: String,
        secretKey: String,
    ): StorageResult {
        // Try keychain first
        if (KeychainManager.storeSecretKey(keyIdentifier, secretKey)) {
            log.debug("✅ API key for $keyIdentifier stored securely in system keychain")
            return StorageResult(true, KEYCHAIN)
        }

        // Fallback to encryption
        val encryptedKey = EncryptionManager.encrypt(secretKey)
        if (encryptedKey != null) {
            if (storeEncryptedKey(keyIdentifier, encryptedKey)) {
                log.warn("⚠️ System keychain not available. API key for $keyIdentifier stored with encryption (less secure than keychain)")
                return StorageResult(
                    success = true,
                    method = ENCRYPTED,
                    warningMessage = "API key stored with encryption - not as secure as system keychain",
                )
            }
        }

        // Both methods failed
        log.warn("❌ Both keychain and encryption failed. API key for $keyIdentifier will be stored as plain text")
        return StorageResult(
            success = false,
            method = INSECURE_FALLBACK,
            warningMessage = "API key will be stored as PLAIN TEXT - this is INSECURE",
        )
    }

    /**
     * Retrieves an secret key, trying keychain first, then encryption fallback.
     *
     * @param keyIdentifier The unique name
     * @return The secret key if found, null otherwise
     */
    fun retrieveSecretKey(keyIdentifier: String): String? {
        // Try keychain first
        KeychainManager.retrieveSecretKey(keyIdentifier)?.let { return it }

        // Try encrypted storage
        return retrieveEncryptedKey(keyIdentifier)
    }

    /**
     * Removes an secret key from secure storage.
     *
     * @param keyIdentifier The unique name
     * @return true if removed from any secure storage method
     */
    fun removeSecretKey(keyIdentifier: String): Boolean {
        val keychainRemoved = KeychainManager.removeSecretKey(keyIdentifier)
        val encryptedRemoved = removeEncryptedKey(keyIdentifier)
        return keychainRemoved || encryptedRemoved
    }

    /**
     * Stores an encrypted key to the encrypted storage file.
     */
    private fun storeEncryptedKey(keyIdentifier: String, encryptedKey: String): Boolean = try {
        val properties = loadEncryptedStorage()
        properties.setProperty(keyIdentifier, encryptedKey)
        saveEncryptedStorage(properties)
        true
    } catch (e: Exception) {
        log.error("Failed to store encrypted key for $keyIdentifier: ${e.message}", e)
        false
    }

    /**
     * Retrieves and decrypts a key from the encrypted storage file.
     */
    private fun retrieveEncryptedKey(provider: String): String? = try {
        val properties = loadEncryptedStorage()
        val encryptedKey = properties.getProperty(provider) ?: return null
        EncryptionManager.decrypt(encryptedKey)
    } catch (e: Exception) {
        log.error("Failed to retrieve encrypted key for $provider: ${e.message}", e)
        null
    }

    /**
     * Removes an encrypted key from the storage file.
     */
    private fun removeEncryptedKey(keyIdentifier: String): Boolean = try {
        val properties = loadEncryptedStorage()
        val removed = properties.remove(keyIdentifier) != null
        if (removed) {
            saveEncryptedStorage(properties)
        }
        removed
    } catch (e: Exception) {
        log.error("Failed to remove encrypted key for $keyIdentifier: ${e.message}", e)
        false
    }

    /**
     * Loads the encrypted storage properties file.
     */
    private fun loadEncryptedStorage(): Properties {
        val properties = Properties()
        val storageFile = encryptedStorageFile()
        if (Files.exists(storageFile)) {
            Files.newInputStream(storageFile).use { input ->
                properties.load(input)
            }
        }
        return properties
    }

    /**
     * Saves the encrypted storage properties file with restrictive permissions.
     */
    private fun saveEncryptedStorage(properties: Properties) {
        val storageFile = encryptedStorageFile()
        // Create directory if it doesn't exist
        Files.createDirectories(storageFile.parent)

        // Save the properties
        Files.newOutputStream(storageFile).use { output ->
            properties.store(output, "Encrypted API Keys - Do Not Edit")
        }

        // Set restrictive permissions (owner only)
        try {
            val file = storageFile.toFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (e: Exception) {
            log.error("Failed to set restrictive permissions on encrypted storage file: ${e.message}", e)
        }
    }

    /**
     * Gets a user-friendly description of the storage security level.
     */
    fun getStorageSecurityDescription(method: StorageMethod): String = when (method) {
        KEYCHAIN -> "🔒 Secure (System Keychain)"
        ENCRYPTED -> "🔐 Encrypted (Local Key)"
        INSECURE_FALLBACK -> "⚠️ INSECURE (Plain Text)"
    }
}
