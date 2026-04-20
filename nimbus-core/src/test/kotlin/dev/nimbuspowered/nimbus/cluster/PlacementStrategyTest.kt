package dev.nimbuspowered.nimbus.cluster

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlacementStrategyTest {

    private fun mkNode(id: String, services: Int, memUsed: Long): NodeConnection {
        val conn = NodeConnection(
            nodeId = id,
            host = "localhost",
            maxMemory = "8G",
            maxServices = 10,
            session = mockk(relaxed = true)
        )
        conn.currentServices = services
        conn.memoryUsedMb = memUsed
        return conn
    }

    @Test
    fun `LeastServicesPlacement picks node with fewest services`() {
        val a = mkNode("a", 5, 1000)
        val b = mkNode("b", 2, 8000)
        val c = mkNode("c", 3, 500)
        val picked = LeastServicesPlacement().select(listOf(a, b, c))
        assertEquals("b", picked.nodeId)
    }

    @Test
    fun `LeastMemoryPlacement picks node with least memory used`() {
        val a = mkNode("a", 5, 4000)
        val b = mkNode("b", 2, 8000)
        val c = mkNode("c", 3, 500)
        val picked = LeastMemoryPlacement().select(listOf(a, b, c))
        assertEquals("c", picked.nodeId)
    }

    @Test
    fun `RoundRobinPlacement cycles through nodes`() {
        val a = mkNode("a", 0, 0)
        val b = mkNode("b", 0, 0)
        val c = mkNode("c", 0, 0)
        val rr = RoundRobinPlacement()
        val nodes = listOf(a, b, c)
        val selected = (0 until 6).map { rr.select(nodes).nodeId }
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), selected)
    }

    @Test
    fun `strategies fall back to first candidate when list has one node`() {
        val only = mkNode("only", 99, 99999)
        assertEquals("only", LeastServicesPlacement().select(listOf(only)).nodeId)
        assertEquals("only", LeastMemoryPlacement().select(listOf(only)).nodeId)
        assertEquals("only", RoundRobinPlacement().select(listOf(only)).nodeId)
    }
}
