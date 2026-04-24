package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.config.GroupConfig
import dev.nimbuspowered.nimbus.config.GroupDefinition
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.ScalingConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.group.ServerGroup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class WarmPoolManagerTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Creates a minimal Service in PREPARING state. */
    private fun createService(name: String, group: String = "Lobby"): Service =
        Service(
            name = name,
            groupName = group,
            port = 30000,
            workingDirectory = Path.of("/tmp/nimbus-test/$name")
        )

    /**
     * Builds a GroupConfig with the given warmPoolSize. Type defaults to DYNAMIC
     * so WarmPoolManager's replenish loop doesn't skip it.
     */
    private fun groupConfig(name: String, warmPoolSize: Int, type: GroupType = GroupType.DYNAMIC): GroupConfig =
        GroupConfig(
            group = GroupDefinition(
                name = name,
                type = type,
                scaling = ScalingConfig(warmPoolSize = warmPoolSize)
            )
        )

    /**
     * Returns a real EventBus — using a mock here breaks the coroutine context because
     * WarmPoolManager's init block calls scope.launch { eventBus.on<GroupDeleted> { ... } }
     * and the MockK proxy for on<>() returns a mock Job whose context fails hasCopyableElements.
     */
    private fun relaxedEventBus(): EventBus = EventBus(kotlinx.coroutines.CoroutineScope(Dispatchers.Default))

    /**
     * Builds a WarmPoolManager wired to [groupManager] and [factory], with [globalMax].
     * Uses [Dispatchers.Default] as the coroutine scope — the scope is only used by
     * `start()` (background replenishment loop), which we never call in these tests.
     */
    private fun buildManager(
        groupManager: GroupManager,
        factory: ServiceFactory,
        registry: ServiceRegistry = ServiceRegistry(),
        portAllocator: PortAllocator = mockk(relaxed = true),
        globalMax: Int = 0
    ): WarmPoolManager {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        return WarmPoolManager(
            serviceFactory = factory,
            registry = registry,
            groupManager = groupManager,
            portAllocator = portAllocator,
            eventBus = relaxedEventBus(),
            scope = scope,
            globalMaxServices = globalMax
        )
    }

    /**
     * Returns a mocked ServiceFactory whose [ServiceFactory.prepare] returns a fresh
     * PreparedService each call (PREPARING state, transitions allowed in cleanupPrepared).
     */
    private fun factoryThatPrepares(groupName: String): ServiceFactory {
        val factory = mockk<ServiceFactory>()
        var counter = 0
        coEvery { factory.prepare(groupName) } answers {
            val idx = ++counter
            val svc = createService("$groupName-$idx", groupName)
            ServiceFactory.PreparedService(
                service = svc,
                workDir = Path.of("/tmp/nimbus-test/$groupName-$idx"),
                command = listOf("java", "-jar", "server.jar"),
                readyPattern = null,
                isModded = false,
                readyTimeout = 120.seconds
            )
        }
        return factory
    }

    // ---------------------------------------------------------------------------
    // 1. take() returns null when pool is empty for a group
    // ---------------------------------------------------------------------------

    @Test
    fun `take returns null when pool is empty for group`() {
        val gm = mockk<GroupManager>()
        val factory = mockk<ServiceFactory>()
        val mgr = buildManager(gm, factory)

        assertNull(mgr.take("Lobby"))
    }

    // ---------------------------------------------------------------------------
    // 2. poolSize() returns 0 for unknown group
    // ---------------------------------------------------------------------------

    @Test
    fun `poolSize returns 0 for unknown group`() {
        val gm = mockk<GroupManager>()
        val factory = mockk<ServiceFactory>()
        val mgr = buildManager(gm, factory)

        assertEquals(0, mgr.poolSize("NonExistent"))
    }

    // ---------------------------------------------------------------------------
    // 3. poolSize() returns correct count after fill/take
    // ---------------------------------------------------------------------------

    @Test
    fun `poolSize returns correct count after fill and take`() = runTest {
        val groupName = "Lobby"
        val gm = mockk<GroupManager>()
        every { gm.getGroup(groupName) } returns ServerGroup(groupConfig(groupName, warmPoolSize = 3))

        val factory = factoryThatPrepares(groupName)
        val mgr = buildManager(gm, factory)

        mgr.fill(groupName)
        assertEquals(3, mgr.poolSize(groupName))

        mgr.take(groupName)
        assertEquals(2, mgr.poolSize(groupName))

        mgr.take(groupName)
        assertEquals(1, mgr.poolSize(groupName))

        mgr.take(groupName)
        assertEquals(0, mgr.poolSize(groupName))

        // Taking from empty pool returns null and doesn't crash
        assertNull(mgr.take(groupName))
        assertEquals(0, mgr.poolSize(groupName))
    }

    // ---------------------------------------------------------------------------
    // 4. allPoolSizes() snapshot reflects all groups
    // ---------------------------------------------------------------------------

    @Test
    fun `allPoolSizes snapshot reflects all groups`() = runTest {
        val gm = mockk<GroupManager>()
        every { gm.getGroup("Lobby") } returns ServerGroup(groupConfig("Lobby", warmPoolSize = 2))
        every { gm.getGroup("BedWars") } returns ServerGroup(groupConfig("BedWars", warmPoolSize = 1))

        val factory = mockk<ServiceFactory>()
        var lobbyCounter = 0
        var bedwarsCounter = 0
        coEvery { factory.prepare("Lobby") } answers {
            val idx = ++lobbyCounter
            val svc = createService("Lobby-$idx", "Lobby")
            ServiceFactory.PreparedService(
                service = svc,
                workDir = Path.of("/tmp/nimbus-test/Lobby-$idx"),
                command = emptyList(), readyPattern = null, isModded = false, readyTimeout = 120.seconds
            )
        }
        coEvery { factory.prepare("BedWars") } answers {
            val idx = ++bedwarsCounter
            val svc = createService("BedWars-$idx", "BedWars")
            ServiceFactory.PreparedService(
                service = svc,
                workDir = Path.of("/tmp/nimbus-test/BedWars-$idx"),
                command = emptyList(), readyPattern = null, isModded = false, readyTimeout = 120.seconds
            )
        }

        val mgr = buildManager(gm, factory)
        mgr.fill("Lobby")
        mgr.fill("BedWars")

        val sizes = mgr.allPoolSizes()
        assertEquals(2, sizes["Lobby"])
        assertEquals(1, sizes["BedWars"])
        assertEquals(2, sizes.size)
    }

    // ---------------------------------------------------------------------------
    // 5. drain(groupName) empties pool, returns correct drain count
    // ---------------------------------------------------------------------------

    @Test
    fun `drain empties pool and returns count`() = runTest {
        val groupName = "Lobby"
        val gm = mockk<GroupManager>()
        every { gm.getGroup(groupName) } returns ServerGroup(groupConfig(groupName, warmPoolSize = 3))

        val factory = factoryThatPrepares(groupName)
        val portAllocator = mockk<PortAllocator>(relaxed = true)
        val registry = ServiceRegistry()
        val mgr = buildManager(gm, factory, registry = registry, portAllocator = portAllocator)

        mgr.fill(groupName)
        assertEquals(3, mgr.poolSize(groupName))

        val drained = mgr.drain(groupName)
        assertEquals(3, drained)
        assertEquals(0, mgr.poolSize(groupName))
        // Pool entry should be gone from allPoolSizes (only non-zero are included)
        assertFalse(mgr.allPoolSizes().containsKey(groupName))
    }

    // ---------------------------------------------------------------------------
    // 6. drain(groupName) on unknown group returns 0, no crash
    // ---------------------------------------------------------------------------

    @Test
    fun `drain on unknown group returns 0`() {
        val gm = mockk<GroupManager>()
        val factory = mockk<ServiceFactory>()
        val mgr = buildManager(gm, factory)

        val result = mgr.drain("NonExistent")
        assertEquals(0, result)
    }

    // ---------------------------------------------------------------------------
    // 7. fill() does NOT exceed globalMaxServices cap
    // ---------------------------------------------------------------------------

    @Test
    fun `fill does not exceed globalMaxServices cap`() = runTest {
        val groupName = "Lobby"
        val gm = mockk<GroupManager>()
        // Ask for 5 warm pool slots but the global max is 2
        every { gm.getGroup(groupName) } returns ServerGroup(groupConfig(groupName, warmPoolSize = 5))

        val factory = factoryThatPrepares(groupName)
        val registry = ServiceRegistry() // empty — no running services
        val mgr = buildManager(gm, factory, registry = registry, globalMax = 2)

        val filled = mgr.fill(groupName)
        // Should only fill up to globalMaxServices (2), not the requested 5
        assertEquals(2, filled)
        assertEquals(2, mgr.poolSize(groupName))
    }

    @Test
    fun `fill respects globalMaxServices when registry already has running services`() = runTest {
        val groupName = "Lobby"
        val gm = mockk<GroupManager>()
        every { gm.getGroup(groupName) } returns ServerGroup(groupConfig(groupName, warmPoolSize = 3))

        val factory = factoryThatPrepares(groupName)
        val registry = ServiceRegistry()
        // Pre-populate registry with 1 already-running service
        registry.register(createService("Lobby-0", groupName))
        // globalMax = 2; registry already has 1 → only 1 warm pool slot available
        val mgr = buildManager(gm, factory, registry = registry, globalMax = 2)

        val filled = mgr.fill(groupName)
        assertEquals(1, filled)
        assertEquals(1, mgr.poolSize(groupName))
    }

    // ---------------------------------------------------------------------------
    // 8. fill() on group with disabled warm pool (warm_pool_size=0) returns 0
    // ---------------------------------------------------------------------------

    @Test
    fun `fill returns 0 when warmPoolSize is 0`() = runTest {
        val groupName = "Lobby"
        val gm = mockk<GroupManager>()
        every { gm.getGroup(groupName) } returns ServerGroup(groupConfig(groupName, warmPoolSize = 0))

        val factory = mockk<ServiceFactory>() // should never be called
        val mgr = buildManager(gm, factory)

        val filled = mgr.fill(groupName)
        assertEquals(0, filled)
        assertEquals(0, mgr.poolSize(groupName))
    }

    @Test
    fun `fill returns 0 for unknown group`() = runTest {
        val gm = mockk<GroupManager>()
        every { gm.getGroup("NoSuch") } returns null

        val factory = mockk<ServiceFactory>()
        val mgr = buildManager(gm, factory)

        val filled = mgr.fill("NoSuch")
        assertEquals(0, filled)
    }

    // ---------------------------------------------------------------------------
    // 9. shutdown() cleans up all pools
    // ---------------------------------------------------------------------------

    @Test
    fun `shutdown empties all pools`() = runTest {
        val gm = mockk<GroupManager>()
        every { gm.getGroup("Lobby") } returns ServerGroup(groupConfig("Lobby", warmPoolSize = 2))
        every { gm.getGroup("BedWars") } returns ServerGroup(groupConfig("BedWars", warmPoolSize = 2))

        val factory = mockk<ServiceFactory>()
        var lobbyIdx = 0
        var bedwarsIdx = 0
        coEvery { factory.prepare("Lobby") } answers {
            val svc = createService("Lobby-${++lobbyIdx}", "Lobby")
            ServiceFactory.PreparedService(
                service = svc, workDir = Path.of("/tmp/nimbus-test/lobby"),
                command = emptyList(), readyPattern = null, isModded = false, readyTimeout = 120.seconds
            )
        }
        coEvery { factory.prepare("BedWars") } answers {
            val svc = createService("BedWars-${++bedwarsIdx}", "BedWars")
            ServiceFactory.PreparedService(
                service = svc, workDir = Path.of("/tmp/nimbus-test/bedwars"),
                command = emptyList(), readyPattern = null, isModded = false, readyTimeout = 120.seconds
            )
        }

        val mgr = buildManager(gm, factory)
        mgr.fill("Lobby")
        mgr.fill("BedWars")

        assertEquals(2, mgr.poolSize("Lobby"))
        assertEquals(2, mgr.poolSize("BedWars"))

        mgr.shutdown()

        assertTrue(mgr.allPoolSizes().isEmpty(), "allPoolSizes should be empty after shutdown")
        assertEquals(0, mgr.poolSize("Lobby"))
        assertEquals(0, mgr.poolSize("BedWars"))
    }

    // ---------------------------------------------------------------------------
    // 10. Concurrent take() + fill() — no exception, pool size >= 0
    // ---------------------------------------------------------------------------

    @Test
    fun `concurrent take and fill do not throw and pool size stays non-negative`() = runTest {
        val groupName = "Lobby"
        val gm = mockk<GroupManager>()
        every { gm.getGroup(groupName) } returns ServerGroup(groupConfig(groupName, warmPoolSize = 5))

        val factory = mockk<ServiceFactory>()
        var idx = 0
        coEvery { factory.prepare(groupName) } answers {
            val svc = createService("$groupName-${++idx}", groupName)
            ServiceFactory.PreparedService(
                service = svc, workDir = Path.of("/tmp/nimbus-test/$groupName-$idx"),
                command = emptyList(), readyPattern = null, isModded = false, readyTimeout = 120.seconds
            )
        }

        val mgr = buildManager(gm, factory, globalMax = 0 /* no cap */)

        // 10 coroutines filling + 10 coroutines taking — all interleaved
        val fillJobs = (1..10).map {
            async(Dispatchers.Default) { mgr.fill(groupName) }
        }
        val takeJobs = (1..10).map {
            async(Dispatchers.Default) { mgr.take(groupName) }
        }

        // Await all — no exception should propagate
        fillJobs.awaitAll()
        takeJobs.awaitAll()

        // Pool must never be negative
        assertTrue(mgr.poolSize(groupName) >= 0)
    }
}
