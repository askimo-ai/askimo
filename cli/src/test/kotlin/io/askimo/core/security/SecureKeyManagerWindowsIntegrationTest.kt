/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.security

import io.askimo.core.util.ProcessBuilderExt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertNotNull
import java.io.IOException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecureKeyManagerWindowsIntegrationTest {

    private val testProviders = mutableSetOf<String>()

    companion object {
        private const val TEST_PROVIDER_PREFIX = "test-provider"
        private const val LONG_API_KEY = "sk-proj-xR7nK4mP9wQ2vE8bF5jL3tY6uA1sD0gH-zX9cV2bN4mK7pQ1wE5rT8yU3iO6aS2dF-vG4hJ8kL1nM0pR7tY2uI9oE3wQ6aS5dF8gH1jK4mP7xZ0cV3bN6mK9pQ2wE5rT-yU8iO1aS4dF7gH0jK3mP6xZ9cV2bN5mK8pQ1wE4rT7yU0iO3aS6dF9gH2jK5mP8xZ1cV4bN7mKpQ0wE3rT6yU9iO2aS5dF8gH1jK4mP7xZ0cV3bN6mKpQ2wE5rT8yU1iO4aS7dF0gH3jK6mP9xZ2cV5bN8mKpQ1wE4rT7yU0iO3aS6dF9gH2jK5mP8xZ1cV4bN7mKpQ0wE3rT6yU9iO2aS5dF8gH1jK4mP7xZ0cV3bN6mKpQ2wE5rT8yU1iO4aS7dF0gH3jK6mP9xZ2cV5bN8mKpQ1wE4rT7yU0iO3aS6dF9gH2jK5mP8xZ1cV4bN7mKpQ0wE3rT6yU9iO2aS5dF8gH1jK4mP7xZ0cV3bN6mKpQ2wE"
    }

    @BeforeEach
    fun setUp() {
        // Skip tests if not running on Windows
        assumeTrue(isWindows(), "Tests only run on Windows")

        // Verify cmdkey command is available
        assumeTrue(isCmdKeyCommandAvailable(), "Windows cmdkey command not available")
    }

    @AfterEach
    fun tearDown() {
        // Clean up all test providers that were created during tests
        testProviders.forEach { provider ->
            try {
                SecureKeyManager.removeSecretKey(provider)
            } catch (e: Exception) {
                println("Warning: Failed to clean up test provider $provider: ${e.message}")
            }
        }
        testProviders.clear()
    }

    @Test
    fun `test SecureApiKeyManager end-to-end with long OpenAI API key`() {
        val provider = generateTestProvider("openai-e2e")

        println("=== SecureKeyManager End-to-End Test (Windows) ===")
        println("Testing with long API key:")
        println("Original length: ${LONG_API_KEY.length}")
        println("Original prefix: ${LONG_API_KEY.take(20)}...")
        println("Original suffix: ...${LONG_API_KEY.takeLast(20)}")

        // Store using SecureKeyManager (this is what the application uses)
        val storeResult = SecureKeyManager.storeSecuredKey(provider, LONG_API_KEY)
        println("Store result: $storeResult")
        assertTrue(storeResult.success, "SecureKeyManager should successfully store the API key")

        // On Windows, it might use different storage methods depending on what's available
        println("Storage method used: ${storeResult.method}")
        if (storeResult.warningMessage != null) {
            println("Warning: ${storeResult.warningMessage}")
        }

        // Retrieve using SecureKeyManager
        val retrievedKey = SecureKeyManager.retrieveSecretKey(provider)
        println("Retrieved key is null: ${retrievedKey == null}")
        assertNotNull(retrievedKey, "Retrieved API key should not be null")

        println("Retrieved length: ${retrievedKey.length}")
        println("Retrieved prefix: ${retrievedKey.take(20)}...")
        println("Retrieved suffix: ...${retrievedKey.takeLast(20)}")

        // Detailed comparison
        println("\n=== Detailed Comparison ===")
        println("Length match: ${LONG_API_KEY.length == retrievedKey.length}")
        println("Content match: ${LONG_API_KEY == retrievedKey}")

        if (LONG_API_KEY != retrievedKey) {
            println("\n=== MISMATCH ANALYSIS ===")
            val minLength = minOf(LONG_API_KEY.length, retrievedKey.length)
            for (i in 0 until minLength) {
                if (LONG_API_KEY[i] != retrievedKey[i]) {
                    println("First difference at position $i:")
                    println("Expected: '${LONG_API_KEY[i]}' (${LONG_API_KEY[i].code})")
                    println("Got: '${retrievedKey[i]}' (${retrievedKey[i].code})")
                    break
                }
            }
            if (LONG_API_KEY.length != retrievedKey.length) {
                println("Length difference: expected ${LONG_API_KEY.length}, got ${retrievedKey.length}")
            }
        }

        assertEquals(LONG_API_KEY.length, retrievedKey.length, "Retrieved API key length should match original")
        assertEquals(LONG_API_KEY, retrievedKey, "Retrieved API key should match stored key exactly")
    }

    @Test
    fun `test retrieve non-existent key through SecureApiKeyManager`() {
        val provider = generateTestProvider("non-existent")

        val retrievedKey = SecureKeyManager.retrieveSecretKey(provider)
        assertNull(retrievedKey, "Non-existent API key should return null")
    }

    @Test
    fun `test remove API key through SecureApiKeyManager`() {
        val provider = generateTestProvider("remove-test")
        val testKey = "sk-test-key-123"

        // Store the key
        val storeResult = SecureKeyManager.storeSecuredKey(provider, testKey)
        assertTrue(storeResult.success, "Should store key successfully")

        // Verify it's stored
        val retrievedKey = SecureKeyManager.retrieveSecretKey(provider)
        assertEquals(testKey, retrievedKey, "Key should be retrievable after storing")

        // Remove the key
        val removeResult = SecureKeyManager.removeSecretKey(provider)
        assertTrue(removeResult, "Should remove key successfully")

        // Verify it's removed
        val retrievedAfterRemoval = SecureKeyManager.retrieveSecretKey(provider)
        assertNull(retrievedAfterRemoval, "Key should not be retrievable after removal")
    }

    @Test
    fun `test storage method detection on Windows`() {
        val provider = generateTestProvider("storage-method")

        val storeResult = SecureKeyManager.storeSecuredKey(provider, LONG_API_KEY)
        assertTrue(storeResult.success, "Should store successfully")

        // On Windows, the storage method depends on what's available
        println("Storage method used on Windows: ${storeResult.method}")

        // Common storage methods on Windows: KEYCHAIN (if cmdkey works), ENCRYPTED, or INSECURE_FALLBACK
        assertTrue(
            storeResult.method in listOf(
                SecureKeyManager.StorageMethod.KEYCHAIN,
                SecureKeyManager.StorageMethod.ENCRYPTED,
                SecureKeyManager.StorageMethod.INSECURE_FALLBACK,
            ),
            "Should use a valid storage method",
        )
    }

    private fun generateTestProvider(suffix: String): String {
        val provider = "$TEST_PROVIDER_PREFIX-$suffix-${System.currentTimeMillis()}"
        testProviders.add(provider)
        return provider
    }

    private fun isWindows(): Boolean {
        val osName = System.getProperty("os.name").lowercase()
        return osName.contains("windows")
    }

    private fun isCmdKeyCommandAvailable(): Boolean = try {
        val process = ProcessBuilderExt("where", "cmdkey").start()
        process.waitFor() == 0
    } catch (e: IOException) {
        false
    }
}
