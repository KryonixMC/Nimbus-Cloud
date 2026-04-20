package dev.nimbuspowered.nimbus.loadbalancer

import dev.nimbuspowered.nimbus.config.LoadBalancerConfig
import dev.nimbuspowered.nimbus.event.EventBus
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackendHealthManagerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val eventBus = mockk<EventBus>(relaxed = true)
    private val config = LoadBalancerConfig(
        unhealthyThreshold = 3,
        healthyThreshold = 2
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `ensureTracked registers a backend once`() {
        val m = BackendHealthManager(config, eventBus, scope)
        m.ensureTracked("1.2.3.4", 25565)
        m.ensureTracked("1.2.3.4", 25565)
        assertEquals(1, m.getAll().size)
    }

    @Test
    fun `unknown backends are assumed healthy`() {
        val m = BackendHealthManager(config, eventBus, scope)
        assertTrue(m.isHealthy("nobody", 25565))
    }

    @Test
    fun `recordFailure flips to UNHEALTHY once threshold hit`() {
        val m = BackendHealthManager(config, eventBus, scope)
        m.ensureTracked("host", 25565)
        assertTrue(m.isHealthy("host", 25565))

        m.recordFailure("host", 25565)
        m.recordFailure("host", 25565)
        assertTrue(m.isHealthy("host", 25565)) // 2 < threshold of 3
        m.recordFailure("host", 25565)
        assertFalse(m.isHealthy("host", 25565))
    }

    @Test
    fun `recordFailure on unknown backend is safe no-op`() {
        val m = BackendHealthManager(config, eventBus, scope)
        m.recordFailure("ghost", 25565) // no crash
        assertTrue(m.isHealthy("ghost", 25565))
    }

    @Test
    fun `markDraining flips isHealthy to false`() {
        val m = BackendHealthManager(config, eventBus, scope)
        m.ensureTracked("host", 25565)
        assertTrue(m.isHealthy("host", 25565))
        m.markDraining("host", 25565)
        assertFalse(m.isHealthy("host", 25565))
    }

    @Test
    fun `increment and decrement connections track count`() {
        val m = BackendHealthManager(config, eventBus, scope)
        m.ensureTracked("host", 25565)
        m.incrementConnections("host", 25565)
        m.incrementConnections("host", 25565)
        val s = m.get("host", 25565)!!
        assertEquals(2, s.activeConnections.get())
        m.decrementConnections("host", 25565)
        assertEquals(1, s.activeConnections.get())
    }

    @Test
    fun `remove deletes the backend entry`() {
        val m = BackendHealthManager(config, eventBus, scope)
        m.ensureTracked("host", 25565)
        assertEquals(1, m.getAll().size)
        m.remove("host", 25565)
        assertTrue(m.getAll().isEmpty())
    }
}
