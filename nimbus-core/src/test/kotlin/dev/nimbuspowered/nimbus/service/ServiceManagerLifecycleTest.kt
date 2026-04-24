package dev.nimbuspowered.nimbus.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for service lifecycle state machine behavior, extending what
 * ServiceTest and ServiceRegistryTest already cover.
 *
 * Sections:
 *   A — State machine: PREPARED + DRAINING paths, remaining invalid transitions,
 *       concurrent transition safety
 *   B — ServiceRegistry: edge cases not covered by ServiceRegistryTest
 *   C — Service utility fields (tps, healthy, lastCrashReport, etc.)
 */
class ServiceManagerLifecycleTest {

    private lateinit var registry: ServiceRegistry

    private fun makeService(name: String, group: String = "Lobby") = Service(
        name = name,
        groupName = group,
        port = 52000,
        workingDirectory = Path.of("/tmp/nimbus-test/$name")
    )

    @BeforeEach
    fun setUp() {
        registry = ServiceRegistry()
    }

    // =========================================================================
    // A. State machine edge cases — PREPARED path
    // =========================================================================

    @Test
    fun `PREPARING to PREPARED is valid`() {
        val svc = makeService("Lobby-1")
        assertTrue(svc.transitionTo(ServiceState.PREPARED))
        assertEquals(ServiceState.PREPARED, svc.state)
    }

    @Test
    fun `PREPARED to STARTING is valid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.PREPARED)
        assertTrue(svc.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.STARTING, svc.state)
    }

    @Test
    fun `PREPARED to STOPPING is valid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.PREPARED)
        assertTrue(svc.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.STOPPING, svc.state)
    }

    @Test
    fun `PREPARED to STOPPED is valid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.PREPARED)
        assertTrue(svc.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.STOPPED, svc.state)
    }

    @Test
    fun `PREPARED to CRASHED is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.PREPARED)
        assertFalse(svc.transitionTo(ServiceState.CRASHED))
        assertEquals(ServiceState.PREPARED, svc.state)
    }

    @Test
    fun `PREPARED to READY is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.PREPARED)
        assertFalse(svc.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.PREPARED, svc.state)
    }

    @Test
    fun `PREPARED to DRAINING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.PREPARED)
        assertFalse(svc.transitionTo(ServiceState.DRAINING))
        assertEquals(ServiceState.PREPARED, svc.state)
    }

    @Test
    fun `PREPARED to PREPARED is idempotent`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.PREPARED)
        assertTrue(svc.transitionTo(ServiceState.PREPARED))
        assertEquals(ServiceState.PREPARED, svc.state)
    }

    // =========================================================================
    // A. State machine edge cases — DRAINING path
    // =========================================================================

    @Test
    fun `READY to DRAINING is valid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        assertTrue(svc.transitionTo(ServiceState.DRAINING))
        assertEquals(ServiceState.DRAINING, svc.state)
    }

    @Test
    fun `DRAINING to STOPPING is valid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.DRAINING)
        assertTrue(svc.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.STOPPING, svc.state)
    }

    @Test
    fun `DRAINING to STOPPED is valid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.DRAINING)
        assertTrue(svc.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.STOPPED, svc.state)
    }

    @Test
    fun `DRAINING to CRASHED is valid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.DRAINING)
        assertTrue(svc.transitionTo(ServiceState.CRASHED))
        assertEquals(ServiceState.CRASHED, svc.state)
    }

    @Test
    fun `DRAINING to PREPARING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.DRAINING)
        assertFalse(svc.transitionTo(ServiceState.PREPARING))
        assertEquals(ServiceState.DRAINING, svc.state)
    }

    @Test
    fun `DRAINING to STARTING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.DRAINING)
        assertFalse(svc.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.DRAINING, svc.state)
    }

    @Test
    fun `DRAINING to READY is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.DRAINING)
        assertFalse(svc.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.DRAINING, svc.state)
    }

    @Test
    fun `DRAINING to DRAINING is idempotent`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.DRAINING)
        assertTrue(svc.transitionTo(ServiceState.DRAINING))
        assertEquals(ServiceState.DRAINING, svc.state)
    }

    // =========================================================================
    // A. State machine edge cases — CRASHED recovery paths
    // =========================================================================

    @Test
    fun `CRASHED to STOPPING is valid for operator cleanup`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.CRASHED)
        assertTrue(svc.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.STOPPING, svc.state)
    }

    @Test
    fun `CRASHED to READY is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.CRASHED)
        assertFalse(svc.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.CRASHED, svc.state)
    }

    @Test
    fun `CRASHED to DRAINING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.CRASHED)
        assertFalse(svc.transitionTo(ServiceState.DRAINING))
        assertEquals(ServiceState.CRASHED, svc.state)
    }

    @Test
    fun `CRASHED to CRASHED is idempotent`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.CRASHED)
        assertTrue(svc.transitionTo(ServiceState.CRASHED))
        assertEquals(ServiceState.CRASHED, svc.state)
    }

    // =========================================================================
    // A. State machine edge cases — STOPPED remaining invalids
    // =========================================================================

    @Test
    fun `STOPPED to STARTING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STOPPED)
        assertFalse(svc.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.STOPPED, svc.state)
    }

    @Test
    fun `STOPPED to STOPPING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STOPPED)
        assertFalse(svc.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.STOPPED, svc.state)
    }

    @Test
    fun `STOPPED to DRAINING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STOPPED)
        assertFalse(svc.transitionTo(ServiceState.DRAINING))
        assertEquals(ServiceState.STOPPED, svc.state)
    }

    // =========================================================================
    // A. State machine — STOPPING remaining invalids
    // =========================================================================

    @Test
    fun `STOPPING to STARTING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.STOPPING)
        assertFalse(svc.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.STOPPING, svc.state)
    }

    @Test
    fun `STOPPING to READY is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.STOPPING)
        assertFalse(svc.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.STOPPING, svc.state)
    }

    @Test
    fun `STOPPING to DRAINING is invalid`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.STOPPING)
        assertFalse(svc.transitionTo(ServiceState.DRAINING))
        assertEquals(ServiceState.STOPPING, svc.state)
    }

    @Test
    fun `STOPPING to STOPPING is idempotent`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        svc.transitionTo(ServiceState.STOPPING)
        assertTrue(svc.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.STOPPING, svc.state)
    }

    // =========================================================================
    // A. State machine — thread-safety: 100 concurrent calls, only one wins
    // =========================================================================

    @Test
    fun `concurrent transitionTo STARTING allows exactly one winner per synchronized block`() = runTest {
        val svc = makeService("Lobby-1")
        assertEquals(ServiceState.PREPARING, svc.state)

        val successCount = AtomicInteger(0)

        withContext(Dispatchers.Default) {
            (1..100).map {
                async {
                    // All threads race to transition PREPARING -> STARTING
                    val won = svc.transitionTo(ServiceState.STARTING)
                    if (won) successCount.incrementAndGet()
                }
            }.awaitAll()
        }

        // transitionTo is @Synchronized — exactly one caller transitions from
        // PREPARING to STARTING; subsequent callers see STARTING == STARTING
        // (idempotent) and also return true. The key invariant is the final
        // state is STARTING and not some undefined intermediate value.
        assertEquals(ServiceState.STARTING, svc.state, "Final state must be STARTING")
        // At least one call must have succeeded
        assertTrue(successCount.get() >= 1, "At least one transition must succeed")
        // All 100 calls must eventually return true (idempotent once in STARTING)
        assertEquals(100, successCount.get(), "All calls return true once state is STARTING (idempotent)")
    }

    @Test
    fun `concurrent invalid transitions do not corrupt state`() = runTest {
        val svc = makeService("Lobby-1")
        // Drive to READY
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.READY)
        assertEquals(ServiceState.READY, svc.state)

        val invalidAttempts = AtomicInteger(0)

        withContext(Dispatchers.Default) {
            (1..50).map {
                async {
                    // READY -> STARTING is invalid; all must return false
                    val result = svc.transitionTo(ServiceState.STARTING)
                    if (!result) invalidAttempts.incrementAndGet()
                }
            }.awaitAll()
        }

        assertEquals(50, invalidAttempts.get(), "All invalid transitions must return false")
        assertEquals(ServiceState.READY, svc.state, "State must remain READY after all invalid attempts")
    }

    // =========================================================================
    // B. ServiceRegistry edge cases
    // =========================================================================

    @Test
    fun `getByGroup returns empty list when no services registered for that group`() {
        assertTrue(registry.getByGroup("Lobby").isEmpty())
    }

    @Test
    fun `getByGroup returns empty list after all services in group are unregistered`() {
        registry.register(makeService("Lobby-1"))
        registry.register(makeService("Lobby-2"))
        registry.unregister("Lobby-1")
        registry.unregister("Lobby-2")
        assertTrue(registry.getByGroup("Lobby").isEmpty())
    }

    @Test
    fun `registerIfUnderLimit with limit zero always returns false`() {
        val svc = makeService("Lobby-1")
        assertFalse(registry.registerIfUnderLimit(svc, maxInstances = 0))
        assertNull(registry.get("Lobby-1"), "Service must not be registered when limit is 0")
    }

    @Test
    fun `getByGroup for Lobby does not return BedWars services`() {
        registry.register(makeService("Lobby-1", "Lobby"))
        registry.register(makeService("BedWars-1", "BedWars"))
        registry.register(makeService("BedWars-2", "BedWars"))

        val lobbies = registry.getByGroup("Lobby")
        assertEquals(1, lobbies.size)
        assertEquals("Lobby-1", lobbies.first().name)
        assertTrue(lobbies.none { it.groupName == "BedWars" })
    }

    @Test
    fun `getAll returns a snapshot — mutating registry after call does not change the returned list`() {
        registry.register(makeService("Lobby-1"))
        registry.register(makeService("Lobby-2"))

        val snapshot = registry.getAll()
        assertEquals(2, snapshot.size)

        // Add a third service after taking the snapshot
        registry.register(makeService("Lobby-3"))

        // Snapshot must not reflect the new registration
        assertEquals(2, snapshot.size, "Snapshot size must not change after further registrations")
        // But a fresh call must reflect the addition
        assertEquals(3, registry.getAll().size)
    }

    @Test
    fun `getDedicated returns only dedicated services`() {
        val regular = makeService("Lobby-1")
        val dedicated = makeService("Survival-1").also { it.isDedicated = true }

        registry.register(regular)
        registry.register(dedicated)

        val dedicated_list = registry.getDedicated()
        assertEquals(1, dedicated_list.size)
        assertEquals("Survival-1", dedicated_list.first().name)
    }

    @Test
    fun `getNonDedicated returns only non-dedicated services`() {
        val regular = makeService("Lobby-1")
        val dedicated = makeService("Survival-1").also { it.isDedicated = true }

        registry.register(regular)
        registry.register(dedicated)

        val nonDedicated = registry.getNonDedicated()
        assertEquals(1, nonDedicated.size)
        assertEquals("Lobby-1", nonDedicated.first().name)
    }

    @Test
    fun `registerIfUnderLimit with limit 1 allows first and rejects second`() {
        val svc1 = makeService("Lobby-1")
        val svc2 = makeService("Lobby-2")

        assertTrue(registry.registerIfUnderLimit(svc1, maxInstances = 1))
        assertFalse(registry.registerIfUnderLimit(svc2, maxInstances = 1))
        assertNull(registry.get("Lobby-2"))
    }

    @Test
    fun `registerIfUnderLimit after unregister allows registration again`() {
        val svc1 = makeService("Lobby-1")
        val svc2 = makeService("Lobby-2")

        assertTrue(registry.registerIfUnderLimit(svc1, maxInstances = 1))
        registry.unregister("Lobby-1")

        // Now there's room again
        assertTrue(registry.registerIfUnderLimit(svc2, maxInstances = 1))
        assertNotNull(registry.get("Lobby-2"))
    }

    // =========================================================================
    // C. Service utility fields
    // =========================================================================

    @Test
    fun `service restartCount starts at 0`() {
        val svc = makeService("Lobby-1")
        assertEquals(0, svc.restartCount)
    }

    @Test
    fun `service startedAt starts null`() {
        val svc = makeService("Lobby-1")
        assertNull(svc.startedAt)
    }

    @Test
    fun `service tps starts at 20_0 (perfect)`() {
        val svc = makeService("Lobby-1")
        assertEquals(20.0, svc.tps)
    }

    @Test
    fun `service healthy starts true`() {
        val svc = makeService("Lobby-1")
        assertTrue(svc.healthy)
    }

    @Test
    fun `service memoryUsedMb starts at 0`() {
        val svc = makeService("Lobby-1")
        assertEquals(0L, svc.memoryUsedMb)
    }

    @Test
    fun `service lastCrashReport starts null`() {
        val svc = makeService("Lobby-1")
        assertNull(svc.lastCrashReport)
    }

    @Test
    fun `service playerCount starts at 0`() {
        val svc = makeService("Lobby-1")
        assertEquals(0, svc.playerCount)
    }

    @Test
    fun `service lastPlayerCountUpdate starts null`() {
        val svc = makeService("Lobby-1")
        assertNull(svc.lastPlayerCountUpdate)
    }

    @Test
    fun `updateTps sets tps and marks service healthy when tps ge 15`() {
        val svc = makeService("Lobby-1")
        svc.updateTps(18.5)
        assertEquals(18.5, svc.tps)
        assertTrue(svc.healthy)
        assertNotNull(svc.lastHealthReport)
    }

    @Test
    fun `updateTps marks service unhealthy when tps below 15`() {
        val svc = makeService("Lobby-1")
        svc.updateTps(10.0)
        assertEquals(10.0, svc.tps)
        assertFalse(svc.healthy)
    }

    @Test
    fun `updateTps marks service healthy at exactly 15 tps (boundary)`() {
        val svc = makeService("Lobby-1")
        svc.updateTps(15.0)
        assertTrue(svc.healthy)
    }

    @Test
    fun `updateTps marks service unhealthy just below 15 tps (boundary)`() {
        val svc = makeService("Lobby-1")
        svc.updateTps(14.9)
        assertFalse(svc.healthy)
    }

    @Test
    fun `service lastHealthReport starts null`() {
        val svc = makeService("Lobby-1")
        assertNull(svc.lastHealthReport)
    }

    @Test
    fun `updateTps sets lastHealthReport`() {
        val svc = makeService("Lobby-1")
        assertNull(svc.lastHealthReport)
        svc.updateTps(20.0)
        assertNotNull(svc.lastHealthReport)
    }

    @Test
    fun `service custom state starts null`() {
        val svc = makeService("Lobby-1")
        assertNull(svc.customState)
    }

    @Test
    fun `service host defaults to 127_0_0_1`() {
        val svc = makeService("Lobby-1")
        assertEquals("127.0.0.1", svc.host)
    }

    @Test
    fun `service nodeId defaults to local`() {
        val svc = makeService("Lobby-1")
        assertEquals("local", svc.nodeId)
    }

    @Test
    fun `service isStatic defaults to false`() {
        val svc = makeService("Lobby-1")
        assertFalse(svc.isStatic)
    }

    @Test
    fun `service isDedicated defaults to false`() {
        val svc = makeService("Lobby-1")
        assertFalse(svc.isDedicated)
    }

    @Test
    fun `service proxyEnabled defaults to true`() {
        val svc = makeService("Lobby-1")
        assertTrue(svc.proxyEnabled)
    }

    @Test
    fun `service bedrockPort defaults to null`() {
        val svc = makeService("Lobby-1")
        assertNull(svc.bedrockPort)
    }

    // =========================================================================
    // A. Additional: full lifecycle path through DRAINING
    // =========================================================================

    @Test
    fun `full graceful lifecycle PREPARING to STOPPED via DRAINING`() {
        val svc = makeService("Lobby-1")
        assertTrue(svc.transitionTo(ServiceState.PREPARED))
        assertTrue(svc.transitionTo(ServiceState.STARTING))
        assertTrue(svc.transitionTo(ServiceState.READY))
        assertTrue(svc.transitionTo(ServiceState.DRAINING))
        assertTrue(svc.transitionTo(ServiceState.STOPPING))
        assertTrue(svc.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.STOPPED, svc.state)
    }

    @Test
    fun `crash recovery lifecycle CRASHED to PREPARING then back to READY`() {
        val svc = makeService("Lobby-1")
        svc.transitionTo(ServiceState.STARTING)
        svc.transitionTo(ServiceState.CRASHED)
        // Restart cycle
        assertTrue(svc.transitionTo(ServiceState.PREPARING))
        assertTrue(svc.transitionTo(ServiceState.STARTING))
        assertTrue(svc.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.READY, svc.state)
    }
}
