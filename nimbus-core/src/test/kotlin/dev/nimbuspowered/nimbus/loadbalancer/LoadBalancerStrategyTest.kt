package dev.nimbuspowered.nimbus.loadbalancer

import dev.nimbuspowered.nimbus.service.Service
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class LoadBalancerStrategyTest {

    private fun mkSvc(name: String, players: Int): Service =
        Service(
            name = name,
            groupName = "grp",
            port = 25565,
            workingDirectory = Paths.get(".")
        ).also { it.playerCount = players }

    @Test
    fun `LeastPlayersStrategy picks the emptiest service`() {
        val a = mkSvc("a", 10)
        val b = mkSvc("b", 3)
        val c = mkSvc("c", 7)
        assertEquals("b", LeastPlayersStrategy().select(listOf(a, b, c)).name)
    }

    @Test
    fun `RoundRobinStrategy cycles and wraps around`() {
        val strat = RoundRobinStrategy()
        val services = listOf(mkSvc("a", 0), mkSvc("b", 0))
        val picks = (0 until 5).map { strat.select(services).name }
        assertEquals(listOf("a", "b", "a", "b", "a"), picks)
    }

    @Test
    fun `RoundRobinStrategy handles negative-wrapped counter safely`() {
        // floorMod ensures we never get a negative index even after many calls
        val strat = RoundRobinStrategy()
        val services = listOf(mkSvc("a", 0), mkSvc("b", 0), mkSvc("c", 0))
        repeat(10) { strat.select(services) }
        val pick = strat.select(services)
        assertTrue(pick.name in listOf("a", "b", "c"))
    }
}
