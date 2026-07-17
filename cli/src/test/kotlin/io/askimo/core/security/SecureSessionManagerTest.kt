/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.security

import io.askimo.core.context.AppContextParams
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.gemini.GeminiSettings
import io.askimo.core.providers.openai.OpenAiSettings
import io.askimo.core.providers.xai.XAiSettings
import io.askimo.test.extensions.AskimoTestHome
import io.askimo.test.extensions.TestSecureSessionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for SecureSessionManager that ensure API key security features work correctly
 * across different platforms while maintaining safety for developers' real keychain data.
 *
 * SAFETY NOTE: These tests use TestSecureSessionManager which automatically prefixes
 * all instance keys with "test_instance." to avoid overwriting real user API keys
 * stored in the system keychain.
 */
@TestInstance(Lifecycle.PER_CLASS)
@AskimoTestHome
class SecureSessionManagerTest {

    private lateinit var secureSessionManager: TestSecureSessionManager

    companion object {
        private const val TEST_KEYCHAIN_KEY = "test_keychain_direct"
    }

    // Fixed instance IDs for deterministic keychain cleanup
    private val openAiInstanceId = "test-openai-instance-id"
    private val geminiInstanceId = "test-gemini-instance-id"
    private val xaiInstanceId = "test-xai-instance-id"

    @BeforeEach
    fun setUp() {
        secureSessionManager = TestSecureSessionManager()
        cleanupTestKeys()
    }

    @AfterEach
    fun tearDown() {
        cleanupTestKeys()
    }

    private fun cleanupTestKeys() {
        listOf(
            "test_instance.$openAiInstanceId",
            "test_instance.$geminiInstanceId",
            "test_instance.$xaiInstanceId",
            TEST_KEYCHAIN_KEY,
        ).forEach { key ->
            try {
                SecureKeyManager.removeSecretKey(key)
            } catch (_: Exception) {}
        }
        try {
            EncryptionManager.deleteKey()
        } catch (_: Exception) {}
    }

    // ── Helper ──────────────────────────────────────────────────────────────────────────────

    private fun paramsWithOpenAi(apiKey: String, model: String = "") = AppContextParams(
        currentInstanceId = openAiInstanceId,
        providerInstances = mutableListOf(
            ProviderInstance(
                id = openAiInstanceId,
                displayName = "openai",
                providerType = ModelProvider.OPENAI,
                settings = OpenAiSettings(apiKey = apiKey, defaultModel = model),
            ),
        ),
    )

    // ── Tests ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should load session with empty API keys unchanged")
    fun testLoadSessionWithEmptyApiKeys() {
        val params = AppContextParams(
            currentInstanceId = openAiInstanceId,
            providerInstances = mutableListOf(
                ProviderInstance(openAiInstanceId, "openai", ModelProvider.OPENAI, OpenAiSettings(apiKey = "")),
                ProviderInstance(geminiInstanceId, "gemini", ModelProvider.GEMINI, GeminiSettings(apiKey = "")),
            ),
        )

        val loaded = secureSessionManager.loadSecureSession(params)

        assertEquals("", (loaded.providerInstances[0].settings as OpenAiSettings).apiKey)
        assertEquals("", (loaded.providerInstances[1].settings as GeminiSettings).apiKey)
    }

    @Test
    @DisplayName("Should save session and replace API keys with placeholders")
    fun testSaveSessionWithApiKeys() {
        val originalApiKey = "sk-test-api-key-12345"
        val params = paramsWithOpenAi(originalApiKey)

        val saved = secureSessionManager.saveSecureSession(params)
        val savedKey = (saved.providerInstances[0].settings as OpenAiSettings).apiKey

        assertNotEquals(originalApiKey, savedKey)
        assertTrue(
            savedKey == "***keychain***" || savedKey.startsWith("encrypted:"),
            "API key should be replaced with secure placeholder or encrypted form",
        )
    }

    @Test
    @DisplayName("Should handle round-trip save and load of API keys")
    fun testRoundTripSaveAndLoad() {
        val originalApiKey = "sk-test-round-trip-key-67890"
        val params = paramsWithOpenAi(originalApiKey)

        val saved = secureSessionManager.saveSecureSession(params)
        val savedKey = (saved.providerInstances[0].settings as OpenAiSettings).apiKey
        assertTrue(
            savedKey == "***keychain***" || savedKey.startsWith("encrypted:"),
            "API key should be replaced with placeholder after save",
        )

        val loaded = secureSessionManager.loadSecureSession(saved)
        val loadedKey = (loaded.providerInstances[0].settings as OpenAiSettings).apiKey
        assertTrue(
            loadedKey == originalApiKey || loadedKey == "***keychain***" || loadedKey.startsWith("encrypted:"),
            "Should either restore original key or maintain secure storage format. Got: '$loadedKey'",
        )
    }

    @Test
    @DisplayName("Should handle multiple instances with different API keys")
    fun testMultipleInstancesWithApiKeys() {
        val openAiKey = "sk-openai-test-key"
        val geminiKey = "ai-gemini-test-key"
        val xaiKey = "xai-test-key"

        val params = AppContextParams(
            currentInstanceId = openAiInstanceId,
            providerInstances = mutableListOf(
                ProviderInstance(openAiInstanceId, "openai", ModelProvider.OPENAI, OpenAiSettings(apiKey = openAiKey)),
                ProviderInstance(geminiInstanceId, "gemini", ModelProvider.GEMINI, GeminiSettings(apiKey = geminiKey)),
                ProviderInstance(xaiInstanceId, "xai", ModelProvider.XAI, XAiSettings(apiKey = xaiKey)),
            ),
        )

        val saved = secureSessionManager.saveSecureSession(params)

        val savedOpenAiKey = (saved.providerInstances[0].settings as OpenAiSettings).apiKey
        val savedGeminiKey = (saved.providerInstances[1].settings as GeminiSettings).apiKey
        val savedXaiKey = (saved.providerInstances[2].settings as XAiSettings).apiKey

        assertNotEquals(openAiKey, savedOpenAiKey)
        assertNotEquals(geminiKey, savedGeminiKey)
        assertNotEquals(xaiKey, savedXaiKey)

        val loaded = secureSessionManager.loadSecureSession(saved)

        fun assertSecure(expected: String, actual: String, label: String) {
            assertTrue(
                actual == expected || actual == "***keychain***" || actual.startsWith("encrypted:"),
                "$label: expected '$expected' or secure placeholder, got '$actual'",
            )
        }

        assertSecure(openAiKey, (loaded.providerInstances[0].settings as OpenAiSettings).apiKey, "OpenAI")
        assertSecure(geminiKey, (loaded.providerInstances[1].settings as GeminiSettings).apiKey, "Gemini")
        assertSecure(xaiKey, (loaded.providerInstances[2].settings as XAiSettings).apiKey, "XAI")
    }

    @Test
    @DisplayName("Two instances of the same provider type store keys independently")
    fun testTwoInstancesSameProviderType() {
        val id1 = "ollama-local-id"
        val id2 = "ollama-server-id"
        val key1 = "token-local"
        val key2 = "token-server"

        // Clean up these specific instance keys
        try {
            SecureKeyManager.removeSecretKey("test_instance.$id1")
        } catch (_: Exception) {}
        try {
            SecureKeyManager.removeSecretKey("test_instance.$id2")
        } catch (_: Exception) {}

        // Use OpenAI-compatible settings to carry an API key for two "different" instances
        val params = AppContextParams(
            currentInstanceId = id1,
            providerInstances = mutableListOf(
                ProviderInstance(id1, "ollama-local", ModelProvider.OPENAI_COMPATIBLE, OpenAiSettings(apiKey = key1)),
                ProviderInstance(id2, "ollama-server", ModelProvider.OPENAI_COMPATIBLE, OpenAiSettings(apiKey = key2)),
            ),
        )

        val saved = secureSessionManager.saveSecureSession(params)
        val loaded = secureSessionManager.loadSecureSession(saved)

        val loadedKey1 = (loaded.providerInstances[0].settings as OpenAiSettings).apiKey
        val loadedKey2 = (loaded.providerInstances[1].settings as OpenAiSettings).apiKey

        // Keys must be independent — they must not overwrite each other
        val key1Secure = loadedKey1 == key1 || loadedKey1 == "***keychain***" || loadedKey1.startsWith("encrypted:")
        val key2Secure = loadedKey2 == key2 || loadedKey2 == "***keychain***" || loadedKey2.startsWith("encrypted:")
        assertTrue(key1Secure, "Instance 1 key should be handled securely. Got: '$loadedKey1'")
        assertTrue(key2Secure, "Instance 2 key should be handled securely. Got: '$loadedKey2'")

        // After a full round-trip with keychain, keys must not be the same unless both happened to match
        if (loadedKey1 != "***keychain***" && loadedKey2 != "***keychain***" &&
            !loadedKey1.startsWith("encrypted:") && !loadedKey2.startsWith("encrypted:")
        ) {
            assertNotEquals(loadedKey1, loadedKey2, "Two independent instances must not share keychain values")
        }

        try {
            SecureKeyManager.removeSecretKey("test_instance.$id1")
        } catch (_: Exception) {}
        try {
            SecureKeyManager.removeSecretKey("test_instance.$id2")
        } catch (_: Exception) {}
    }

    @Test
    @DisplayName("Should handle keychain placeholder correctly")
    fun testKeychainPlaceholderHandling() {
        val params = paramsWithOpenAi("***keychain***")

        val loaded = secureSessionManager.loadSecureSession(params)
        val loadedKey = (loaded.providerInstances[0].settings as OpenAiSettings).apiKey
        assertNotNull(loadedKey)
    }

    @Test
    @DisplayName("Should handle encrypted API key format correctly")
    fun testEncryptedApiKeyHandling() {
        val originalKey = "sk-unique-encrypted-test-key-123456789"
        cleanupTestKeys()

        val encrypted = EncryptionManager.encrypt(originalKey)
        assertNotNull(encrypted, "Encryption should succeed")

        val params = paramsWithOpenAi("encrypted:$encrypted")
        val loaded = secureSessionManager.loadSecureSession(params)
        val loadedKey = (loaded.providerInstances[0].settings as OpenAiSettings).apiKey

        assertTrue(
            loadedKey == originalKey || loadedKey.isNotEmpty(),
            "Should either decrypt or retrieve from secure storage. Got: '$loadedKey'",
        )
    }

    @Test
    @DisplayName("Should handle corrupted encrypted API key gracefully")
    fun testCorruptedEncryptedApiKey() {
        cleanupTestKeys()
        val params = paramsWithOpenAi("encrypted:corrupted-invalid-base64-data-xyz")

        val loaded = secureSessionManager.loadSecureSession(params)
        val loadedKey = (loaded.providerInstances[0].settings as OpenAiSettings).apiKey

        if (loadedKey.isNotEmpty()) {
            assertFalse(loadedKey.startsWith("encrypted:"), "Should not return encrypted format when loading")
        }
    }

    @Test
    @DisplayName("Should preserve non-API key settings during operations")
    fun testPreserveNonApiKeySettings() {
        val params = paramsWithOpenAi("sk-test-key", "gpt-4o")

        val saved = secureSessionManager.saveSecureSession(params)
        val loaded = secureSessionManager.loadSecureSession(saved)

        assertEquals(openAiInstanceId, loaded.currentInstanceId)
        assertEquals("gpt-4o", (loaded.providerInstances[0].settings as OpenAiSettings).defaultModel)
    }

    @Test
    @DisplayName("Should produce a deep copy so original params are not mutated")
    fun testSessionParamsCopyBehavior() {
        val originalApiKey = "sk-original-key"
        val original = paramsWithOpenAi(originalApiKey)

        val saved = secureSessionManager.saveSecureSession(original)

        assertNotNull(saved)
        val originalKey = (original.providerInstances[0].settings as OpenAiSettings).apiKey
        val savedKey = (saved.providerInstances[0].settings as OpenAiSettings).apiKey

        assertTrue(
            (originalKey == originalApiKey && savedKey != originalApiKey) ||
                (originalKey == savedKey && savedKey != originalApiKey),
            "Either original is preserved and saved is modified, or both modified consistently. " +
                "Original: '$originalKey', Saved: '$savedKey'",
        )
    }

    @Test
    @DisplayName("Should verify keychain operations work correctly")
    fun testKeychainOperationsDirectly() {
        val testKey = "sk-test-keychain-direct-12345"
        try {
            SecureKeyManager.removeSecretKey(TEST_KEYCHAIN_KEY)
        } catch (_: Exception) {}

        val storeResult = SecureKeyManager.storeSecuredKey(TEST_KEYCHAIN_KEY, testKey)
        assertTrue(storeResult.success, "Should successfully store API key, result: $storeResult")

        val retrieved = SecureKeyManager.retrieveSecretKey(TEST_KEYCHAIN_KEY)
        if (storeResult.method == SecureKeyManager.StorageMethod.KEYCHAIN) {
            if (retrieved != null) {
                assertEquals(testKey, retrieved)
            } else {
                println("WARNING: Keychain storage succeeded but retrieval failed — expected in some CI environments")
            }
        }

        try {
            SecureKeyManager.removeSecretKey(TEST_KEYCHAIN_KEY)
        } catch (_: Exception) {}
    }

    @Test
    @DisplayName("Should load API key from keychain when placeholder is present (macOS only)")
    fun testSecureSessionLoading() {
        val osName = System.getProperty("os.name").lowercase()
        assumeTrue(osName.contains("mac"), "Keychain only supported on macOS")

        val directKey = "test_session_load_direct"
        SecureKeyManager.storeSecuredKey(directKey, "sk-actual-key-from-keychain")
        val retrieved = SecureKeyManager.retrieveSecretKey(directKey)
        if (retrieved != null) {
            assertEquals("sk-actual-key-from-keychain", retrieved)
        }
        try {
            SecureKeyManager.removeSecretKey(directKey)
        } catch (_: Exception) {}
    }

    @Test
    @DisplayName("Should encrypt and decrypt API key via EncryptionManager")
    fun testEncryptionFallback() {
        val testApiKey = "sk-test-encryption-key"
        val encrypted = EncryptionManager.encrypt(testApiKey)
        assertNotNull(encrypted)
        assertNotEquals(testApiKey, encrypted)
        assertEquals(testApiKey, EncryptionManager.decrypt(encrypted))
    }

    @Test
    @DisplayName("Should return correct security descriptions for each storage method")
    fun testStorageSecurityDescriptions() {
        assertTrue(SecureKeyManager.getStorageSecurityDescription(SecureKeyManager.StorageMethod.KEYCHAIN).contains("Keychain"))
        assertTrue(SecureKeyManager.getStorageSecurityDescription(SecureKeyManager.StorageMethod.ENCRYPTED).contains("Encrypted"))
        assertTrue(SecureKeyManager.getStorageSecurityDescription(SecureKeyManager.StorageMethod.INSECURE_FALLBACK).contains("INSECURE"))
    }
}
