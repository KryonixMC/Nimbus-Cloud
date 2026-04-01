package dev.kryonix.nimbus.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ServiceRegistryTest {

    private lateinit var registry: ServiceRegistry

    private fun createService(name: String, group: String = "Lobby"): Service =
        Service(
            name = name,
            groupName = group,
            port = 30000,
            workingDirectory = Path.of("/tmp/nimbus/$name")
        )

    @BeforeEach
    fun setUp() {
        registry = ServiceRegistry()
    }

    // --- register / unregister lifecycle ---

    @Test
    fun `register and retrieve service`() {
        val svc = createService("Lobby-1")
        registry.register(svc)
        assertSame(svc, registry.get("Lobby-1"))
    }

    @Test
    fun `unregister removes service`() {
        val svc = createService("Lobby-1")
        registry.register(svc)
        registry.unregister("Lobby-1")
        assertNull(registry.get("Lobby-1"))
    }

    @Test
    fun `unregister nonexistent service does not throw`() {
        assertDoesNotThrow { registry.unregister("NoSuch") }
    }

    @Test
    fun `get returns null for unknown service`() {
        assertNull(registry.get("Unknown"))
    }

    // --- registerIfUnderLimit ---

    @Test
    fun `registerIfUnderLimit succeeds when under limit`() {
        val svc = createService("Lobby-1")
        assertTrue(registry.registerIfUnderLimit(svc, maxInstances = 3))
        assertSame(svc, registry.get("Lobby-1"))
    }

    @Test
    fun `registerIfUnderLimit fails when at limit`() {
        registry.register(createService("Lobby-1"))
        registry.register(createService("Lobby-2"))
        val svc3 = createService("Lobby-3")
        assertFalse(registry.registerIfUnderLimit(svc3, maxInstances = 2))
        assertNull(registry.get("Lobby-3"))
    }

    @Test
    fun `registerIfUnderLimit does not count other groups`() {
        registry.register(createService("BedWars-1", "BedWars"))
        registry.register(createService("BedWars-2", "BedWars"))
        val lobbySvc = createService("Lobby-1")
        assertTrue(registry.registerIfUnderLimit(lobbySvc, maxInstances = 1))
    }

    // --- getByGroup / getAll / countByGroup ---

    @Test
    fun `getByGroup returns only matching group`() {
        registry.register(createService("Lobby-1", "Lobby"))
        registry.register(createService("Lobby-2", "Lobby"))
        registry.register(createService("BedWars-1", "BedWars"))

        val lobbies = registry.getByGroup("Lobby")
        assertEquals(2, lobbies.size)
        assertTrue(lobbies.all { it.groupName == "Lobby" })
    }

    @Test
    fun `getByGroup returns empty list for unknown group`() {
        assertTrue(registry.getByGroup("NoGroup").isEmpty())
    }

    @Test
    fun `getAll returns all registered services`() {
        registry.register(createService("Lobby-1", "Lobby"))
        registry.register(createService("BedWars-1", "BedWars"))
        assertEquals(2, registry.getAll().size)
    }

    @Test
    fun `getAll returns empty list when no services`() {
        assertTrue(registry.getAll().isEmpty())
    }

    @Test
    fun `countByGroup returns correct count`() {
        registry.register(createService("Lobby-1", "Lobby"))
        registry.register(createService("Lobby-2", "Lobby"))
        registry.register(createService("BedWars-1", "BedWars"))
        assertEquals(2, registry.countByGroup("Lobby"))
        assertEquals(1, registry.countByGroup("BedWars"))
        assertEquals(0, registry.countByGroup("SkyWars"))
    }

    // --- Concurrency ---

    @Test
    fun `concurrent registerIfUnderLimit respects maxInstances`() = runTest {
        val maxInstances = 10
        val results = (1..100).map { i ->
            async(Dispatchers.Default) {
                val svc = createService("Lobby-$i")
                registry.registerIfUnderLimit(svc, maxInstances)
            }
        }.awaitAll()

        val successCount = results.count { it }
        assertEquals(maxInstances, successCount, "Exactly $maxInstances should have been registered")
        assertEquals(maxInstances, registry.getByGroup("Lobby").size)
    }

    @Test
    fun `concurrent register and unregister does not throw`() = runTest {
        val jobs = (1..100).map { i ->
            async(Dispatchers.Default) {
                val svc = createService("Svc-$i", "Group")
                registry.register(svc)
                registry.getAll()
                registry.countByGroup("Group")
                registry.getByGroup("Group")
                registry.unregister("Svc-$i")
            }
        }
        jobs.awaitAll()
    }
}
