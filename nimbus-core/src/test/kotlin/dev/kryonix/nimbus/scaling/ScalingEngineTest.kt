package dev.kryonix.nimbus.scaling

import dev.kryonix.nimbus.config.*
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.group.ServerGroup
import dev.kryonix.nimbus.service.Service
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState
import io.mockk.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class ScalingEngineTest {

    private lateinit var registry: ServiceRegistry
    private lateinit var serviceManager: ServiceManager
    private lateinit var groupManager: GroupManager
    private lateinit var eventBus: EventBus
    private lateinit var testScope: TestScope
    private lateinit var engine: ScalingEngine

    @BeforeEach
    fun setUp() {
        registry = mockk(relaxed = true)
        serviceManager = mockk(relaxed = true)
        groupManager = mockk(relaxed = true)
        testScope = TestScope()
        eventBus = EventBus(testScope)

        engine = ScalingEngine(
            registry = registry,
            serviceManager = serviceManager,
            groupManager = groupManager,
            eventBus = eventBus,
            scope = testScope,
            checkIntervalMs = 5000
        )

        // By default, return empty lists for registry.getAll() to prevent ping errors
        every { registry.getAll() } returns emptyList()
    }

    private suspend fun callEvaluate() {
        val method = ScalingEngine::class.java.getDeclaredMethod("evaluate", Continuation::class.java)
        method.isAccessible = true
        // Invoke the suspend function via continuation
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            val result = method.invoke(engine, cont)
            if (result == COROUTINE_SUSPENDED) COROUTINE_SUSPENDED
            else Unit
        }
    }

    private fun makeService(
        name: String,
        groupName: String,
        port: Int = 30000,
        state: ServiceState = ServiceState.READY,
        playerCount: Int = 0,
        customState: String? = null
    ): Service {
        val service = Service(
            name = name,
            groupName = groupName,
            port = port,
            initialState = ServiceState.PREPARING,
            customState = customState,
            workingDirectory = Path.of("/tmp/nimbus/$name")
        )
        // Transition to desired state
        when (state) {
            ServiceState.READY -> {
                service.transitionTo(ServiceState.STARTING)
                service.transitionTo(ServiceState.READY)
            }
            ServiceState.STARTING -> {
                service.transitionTo(ServiceState.STARTING)
            }
            ServiceState.STOPPING -> {
                service.transitionTo(ServiceState.STARTING)
                service.transitionTo(ServiceState.READY)
                service.transitionTo(ServiceState.STOPPING)
            }
            ServiceState.STOPPED -> {
                service.transitionTo(ServiceState.STOPPED)
            }
            ServiceState.CRASHED -> {
                service.transitionTo(ServiceState.STARTING)
                service.transitionTo(ServiceState.CRASHED)
            }
            ServiceState.PREPARING -> {} // already in PREPARING
        }
        service.playerCount = playerCount
        return service
    }

    private fun makeDynamicGroup(
        name: String,
        minInstances: Int = 1,
        maxInstances: Int = 4,
        playersPerInstance: Int = 40,
        scaleThreshold: Double = 0.8,
        idleTimeout: Long = 0
    ): ServerGroup {
        val config = GroupConfig(
            group = GroupDefinition(
                name = name,
                type = GroupType.DYNAMIC,
                scaling = ScalingConfig(
                    minInstances = minInstances,
                    maxInstances = maxInstances,
                    playersPerInstance = playersPerInstance,
                    scaleThreshold = scaleThreshold,
                    idleTimeout = idleTimeout
                )
            )
        )
        return ServerGroup(config)
    }

    private fun makeStaticGroup(name: String): ServerGroup {
        val config = GroupConfig(
            group = GroupDefinition(
                name = name,
                type = GroupType.STATIC
            )
        )
        return ServerGroup(config)
    }

    @Test
    fun `evaluate triggers startService when fill rate exceeds threshold`() = runTest {
        val group = makeDynamicGroup("Lobby", scaleThreshold = 0.8, playersPerInstance = 40)
        val service = makeService("Lobby-1", "Lobby", playerCount = 35) // 87.5% fill

        every { groupManager.getAllGroups() } returns listOf(group)
        every { registry.getByGroup("Lobby") } returns listOf(service)
        every { registry.getAll() } returns listOf(service)

        callEvaluate()

        coVerify { serviceManager.startService("Lobby") }
    }

    @Test
    fun `evaluate triggers stopService when service idle past timeout`() = runTest {
        val group = makeDynamicGroup("Lobby", idleTimeout = 60, minInstances = 1)
        val svc1 = makeService("Lobby-1", "Lobby", playerCount = 5)
        val svc2 = makeService("Lobby-2", "Lobby", playerCount = 0)

        every { groupManager.getAllGroups() } returns listOf(group)
        every { registry.getByGroup("Lobby") } returns listOf(svc1, svc2)
        every { registry.getAll() } returns listOf(svc1, svc2)

        // First call: first zero reading for Lobby-2 — should NOT trigger stop
        callEvaluate()
        coVerify(exactly = 0) { serviceManager.stopService("Lobby-2") }

        // Second call: consecutive zero reading — starts idle tracking but time hasn't elapsed
        callEvaluate()
        coVerify(exactly = 0) { serviceManager.stopService("Lobby-2") }

        // Now we need to manipulate the idleSince map to simulate elapsed time.
        // Access the internal idleSince map via reflection
        val idleSinceField = ScalingEngine::class.java.getDeclaredField("idleSince")
        idleSinceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val idleSinceMap = idleSinceField.get(engine) as java.util.concurrent.ConcurrentHashMap<String, Instant>
        // Set idle start to 120 seconds ago (well past the 60s timeout)
        idleSinceMap["Lobby-2"] = Instant.now().minusSeconds(120)

        // Third call: idle timeout has expired
        callEvaluate()
        coVerify(exactly = 1) { serviceManager.stopService("Lobby-2") }
    }

    @Test
    fun `evaluate skips static groups`() = runTest {
        val staticGroup = makeStaticGroup("Build")
        val service = makeService("Build-1", "Build", playerCount = 35)

        every { groupManager.getAllGroups() } returns listOf(staticGroup)
        every { registry.getByGroup("Build") } returns listOf(service)
        every { registry.getAll() } returns listOf(service)

        callEvaluate()

        coVerify(exactly = 0) { serviceManager.startService(any()) }
        coVerify(exactly = 0) { serviceManager.stopService(any()) }
    }

    @Test
    fun `services with customState excluded from capacity calculation`() = runTest {
        val group = makeDynamicGroup("BedWars", scaleThreshold = 0.5, playersPerInstance = 10, maxInstances = 5)
        // svc1 has customState "INGAME" — should be excluded from routable count
        val svc1 = makeService("BedWars-1", "BedWars", playerCount = 8, customState = "INGAME")
        // svc2 is routable with low fill
        val svc2 = makeService("BedWars-2", "BedWars", playerCount = 2)

        every { groupManager.getAllGroups() } returns listOf(group)
        every { registry.getByGroup("BedWars") } returns listOf(svc1, svc2)
        every { registry.getAll() } returns listOf(svc1, svc2)

        // Only svc2 is routable: 2/10 = 20% fill, below 50% threshold
        callEvaluate()

        coVerify(exactly = 0) { serviceManager.startService(any()) }
    }

    @Test
    fun `first zero reading does not trigger scale down`() = runTest {
        val group = makeDynamicGroup("Lobby", idleTimeout = 1, minInstances = 1)
        val svc1 = makeService("Lobby-1", "Lobby", playerCount = 5)
        val svc2 = makeService("Lobby-2", "Lobby", playerCount = 0)

        every { groupManager.getAllGroups() } returns listOf(group)
        every { registry.getByGroup("Lobby") } returns listOf(svc1, svc2)
        every { registry.getAll() } returns listOf(svc1, svc2)

        // First zero reading — should not scale down
        callEvaluate()

        coVerify(exactly = 0) { serviceManager.stopService("Lobby-2") }
    }

    @Test
    fun `idle tracking cleaned up for removed services`() = runTest {
        val group = makeDynamicGroup("Lobby", idleTimeout = 60, minInstances = 1)
        val svc1 = makeService("Lobby-1", "Lobby", playerCount = 5)
        val svc2 = makeService("Lobby-2", "Lobby", playerCount = 0)

        every { groupManager.getAllGroups() } returns listOf(group)
        every { registry.getByGroup("Lobby") } returns listOf(svc1, svc2)
        every { registry.getAll() } returns listOf(svc1, svc2)

        // Run twice to get past the consecutive zero check
        callEvaluate()
        callEvaluate()

        // Access idleSince and consecutiveZeroReadings via reflection
        val idleSinceField = ScalingEngine::class.java.getDeclaredField("idleSince")
        idleSinceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val idleSinceMap = idleSinceField.get(engine) as java.util.concurrent.ConcurrentHashMap<String, Instant>

        val zeroField = ScalingEngine::class.java.getDeclaredField("consecutiveZeroReadings")
        zeroField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val zeroMap = zeroField.get(engine) as java.util.concurrent.ConcurrentHashMap<String, Int>

        // Lobby-2 should be tracked
        assertTrue(idleSinceMap.containsKey("Lobby-2"))
        assertTrue(zeroMap.containsKey("Lobby-2"))

        // Now simulate Lobby-2 being removed (crashed/stopped)
        every { registry.getAll() } returns listOf(svc1)
        every { registry.getByGroup("Lobby") } returns listOf(svc1)

        callEvaluate()

        // Idle tracking for Lobby-2 should be cleaned up
        assertFalse(idleSinceMap.containsKey("Lobby-2"))
        assertFalse(zeroMap.containsKey("Lobby-2"))
    }

    @Test
    fun `evaluate does not scale up when no groups exist`() = runTest {
        every { groupManager.getAllGroups() } returns emptyList()

        callEvaluate()

        coVerify(exactly = 0) { serviceManager.startService(any()) }
        coVerify(exactly = 0) { serviceManager.stopService(any()) }
    }

    @Test
    fun `service with players resets idle tracking`() = runTest {
        val group = makeDynamicGroup("Lobby", idleTimeout = 60, minInstances = 1)
        val svc1 = makeService("Lobby-1", "Lobby", playerCount = 5)
        val svc2 = makeService("Lobby-2", "Lobby", playerCount = 0)

        every { groupManager.getAllGroups() } returns listOf(group)
        every { registry.getByGroup("Lobby") } returns listOf(svc1, svc2)
        every { registry.getAll() } returns listOf(svc1, svc2)

        // Two zero readings to start tracking
        callEvaluate()
        callEvaluate()

        // Now svc2 gets players
        svc2.playerCount = 3

        callEvaluate()

        // Access idleSince and zero readings
        val idleSinceField = ScalingEngine::class.java.getDeclaredField("idleSince")
        idleSinceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val idleSinceMap = idleSinceField.get(engine) as java.util.concurrent.ConcurrentHashMap<String, Instant>

        val zeroField = ScalingEngine::class.java.getDeclaredField("consecutiveZeroReadings")
        zeroField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val zeroMap = zeroField.get(engine) as java.util.concurrent.ConcurrentHashMap<String, Int>

        // Idle tracking should be cleared since service now has players
        assertFalse(idleSinceMap.containsKey("Lobby-2"))
        assertFalse(zeroMap.containsKey("Lobby-2"))
    }
}
