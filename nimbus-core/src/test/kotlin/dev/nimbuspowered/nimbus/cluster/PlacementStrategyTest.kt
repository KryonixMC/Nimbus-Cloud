package dev.nimbuspowered.nimbus.cluster

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

    // ── Additional edge cases ────────────────────────────────────────────────

    @Test
    fun `LeastServicesPlacement throws on empty node list`() {
        // select() returns NodeConnection (non-nullable); empty list causes candidates.first() to throw
        assertThrows<NoSuchElementException> {
            LeastServicesPlacement().select(emptyList())
        }
    }

    @Test
    fun `LeastMemoryPlacement throws on empty node list`() {
        assertThrows<NoSuchElementException> {
            LeastMemoryPlacement().select(emptyList())
        }
    }

    @Test
    fun `RoundRobinPlacement throws on empty node list`() {
        // % 0 causes ArithmeticException
        assertThrows<ArithmeticException> {
            RoundRobinPlacement().select(emptyList())
        }
    }

    @Test
    fun `LeastServicesPlacement with tie picks deterministically`() {
        // Both nodes have the same service count — minByOrNull returns the first match,
        // so the result must be stable across repeated calls with the same list order.
        val a = mkNode("a", 3, 1000)
        val b = mkNode("b", 3, 2000)
        val strategy = LeastServicesPlacement()
        val first = strategy.select(listOf(a, b)).nodeId
        val second = strategy.select(listOf(a, b)).nodeId
        assertEquals(first, second, "Tie-breaking must be deterministic for the same input order")
    }

    @Test
    fun `LeastMemoryPlacement skips node with high memory in favor of lower`() {
        val highMem = mkNode("high", 1, 7000)
        val lowMem  = mkNode("low",  1,    1)
        val picked = LeastMemoryPlacement().select(listOf(highMem, lowMem))
        assertEquals("low", picked.nodeId, "Node with lowest memoryUsedMb must be selected")
    }

    @Test
    fun `LeastMemoryPlacement with all nodes at 0 free memory picks first`() {
        // All nodes at memoryUsedMb = 0 — minByOrNull returns the first one
        val a = mkNode("a", 0, 0)
        val b = mkNode("b", 0, 0)
        val picked = LeastMemoryPlacement().select(listOf(a, b))
        assertEquals("a", picked.nodeId, "When all memory usage is equal (0), first node should be picked")
    }

    @Test
    fun `RoundRobinPlacement wraps around after N nodes`() {
        val a = mkNode("a", 0, 0)
        val b = mkNode("b", 0, 0)
        val c = mkNode("c", 0, 0)
        val rr = RoundRobinPlacement()
        val nodes = listOf(a, b, c)
        // First full cycle
        assertEquals("a", rr.select(nodes).nodeId)
        assertEquals("b", rr.select(nodes).nodeId)
        assertEquals("c", rr.select(nodes).nodeId)
        // Wrap-around: 4th call returns the first node again
        assertEquals("a", rr.select(nodes).nodeId, "4th call must wrap back to first node")
    }

    @Test
    fun `RoundRobinPlacement with single node always returns that node`() {
        val only = mkNode("only", 0, 0)
        val rr = RoundRobinPlacement()
        repeat(5) {
            assertEquals("only", rr.select(listOf(only)).nodeId, "Single node must always be returned")
        }
    }
}
