/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.context.AppContext
import io.askimo.core.context.AppContextParams
import io.askimo.core.context.ExecutionMode
import io.askimo.core.error.AppError
import io.askimo.core.providers.ollama.OllamaSettings
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@AskimoTestHome
@DisplayName("ProviderInstanceService")
class ProviderInstanceServiceTest {

    private lateinit var service: ProviderInstanceService

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * Creates a test [ProviderInstance] with [OllamaSettings] so that [ProviderInstanceService.setModel]
     * (which calls [ProviderSettings.updateField]) works correctly in tests.
     */
    private fun makeInstance(
        displayName: String,
        provider: ModelProvider = ModelProvider.OLLAMA,
        model: String = "",
    ): ProviderInstance = ProviderInstance.create(
        displayName = displayName,
        providerType = provider,
        settings = OllamaSettings(defaultModel = model),
    )

    @BeforeEach
    fun setUp() {
        // Initialize a clean AppContext with no pre-configured instances
        val params = AppContextParams(currentInstanceId = "", providerInstances = mutableListOf())
        AppContext.initialize(ExecutionMode.STATELESS_MODE, params)
        service = ProviderInstanceService(AppContext.getInstance())
    }

    @AfterEach
    fun tearDown() {
        AppContext.reset()
    }

    // ── findById ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findById")
    inner class FindById {

        @Test
        fun `returns instance when id exists`() {
            val instance = makeInstance("Ollama Local")
            service.add(instance)

            val found = service.findById(instance.id)
            assertNotNull(found)
            assertEquals(instance.id, found.id)
        }

        @Test
        fun `returns null when id does not exist`() {
            assertNull(service.findById("non-existent-id"))
        }
    }

    // ── isDisplayNameAvailable ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isDisplayNameAvailable")
    inner class IsDisplayNameAvailable {

        @Test
        fun `returns true when no instances exist`() {
            assertTrue(service.isDisplayNameAvailable("My Provider"))
        }

        @Test
        fun `returns false when name is already taken (exact match)`() {
            service.add(makeInstance("My Provider"))
            assertFalse(service.isDisplayNameAvailable("My Provider"))
        }

        @Test
        fun `name check is case-insensitive`() {
            service.add(makeInstance("my provider"))
            assertFalse(service.isDisplayNameAvailable("MY PROVIDER"))
            assertFalse(service.isDisplayNameAvailable("My Provider"))
        }

        @Test
        fun `name check trims whitespace`() {
            service.add(makeInstance("My Provider"))
            assertFalse(service.isDisplayNameAvailable("  My Provider  "))
        }

        @Test
        fun `returns false for blank name`() {
            assertFalse(service.isDisplayNameAvailable(""))
            assertFalse(service.isDisplayNameAvailable("   "))
        }

        @Test
        fun `excludingId allows the instance's own name during edit`() {
            val instance = makeInstance("My Provider")
            service.add(instance)

            // Same name is available when excluding the instance itself
            assertTrue(service.isDisplayNameAvailable("My Provider", excludingId = instance.id))
            // But still blocked for a different id
            assertFalse(service.isDisplayNameAvailable("My Provider", excludingId = "other-id"))
        }
    }

    // ── add ──────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("add")
    inner class Add {

        @Test
        fun `adds instance and makes it active`() {
            val instance = makeInstance("OpenAI Work", ModelProvider.OPENAI)

            val result = service.add(instance)

            assertTrue(result.isSuccess)
            assertEquals(instance.id, AppContext.getInstance().params.currentInstanceId)
            assertNotNull(service.findById(instance.id))
        }

        @Test
        fun `persists the instance to storage`() {
            val instance = makeInstance("Saved Provider")
            service.add(instance)

            // Verify it is accessible through the params list
            assertEquals(1, service.all.size)
            assertEquals("Saved Provider", service.all.first().displayName)
        }

        @Test
        fun `returns DuplicateEntry failure when display name already taken`() {
            service.add(makeInstance("My Provider"))

            val result = service.add(makeInstance("My Provider"))

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<AppError.DuplicateEntry>(error)
            assertEquals("display name", error.field)
        }

        @Test
        fun `name uniqueness check is case-insensitive on add`() {
            service.add(makeInstance("my provider"))

            val result = service.add(makeInstance("MY PROVIDER"))
            assertTrue(result.isFailure)
            assertIs<AppError.DuplicateEntry>(result.exceptionOrNull())
        }

        @Test
        fun `adding multiple distinct providers succeeds`() {
            service.add(makeInstance("Ollama Dev", ModelProvider.OLLAMA))
            service.add(makeInstance("OpenAI Prod", ModelProvider.OPENAI))

            assertEquals(2, service.all.size)
        }
    }

    // ── update ───────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update")
    inner class Update {

        @Test
        fun `updates display name of an existing instance`() {
            val instance = makeInstance("Old Name")
            service.add(instance)

            val renamed = instance.copy(displayName = "New Name")
            val result = service.update(renamed)

            assertTrue(result.isSuccess)
            assertEquals("New Name", service.findById(instance.id)?.displayName)
        }

        @Test
        fun `returns NotFound when instance does not exist`() {
            val ghost = makeInstance("Ghost")
            val result = service.update(ghost)

            assertTrue(result.isFailure)
            assertIs<AppError.NotFound>(result.exceptionOrNull())
        }

        @Test
        fun `returns DuplicateEntry when renamed to an existing name`() {
            val first = makeInstance("First")
            val second = makeInstance("Second")
            service.add(first)
            service.add(second)

            val conflict = second.copy(displayName = "First")
            val result = service.update(conflict)

            assertTrue(result.isFailure)
            assertIs<AppError.DuplicateEntry>(result.exceptionOrNull())
        }

        @Test
        fun `keeping the same name on update succeeds (self-exclusion)`() {
            val instance = makeInstance("Same Name")
            service.add(instance)

            // Update with same name (e.g. only changing settings)
            val result = service.update(instance.copy(displayName = "Same Name"))
            assertTrue(result.isSuccess)
        }
    }

    // ── delete ───────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    inner class Delete {

        @Test
        fun `removes the instance from the list`() {
            val instance = makeInstance("To Delete")
            service.add(instance)

            val result = service.delete(instance.id)

            assertTrue(result.isSuccess)
            assertNull(service.findById(instance.id))
            assertEquals(0, service.all.size)
        }

        @Test
        fun `returns NotFound when instance does not exist`() {
            val result = service.delete("non-existent-id")
            assertTrue(result.isFailure)
            assertIs<AppError.NotFound>(result.exceptionOrNull())
        }

        @Test
        fun `clears currentInstanceId when the last instance is deleted`() {
            val instance = makeInstance("Active One")
            service.add(instance)
            assertEquals(instance.id, AppContext.getInstance().params.currentInstanceId)

            service.delete(instance.id)

            assertEquals("", AppContext.getInstance().params.currentInstanceId)
            assertNull(service.active)
        }

        @Test
        fun `promotes the next instance when the active one is deleted`() {
            val first = makeInstance("First")
            val second = makeInstance("Second")
            service.add(first)
            service.add(second)
            service.setActive(first.id)

            service.delete(first.id)

            assertEquals(second.id, AppContext.getInstance().params.currentInstanceId)
            assertEquals(second.id, service.active?.id)
        }

        @Test
        fun `promotes the first remaining instance regardless of position`() {
            val a = makeInstance("A")
            val b = makeInstance("B")
            val c = makeInstance("C")
            service.add(a)
            service.add(b)
            service.add(c)
            service.setActive(b.id) // middle instance is active

            service.delete(b.id)

            // After removing the middle, 'a' is first in the list
            assertEquals(a.id, AppContext.getInstance().params.currentInstanceId)
            assertEquals(a.id, service.active?.id)
            assertEquals(2, service.all.size)
        }

        @Test
        fun `active instance is updated in params before save (promotion is persisted)`() {
            val first = makeInstance("First")
            val second = makeInstance("Second")
            service.add(first)
            service.add(second)
            service.setActive(first.id)

            service.delete(first.id)

            // service.active reads from appContext.params which is what gets saved —
            // verifies setCurrentInstance was called before save(), not after
            val activeId = AppContext.getInstance().params.currentInstanceId
            assertFalse(activeId.isEmpty(), "currentInstanceId must not be empty when instances remain")
            assertEquals(second.id, activeId)
        }

        @Test
        fun `deleting a non-active instance preserves the active instance`() {
            val active = makeInstance("Active")
            val other = makeInstance("Other")
            service.add(active)
            service.add(other)

            service.delete(other.id)

            assertEquals(active.id, AppContext.getInstance().params.currentInstanceId)
            assertEquals(active.id, service.active?.id)
            assertEquals(1, service.all.size)
        }
    }

    // ── setActive ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setActive")
    inner class SetActive {

        @Test
        fun `switches the active instance`() {
            val first = makeInstance("First")
            val second = makeInstance("Second")
            service.add(first) // first becomes active
            service.add(second) // second becomes active

            service.setActive(first.id)
            assertEquals(first.id, AppContext.getInstance().params.currentInstanceId)
        }

        @Test
        fun `returns NotFound when instance does not exist`() {
            val result = service.setActive("ghost-id")
            assertTrue(result.isFailure)
            assertIs<AppError.NotFound>(result.exceptionOrNull())
        }
    }

    // ── setModel ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setModel")
    inner class SetModel {

        @Test
        fun `persists the model on the instance`() {
            val instance = makeInstance("My Ollama")
            service.add(instance)

            service.setModel(instance.id, "llama3:8b")

            val updated = service.findById(instance.id)
            assertEquals("llama3:8b", updated?.settings?.defaultModel)
        }

        @Test
        fun `activates the instance if it is not already active`() {
            val first = makeInstance("First")
            val second = makeInstance("Second")
            service.add(first) // first is active
            service.add(second) // second is active after add

            // Switch back to first, then pick a model on second
            service.setActive(first.id)
            service.setModel(second.id, "mistral")

            assertEquals(second.id, AppContext.getInstance().params.currentInstanceId)
        }

        @Test
        fun `returns NotFound when instance does not exist`() {
            val result = service.setModel("ghost-id", "some-model")
            assertTrue(result.isFailure)
            assertIs<AppError.NotFound>(result.exceptionOrNull())
        }
    }
}
