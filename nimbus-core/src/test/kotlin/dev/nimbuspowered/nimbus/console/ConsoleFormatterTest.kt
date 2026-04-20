package dev.nimbuspowered.nimbus.console

import dev.nimbuspowered.nimbus.console.ConsoleFormatter.stripAnsi
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.service.ServiceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ConsoleFormatterTest {

    @Test
    fun `colorize wraps with ANSI sequence and RESET`() {
        val colored = ConsoleFormatter.colorize("hi", ConsoleFormatter.RED)
        assertTrue(colored.startsWith(ConsoleFormatter.RED))
        assertTrue(colored.endsWith(ConsoleFormatter.RESET))
        assertEquals("hi", colored.stripAnsi())
    }

    @Test
    fun `stripAnsi removes all escape codes`() {
        val msg = ConsoleFormatter.success("done") + " " + ConsoleFormatter.error("bad")
        assertEquals("done bad", msg.stripAnsi())
    }

    @Test
    fun `yesNo and enabledDisabled branch correctly`() {
        assertTrue(ConsoleFormatter.yesNo(true).stripAnsi() == "yes")
        assertTrue(ConsoleFormatter.yesNo(false).stripAnsi() == "no")
        assertTrue(ConsoleFormatter.enabledDisabled(true).stripAnsi() == "ENABLED")
        assertTrue(ConsoleFormatter.enabledDisabled(false).stripAnsi() == "DISABLED")
    }

    @Test
    fun `count uses singular for n=1 and plural otherwise`() {
        assertEquals("1 service", ConsoleFormatter.count(1, "service").stripAnsi())
        assertEquals("0 services", ConsoleFormatter.count(0, "service").stripAnsi())
        assertEquals("3 services", ConsoleFormatter.count(3, "service").stripAnsi())
        assertEquals("2 geese", ConsoleFormatter.count(2, "goose", "geese").stripAnsi())
    }

    @Test
    fun `progressBar renders filled-plus-empty equal to width`() {
        val bar = ConsoleFormatter.progressBar(3, 10, width = 20)
        // Bar contains 20 cells total: filled + empty
        val plain = bar.stripAnsi()
        assertEquals(20, plain.length)
        assertTrue(plain.contains("█"))
        assertTrue(plain.contains("░"))
    }

    @Test
    fun `progressBar caps at width when current equals max`() {
        val plain = ConsoleFormatter.progressBar(10, 10, width = 10).stripAnsi()
        assertEquals(10, plain.length)
        assertEquals(10, plain.count { it == '█' })
    }

    @Test
    fun `progressBar with zero max has only empty cells`() {
        val plain = ConsoleFormatter.progressBar(0, 0, width = 5).stripAnsi()
        assertEquals(5, plain.length)
        assertEquals(5, plain.count { it == '░' })
    }

    @Test
    fun `formatTable pads columns and includes headers`() {
        val out = ConsoleFormatter.formatTable(
            headers = listOf("NAME", "STATE"),
            rows = listOf(
                listOf("Lobby-1", "READY"),
                listOf("BW-12", "STOPPED")
            )
        )
        val plain = out.stripAnsi()
        assertTrue(plain.contains("NAME"))
        assertTrue(plain.contains("Lobby-1"))
        assertTrue(plain.contains("STOPPED"))
    }

    @Test
    fun `formatUptime null returns placeholder`() {
        val plain = ConsoleFormatter.formatUptime(null).stripAnsi()
        assertEquals("-", plain)
    }

    @Test
    fun `formatUptime seconds under a minute formats as Ns`() {
        val start = Instant.now().minusSeconds(45)
        val uptime = ConsoleFormatter.formatUptime(start).stripAnsi()
        assertTrue(uptime.endsWith("s"))
    }

    @Test
    fun `formatSessionDuration picks the right bucket`() {
        assertEquals("59s", ConsoleFormatter.formatSessionDuration(59))
        assertEquals("1m 30s", ConsoleFormatter.formatSessionDuration(90))
        assertEquals("1h 1m", ConsoleFormatter.formatSessionDuration(3660))
        assertEquals("1d 1h", ConsoleFormatter.formatSessionDuration(90000))
    }

    @Test
    fun `stateIcon and stateColor are defined for all ServiceStates`() {
        for (s in ServiceState.entries) {
            assertNotNull(ConsoleFormatter.stateIcon(s))
            assertNotNull(ConsoleFormatter.stateColor(s))
            assertTrue(ConsoleFormatter.coloredState(s).stripAnsi().contains(s.name))
        }
    }

    @Test
    fun `formatEvent renders a basic service event with timestamp`() {
        val evt = NimbusEvent.ServiceReady("Lobby-1", "Lobby")
        val plain = ConsoleFormatter.formatEvent(evt).stripAnsi()
        assertTrue(plain.contains("READY"))
        assertTrue(plain.contains("Lobby-1"))
        // timestamp HH:mm:ss format
        assertTrue(plain.contains(":"))
    }

    @Test
    fun `module event formatter is used when registered`() {
        ConsoleFormatter.registerModuleEventFormatter("test-formatter-evt") { data ->
            "[MF] ${data["k"]}"
        }
        val evt = NimbusEvent.ModuleEvent(
            moduleId = "mymod",
            type = "test-formatter-evt",
            data = mapOf("k" to "v")
        )
        val plain = ConsoleFormatter.formatEvent(evt).stripAnsi()
        assertTrue(plain.contains("[MF] v"))
    }
}
