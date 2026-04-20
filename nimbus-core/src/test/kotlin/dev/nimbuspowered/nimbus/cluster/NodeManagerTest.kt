package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.config.ClusterConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NodeManagerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun mkConn(id: String, maxMem: String = "4G", maxServices: Int = 10): NodeConnection =
        NodeConnection(id, "host-$id", maxMem, maxServices, mockk(relaxed = true))

    private fun mgr(strategy: String = "least-services"): NodeManager {
        val config = ClusterConfig(placementStrategy = strategy)
        return NodeManager(config, ServiceRegistry(), EventBus(scope), scope)
    }

    @Test
    fun `placementStrategy is selected from config string`() {
        assertTrue(mgr("least-services").placementStrategy is LeastServicesPlacement)
        assertTrue(mgr("least-memory").placementStrategy is LeastMemoryPlacement)
        assertTrue(mgr("round-robin").placementStrategy is RoundRobinPlacement)
        assertTrue(mgr("unknown-strategy").placementStrategy is LeastServicesPlacement)
    }

    @Test
    fun `register and unregister track node count`() {
        val m = mgr()
        assertEquals(0, m.getNodeCount())
        m.registerNode(mkConn("a"))
        m.registerNode(mkConn("b"))
        assertEquals(2, m.getNodeCount())
        assertEquals(2, m.getOnlineNodeCount())
        m.unregisterNode("a")
        assertEquals(1, m.getNodeCount())
    }

    @Test
    fun `getNode returns the registered connection and null for unknown`() {
        val m = mgr()
        val c = mkConn("a")
        m.registerNode(c)
        assertSame(c, m.getNode("a"))
        assertNull(m.getNode("nope"))
    }

    @Test
    fun `getOnlineNodes excludes disconnected nodes`() {
        val m = mgr()
        val a = mkConn("a")
        val b = mkConn("b")
        m.registerNode(a)
        m.registerNode(b)
        b.markDisconnected()
        val online = m.getOnlineNodes()
        assertEquals(1, online.size)
        assertEquals("a", online.first().nodeId)
    }

    @Test
    fun `selectNode returns null when no nodes are available`() {
        val m = mgr()
        assertNull(m.selectNode("512M"))
    }

    @Test
    fun `selectNode skips nodes at service capacity`() {
        val m = mgr()
        val a = mkConn("a", maxServices = 1)
        a.currentServices = 1
        a.memoryTotalMb = 8192
        m.registerNode(a)
        assertNull(m.selectNode("256M"))
    }

    @Test
    fun `selectNode picks the node with fewest services when least-services`() {
        val m = mgr("least-services")
        val a = mkConn("a").apply { currentServices = 3; memoryTotalMb = 8192 }
        val b = mkConn("b").apply { currentServices = 1; memoryTotalMb = 8192 }
        m.registerNode(a)
        m.registerNode(b)
        assertEquals("b", m.selectNode("512M")?.nodeId)
    }

    @Test
    fun `selectNode skips node lacking memory budget`() {
        val m = mgr()
        val a = mkConn("a", maxMem = "1G").apply {
            servicesUsedMb = 900
            memoryTotalMb = 8192
        }
        m.registerNode(a)
        // Need 512M but only 124M service budget free → skip
        assertNull(m.selectNode("512M"))
    }
}
