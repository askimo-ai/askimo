/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.security

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides encryption/decryption for API keys when keychain storage is not available.
 * Uses AES-256-GCM encryption with a key derived from system properties.
 */
object EncryptionManager {
    private val log = logger<EncryptionManager>()

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16

    /**
     * Gets the path to the encryption key file.
     * This is a function rather than a property to ensure the path is always fresh,
     * especially important for tests that may modify the file system.
     */
    private fun keyPath(): Path = AskimoHome.encryptionKeyFile()

    /**
     * Encrypts an API key using AES-256-GCM.
     *
     * @param plaintext The API key to encrypt
     * @return Base64-encoded encrypted data, or null if encryption fails
     */
    fun encrypt(plaintext: String): String? = try {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)

        // Generate random IV
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val encryptedData = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combine IV + encrypted data
        val combined = iv + encryptedData
        Base64.getEncoder().encodeToString(combined)
    } catch (e: Exception) {
        log.error("Failed to encrypt API key: ${e.message}", e)
        null
    }

    /**
     * Decrypts an encrypted API key.
     *
     * @param ciphertext Base64-encoded encrypted data
     * @return Decrypted API key, or null if decryption fails
     */
    fun decrypt(ciphertext: String): String? = try {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)

        val combined = Base64.getDecoder().decode(ciphertext)

        // Extract IV and encrypted data
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val encryptedData = combined.sliceArray(GCM_IV_LENGTH until combined.size)

        val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedData = cipher.doFinal(encryptedData)
        String(decryptedData, Charsets.UTF_8)
    } catch (e: Exception) {
        log.error("Failed to decrypt API key: ${e.message}", e)
        null
    }

    /**
     * Gets the secret key from file, or creates a new one if it doesn't exist.
     */
    private fun getOrCreateSecretKey(): SecretKey = if (Files.exists(keyPath())) {
        loadSecretKey()
    } else {
        createAndSaveSecretKey()
    }

    /**
     * Creates a new AES-256 secret key and saves it to file.
     */
    private fun createAndSaveSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(256) // AES-256
        val secretKey = keyGenerator.generateKey()

        // Create directory if it doesn't exist
        Files.createDirectories(keyPath().parent)

        // Save the key (this is still a security risk, but better than plain text)
        val encoded = Base64.getEncoder().encodeToString(secretKey.encoded)
        Files.write(keyPath(), encoded.toByteArray())

        // Set restrictive permissions (owner only)
        try {
            val file = keyPath().toFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (e: Exception) {
            log.error("Failed to set restrictive permissions on key file: ${e.message}", e)
        }

        return secretKey
    }

    /**
     * Loads the secret key from file.
     */
    private fun loadSecretKey(): SecretKey {
        val encoded = String(Files.readAllBytes(keyPath()))
        val keyBytes = Base64.getDecoder().decode(encoded)
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    /**
     * Deletes the encryption key file.
     */
    fun deleteKey(): Boolean = try {
        if (Files.exists(keyPath())) {
            Files.delete(keyPath())
            true
        } else {
            false
        }
    } catch (e: Exception) {
        log.error("Failed to delete encryption key: ${e.message}", e)
        false
    }
}
