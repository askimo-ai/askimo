/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import io.askimo.core.mcp.config.McpInstancesConfig
import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AskimoTestHome
class McpInstanceServiceTest {

    @Test
    fun `deleteInstance removes both instance and associated server definition`() {
        val service = McpInstanceService()

        // Create a global server definition (simulating what AddGlobalMcpInstanceDialog does)
        val serverId = "global-test-delete-server-123"
        val serverDef = McpServerDefinition(
            id = serverId,
            name = "Test Global Server",
            description = "Test server for deletion",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.example.com",
                headersTemplate = mapOf("Authorization" to "Bearer test-token"),
                timeoutMs = 30000,
            ),
            tags = listOf("global"), // Important: Tagged as global
        )

        // Save the server definition
        McpServersConfig.add(serverDef)

        // Verify server definition exists
        assertNotNull(McpServersConfig.get(serverId), "Server definition should exist before deletion")

        // Create an instance using this server
        val createResult = service.createInstance(
            serverId = serverId,
            name = "Test Global Instance",
            parameterValues = emptyMap(),
        )

        assertTrue(createResult.isSuccess, "Instance creation should succeed")
        val instance = createResult.getOrNull()!!

        // Verify instance exists in mcp-instances.yml
        assertNotNull(
            McpInstancesConfig.get(instance.id),
            "Instance should exist in mcp-instances.yml before deletion",
        )

        // Delete the instance
        val deleteResult = service.deleteInstance(instance.id)

        assertTrue(deleteResult.isSuccess, "Instance deletion should succeed")

        // Verify instance is removed from mcp-instances.yml
        assertNull(
            McpInstancesConfig.get(instance.id),
            "Instance should be removed from mcp-instances.yml after deletion",
        )

        // Verify associated server definition is removed from mcp-servers.yml
        assertNull(
            McpServersConfig.get(serverId),
            "Server definition should be removed from mcp-servers.yml after deletion",
        )
    }

    @Test
    fun `deleteInstance does not remove server definitions without global tag`() {
        val service = McpInstanceService()

        // Create a regular server definition (NOT tagged as global)
        val serverId = "regular-server-not-global"
        val serverDef = McpServerDefinition(
            id = serverId,
            name = "Regular Server Template",
            description = "This is a template, not a global instance",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.example.com",
                headersTemplate = emptyMap(),
                timeoutMs = 30000,
            ),
            tags = listOf("template"), // NOT tagged as global
        )

        // Save the server definition
        McpServersConfig.add(serverDef)

        // Create an instance using this template (hypothetically)
        val createResult = service.createInstance(
            serverId = serverId,
            name = "Instance from Template",
            parameterValues = emptyMap(),
        )

        assertTrue(createResult.isSuccess)
        val instance = createResult.getOrNull()!!

        // Delete the instance
        val deleteResult = service.deleteInstance(instance.id)

        assertTrue(deleteResult.isSuccess, "Instance deletion should succeed")

        // Verify instance is removed
        assertNull(McpInstancesConfig.get(instance.id))

        // Verify server definition is NOT removed (it's a template, not a global instance)
        assertNotNull(
            McpServersConfig.get(serverId),
            "Template server definition should NOT be removed when instance deleted",
        )

        // Cleanup
        McpServersConfig.remove(serverId)
    }

    @Test
    fun `deleteInstance handles non-existent instance gracefully`() {
        val service = McpInstanceService()

        // Try to delete non-existent instance
        val result = service.deleteInstance("non-existent-instance-id")

        assertFalse(result.isSuccess, "Deleting non-existent instance should fail")
    }

    @Test
    fun `createInstance successfully creates both server definition and instance`() {
        val service = McpInstanceService()

        // Create a global server definition first
        val serverId = "global-test-create-123"
        val serverDef = McpServerDefinition(
            id = serverId,
            name = "Test Create Server",
            description = "Test server for creation",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.example.com",
                headersTemplate = emptyMap(),
                timeoutMs = 30000,
            ),
            tags = listOf("global"),
        )

        McpServersConfig.add(serverDef)

        // Create instance
        val result = service.createInstance(
            serverId = serverId,
            name = "Test Instance",
            parameterValues = emptyMap(),
        )

        assertTrue(result.isSuccess, "Instance creation should succeed")
        val instance = result.getOrNull()!!

        // Verify instance exists
        assertNotNull(McpInstancesConfig.get(instance.id))
        assertEquals("Test Instance", instance.name)
        assertEquals(serverId, instance.serverId)

        // Cleanup
        service.deleteInstance(instance.id)
    }

    @Test
    fun `getInstances returns all global instances`() {
        val service = McpInstanceService()

        // Create multiple instances
        val serverId = "global-test-list-123"
        val serverDef = McpServerDefinition(
            id = serverId,
            name = "Test List Server",
            description = "Test server for listing",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.example.com",
                headersTemplate = emptyMap(),
                timeoutMs = 30000,
            ),
            tags = listOf("global"),
        )

        McpServersConfig.add(serverDef)

        val instance1 = service.createInstance(serverId, "Instance 1", emptyMap()).getOrNull()!!
        val instance2 = service.createInstance(serverId, "Instance 2", emptyMap()).getOrNull()!!

        val instances = service.getInstances()

        assertTrue(instances.size >= 2, "Should have at least 2 instances")
        assertTrue(instances.any { it.id == instance1.id }, "Instance 1 should be in list")
        assertTrue(instances.any { it.id == instance2.id }, "Instance 2 should be in list")

        // Cleanup
        service.deleteInstance(instance1.id)
        service.deleteInstance(instance2.id)
    }

    @Test
    fun `updateInstance modifies instance properties`() {
        val service = McpInstanceService()

        // Create server and instance
        val serverId = "global-test-update-123"
        val serverDef = McpServerDefinition(
            id = serverId,
            name = "Test Update Server",
            description = "Test server for updates",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.example.com",
                headersTemplate = emptyMap(),
                timeoutMs = 30000,
            ),
            tags = listOf("global"),
        )

        McpServersConfig.add(serverDef)

        val instance = service.createInstance(serverId, "Original Name", emptyMap()).getOrNull()!!

        // Update the instance
        val updateResult = service.updateInstance(
            instanceId = instance.id,
            name = "Updated Name",
            enabled = false,
        )

        assertTrue(updateResult.isSuccess, "Update should succeed")
        val updated = updateResult.getOrNull()!!

        assertEquals("Updated Name", updated.name, "Name should be updated")
        assertFalse(updated.enabled, "Should be disabled")

        // Cleanup
        service.deleteInstance(instance.id)
    }

    @Test
    fun `updateInstance preserves list order (regression - toggling instance 1 must not reorder the list)`() {
        val service = McpInstanceService()

        val serverId = "global-test-order-123"
        val serverDef = McpServerDefinition(
            id = serverId,
            name = "Test Order Server",
            description = "Test server for order preservation",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.example.com",
                headersTemplate = emptyMap(),
                timeoutMs = 30000,
            ),
            tags = listOf("global"),
        )
        McpServersConfig.add(serverDef)

        val instance1 = service.createInstance(serverId, "Instance 1", emptyMap()).getOrNull()!!
        val instance2 = service.createInstance(serverId, "Instance 2", emptyMap()).getOrNull()!!

        val beforeIds = service.getInstances().map { it.id }
        val index1Before = beforeIds.indexOf(instance1.id)
        val index2Before = beforeIds.indexOf(instance2.id)
        assertTrue(index1Before < index2Before, "Instance 1 should appear before Instance 2 initially")

        // Toggle instance 1 (simulates the UI "enable/disable" switch)
        service.updateInstance(instanceId = instance1.id, enabled = false)

        val afterIds = service.getInstances().map { it.id }
        assertEquals(
            index1Before,
            afterIds.indexOf(instance1.id),
            "Instance 1 must stay at its original position after being toggled",
        )
        assertEquals(
            index2Before,
            afterIds.indexOf(instance2.id),
            "Instance 2 must stay at its original position after Instance 1 is toggled",
        )

        // Cleanup
        service.deleteInstance(instance1.id)
        service.deleteInstance(instance2.id)
    }

    @Test
    fun `McpServersConfig add preserves list order when updating an existing definition`() {
        val serverId1 = "global-test-order-def-1"
        val serverId2 = "global-test-order-def-2"

        fun def(id: String, name: String) = McpServerDefinition(
            id = id,
            name = name,
            description = "test",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.example.com",
                headersTemplate = emptyMap(),
                timeoutMs = 30000,
            ),
            tags = listOf("global"),
        )

        McpServersConfig.add(def(serverId1, "Definition 1"))
        McpServersConfig.add(def(serverId2, "Definition 2"))

        val beforeIds = McpServersConfig.getAll().map { it.id }
        val index1Before = beforeIds.indexOf(serverId1)
        val index2Before = beforeIds.indexOf(serverId2)
        assertTrue(index1Before < index2Before, "Definition 1 should appear before Definition 2 initially")

        // Update (re-add) definition 1 — must not move it to the end of the list
        McpServersConfig.add(def(serverId1, "Definition 1 Renamed"))

        val afterIds = McpServersConfig.getAll().map { it.id }
        assertEquals(
            index1Before,
            afterIds.indexOf(serverId1),
            "Definition 1 must stay at its original position after being updated",
        )
        assertEquals(
            index2Before,
            afterIds.indexOf(serverId2),
            "Definition 2 must stay at its original position after Definition 1 is updated",
        )
        assertEquals(
            "Definition 1 Renamed",
            McpServersConfig.get(serverId1)?.name,
            "Definition 1's update should still be applied",
        )

        // Cleanup
        McpServersConfig.remove(serverId1)
        McpServersConfig.remove(serverId2)
    }
}
