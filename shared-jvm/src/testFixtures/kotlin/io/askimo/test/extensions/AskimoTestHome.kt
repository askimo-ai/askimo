/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.test.extensions

import io.askimo.core.config.AppConfig
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Path

/**
 * JUnit 5 extension that automatically sets up and tears down AskimoHome for tests.
 *
 * Usage:
 * ```kotlin
 * @AskimoTestHome
 * class MyTest {
 *     @Test
 *     fun myTest() {
 *         // AskimoHome is automatically configured with a temp directory
 *     }
 * }
 * ```
 *
 * Optionally, inject the temp directory:
 * ```kotlin
 * @AskimoTestHome
 * class MyTest {
 *     @TempDir
 *     lateinit var tempHome: Path
 *
 *     @Test
 *     fun myTest() {
 *         // tempHome contains the test directory
 *     }
 * }
 * ```
 *
 * Specify custom profile name:
 * ```kotlin
 * @AskimoTestHome(profileName = "team")
 * class MyTeamTest {
 *     // AskimoHome.base() resolves to tempDir/team/
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(AskimoTestHomeExtension::class)
annotation class AskimoTestHome(
    val profileName: String = "personal",
)

class AskimoTestHomeExtension :
    BeforeEachCallback,
    AfterEachCallback {

    companion object {
        private const val TEST_BASE_SCOPE_KEY = "askimo.testBaseScope"
        private const val TEMP_DIR_KEY = "askimo.tempDir"
        private const val ORIGINAL_IMPL_KEY = "askimo.originalImpl"
    }

    override fun beforeEach(context: ExtensionContext) {
        val annotation = findAnnotation(context.requiredTestClass)
        val profileName = annotation?.profileName ?: "personal"

        // Try to find @TempDir field, otherwise create our own temp directory
        val tempDir = findTempDirField(context)?.let { field ->
            field.get(context.requiredTestInstance) as? Path
        } ?: createTempDirectory(context)

        // Save original implementation if it exists (using reflection to access private field)
        val originalImpl = try {
            val implField = AskimoHome::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            implField.get(AskimoHome) as? io.askimo.core.util.AskimoHomeBase
        } catch (e: Exception) {
            null
        }

        // Create test implementation
        val testImpl = object : io.askimo.core.util.AskimoHomeBase {
            override val profileDirName = profileName
            override fun rootBase() = tempDir.toAbsolutePath().normalize()
        }

        // Register globally so spawned threads can access it
        AskimoHome.register(testImpl)

        // Write DEFAULT_YAML to the temp home dir and reset AppConfig cache so
        // any code that calls AppConfig will load the default values from the YAML
        // (e.g. models[OLLAMA].embeddingModel == "nomic-embed-text:latest")
        AppConfig.initForTest(AskimoHome.base())
        AppConfig.setSecureSessionManagerForTest(TestSecureSessionManager())

        // Also use thread-local for compatibility
        val testBaseScope = AskimoHome.withTestBase(tempDir, profileName)

        context.getStore(ExtensionContext.Namespace.GLOBAL).put(TEST_BASE_SCOPE_KEY, testBaseScope)
        context.getStore(ExtensionContext.Namespace.GLOBAL).put(TEMP_DIR_KEY, tempDir)
        context.getStore(ExtensionContext.Namespace.GLOBAL).put(ORIGINAL_IMPL_KEY, originalImpl)
    }

    override fun afterEach(context: ExtensionContext) {
        val testBaseScope = context.getStore(ExtensionContext.Namespace.GLOBAL)
            .get(TEST_BASE_SCOPE_KEY, AskimoHome.TestBaseScope::class.java)

        testBaseScope?.close()

        // Reset AppConfig so the next test (or production code) reloads fresh
        AppConfig.reset()

        // Restore original implementation
        val originalImpl = context.getStore(ExtensionContext.Namespace.GLOBAL)
            .get(ORIGINAL_IMPL_KEY) as? io.askimo.core.util.AskimoHomeBase

        if (originalImpl != null) {
            AskimoHome.register(originalImpl)
        }

        // Clean up temp directory if we created it
        val tempDir = context.getStore(ExtensionContext.Namespace.GLOBAL)
            .get(TEMP_DIR_KEY) as? Path

        if (tempDir != null && !hasTempDirField(context)) {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun findAnnotation(clazz: Class<*>): AskimoTestHome? {
        var c: Class<*>? = clazz
        while (c != null) {
            val ann = c.getAnnotation(AskimoTestHome::class.java)
            if (ann != null) return ann
            c = c.enclosingClass
        }
        return null
    }

    private fun findTempDirField(context: ExtensionContext): Field? = context.requiredTestClass.declaredFields
        .firstOrNull { it.isAnnotationPresent(TempDir::class.java) }
        ?.also { it.isAccessible = true }

    private fun hasTempDirField(context: ExtensionContext): Boolean = findTempDirField(context) != null

    private fun createTempDirectory(context: ExtensionContext): Path {
        val tempDir = File.createTempFile("askimo-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
        return tempDir.toPath()
    }
}
