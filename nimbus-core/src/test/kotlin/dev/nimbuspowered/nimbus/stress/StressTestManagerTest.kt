package dev.nimbuspowered.nimbus.stress

import dev.nimbuspowered.nimbus.config.GroupConfig
import dev.nimbuspowered.nimbus.config.GroupDefinition
import dev.nimbuspowered.nimbus.config.ResourcesConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.group.ServerGroup
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class StressTestManagerTest {

    private fun group(name: String, software: ServerSoftware, maxPlayers: Int = 50): ServerGroup {
        return ServerGroup(
            GroupConfig(
                group = GroupDefinition(
                    name = name,
                    software = software,
                    resources = ResourcesConfig(memory = "1G", maxPlayers = maxPlayers)
                )
            )
        )
    }

    private fun readyService(name: String, group: String, port: Int = 30000): Service {
        val s = Service(
            name = name,
            groupName = group,
            port = port,
            workingDirectory = Path.of("/tmp/$name")
        )
        s.transitionTo(ServiceState.STARTING)
        s.transitionTo(ServiceState.READY)
        return s
    }

    @Test
    fun `cannot start twice`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val lobby = group("Lobby", ServerSoftware.PAPER)
        every { gm.getGroup("Lobby") } returns lobby
        every { gm.getAllGroups() } returns listOf(lobby)

        val mgr = StressTestManager(registry, gm, EventBus(this), null, TestScope())
        assertTrue(mgr.start("Lobby", 10, 0))
        assertFalse(mgr.start("Lobby", 20, 0))
        mgr.stop()
    }

    @Test
    fun `cannot target a proxy group directly`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val proxy = group("Proxy", ServerSoftware.VELOCITY)
        every { gm.getGroup("Proxy") } returns proxy
        every { gm.getAllGroups() } returns listOf(proxy)

        val mgr = StressTestManager(registry, gm, EventBus(this), null, TestScope())
        assertFalse(mgr.start("Proxy", 100, 0))
        assertFalse(mgr.isActive())
    }

    @Test
    fun `distributes players across backend services respecting maxPlayers cap`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val lobby = group("Lobby", ServerSoftware.PAPER, maxPlayers = 30)
        every { gm.getGroup("Lobby") } returns lobby
        every { gm.getAllGroups() } returns listOf(lobby)

        val s1 = readyService("Lobby-1", "Lobby", 30001)
        val s2 = readyService("Lobby-2", "Lobby", 30002)
        registry.register(s1)
        registry.register(s2)

        val testScope = TestScope()
        val mgr = StressTestManager(registry, gm, EventBus(testScope), null, testScope)
        assertTrue(mgr.start("Lobby", 40, 0))

        testScope.runCurrent()

        // Total = 40, 2 services × 30 cap = 60 capacity. Distributes ~20/20.
        val total = s1.playerCount + s2.playerCount
        assertEquals(40, total)
        assertTrue(s1.playerCount <= 30)
        assertTrue(s2.playerCount <= 30)

        mgr.stop()
    }

    @Test
    fun `overflow tracked when target exceeds total capacity`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val lobby = group("Lobby", ServerSoftware.PAPER, maxPlayers = 10)
        every { gm.getGroup("Lobby") } returns lobby
        every { gm.getAllGroups() } returns listOf(lobby)

        val s1 = readyService("Lobby-1", "Lobby", 30001)
        registry.register(s1)

        val testScope = TestScope()
        val mgr = StressTestManager(registry, gm, EventBus(testScope), null, testScope)
        mgr.start("Lobby", 100, 0)
        testScope.runCurrent()

        assertEquals(10, s1.playerCount) // capped
        val status = mgr.getStatus()!!
        assertEquals(90, status.profile.overflow)

        mgr.stop()
    }

    @Test
    fun `proxy service reflects total backend player count`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val lobby = group("Lobby", ServerSoftware.PAPER, maxPlayers = 50)
        val proxy = group("Proxy", ServerSoftware.VELOCITY, maxPlayers = 500)
        every { gm.getGroup("Lobby") } returns lobby
        every { gm.getGroup("Proxy") } returns proxy
        every { gm.getAllGroups() } returns listOf(lobby, proxy)

        val lobbySvc = readyService("Lobby-1", "Lobby", 30001)
        val proxySvc = readyService("Proxy-1", "Proxy", 25565)
        registry.register(lobbySvc)
        registry.register(proxySvc)

        val testScope = TestScope()
        val mgr = StressTestManager(registry, gm, EventBus(testScope), null, testScope)
        mgr.start("Lobby", 20, 0)
        testScope.runCurrent()

        assertEquals(20, lobbySvc.playerCount)
        assertEquals(20, proxySvc.playerCount)

        mgr.stop()
    }

    @Test
    fun `stop clears all overrides and resets player counts`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val lobby = group("Lobby", ServerSoftware.PAPER, maxPlayers = 50)
        every { gm.getGroup("Lobby") } returns lobby
        every { gm.getAllGroups() } returns listOf(lobby)

        val s1 = readyService("Lobby-1", "Lobby", 30001)
        registry.register(s1)

        val testScope = TestScope()
        val mgr = StressTestManager(registry, gm, EventBus(testScope), null, testScope)
        mgr.start("Lobby", 25, 0)
        testScope.runCurrent()
        assertEquals(25, s1.playerCount)

        mgr.stop()
        assertEquals(0, s1.playerCount)
        assertFalse(mgr.isActive())
        assertFalse(mgr.isOverridden("Lobby-1"))
    }

    @Test
    fun `isOverridden reflects active overrides`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val lobby = group("Lobby", ServerSoftware.PAPER, maxPlayers = 50)
        every { gm.getGroup("Lobby") } returns lobby
        every { gm.getAllGroups() } returns listOf(lobby)

        val s1 = readyService("Lobby-1", "Lobby", 30001)
        registry.register(s1)

        val testScope = TestScope()
        val mgr = StressTestManager(registry, gm, EventBus(testScope), null, testScope)
        assertFalse(mgr.isOverridden("Lobby-1"))

        mgr.start("Lobby", 10, 0)
        testScope.runCurrent()
        assertTrue(mgr.isOverridden("Lobby-1"))

        mgr.stop()
    }

    @Test
    fun `ramp adjusts the target player count`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        val lobby = group("Lobby", ServerSoftware.PAPER, maxPlayers = 100)
        every { gm.getGroup("Lobby") } returns lobby
        every { gm.getAllGroups() } returns listOf(lobby)

        val s1 = readyService("Lobby-1", "Lobby", 30001)
        registry.register(s1)

        val testScope = TestScope()
        val mgr = StressTestManager(registry, gm, EventBus(testScope), null, testScope)
        mgr.start("Lobby", 30, 0)
        testScope.runCurrent()
        assertEquals(30, s1.playerCount)

        assertTrue(mgr.ramp(50, 0))
        testScope.runCurrent()
        assertEquals(50, s1.playerCount)

        // Ramp with no active test should fail
        mgr.stop()
        assertFalse(mgr.ramp(10, 0))
    }

    @Test
    fun `ramp rejected when no active test`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        every { gm.getAllGroups() } returns emptyList()

        val mgr = StressTestManager(registry, gm, EventBus(this), null, TestScope())
        assertFalse(mgr.ramp(50, 1000))
    }

    @Test
    fun `getStatus returns null when idle`() = runTest {
        val registry = ServiceRegistry()
        val gm = mockk<GroupManager>(relaxed = true)
        every { gm.getAllGroups() } returns emptyList()

        val mgr = StressTestManager(registry, gm, EventBus(this), null, TestScope())
        assertNull(mgr.getStatus())
    }
}
