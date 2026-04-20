package dev.nimbuspowered.nimbus.module.scaling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import kotlin.io.path.writeText

class SmartScalingConfigManagerTest {

    @Test
    fun `ensureConfig creates default disabled config`(@TempDir dir: Path) {
        val mgr = SmartScalingConfigManager(dir)
        mgr.init()
        mgr.ensureConfig("Lobby")
        mgr.reload()

        val cfg = mgr.getConfig("Lobby")
        assertNotNull(cfg)
        assertFalse(cfg!!.schedule.enabled)
        assertEquals(ZoneId.of("Europe/Berlin"), cfg.schedule.timezone)
        assertTrue(cfg.schedule.rules.isEmpty())
        assertTrue(cfg.schedule.warmup.enabled)
        assertEquals(10, cfg.schedule.warmup.leadTimeMinutes)
    }

    @Test
    fun `parses rule with days and time range`(@TempDir dir: Path) {
        dir.resolve("BedWars.toml").writeText(
            """
            [schedule]
            enabled = true
            timezone = "UTC"

            [[schedule.rules]]
            name = "evening-peak"
            days = ["MON", "TUE", "FRI"]
            from = "17:00"
            to = "23:00"
            min_instances = 4
            max_instances = 10

            [warmup]
            enabled = true
            lead_time_minutes = 15
            """.trimIndent() + "\n"
        )
        val mgr = SmartScalingConfigManager(dir)
        mgr.init()

        val cfg = mgr.getConfig("BedWars")
        assertNotNull(cfg)
        assertTrue(cfg!!.schedule.enabled)
        assertEquals(ZoneId.of("UTC"), cfg.schedule.timezone)
        assertEquals(1, cfg.schedule.rules.size)

        val rule = cfg.schedule.rules.first()
        assertEquals("evening-peak", rule.name)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), rule.days)
        assertEquals(LocalTime.of(17, 0), rule.from)
        assertEquals(LocalTime.of(23, 0), rule.to)
        assertEquals(4, rule.minInstances)
        assertEquals(10, rule.maxInstances)
        assertEquals(15, cfg.schedule.warmup.leadTimeMinutes)
    }

    @Test
    fun `invalid timezone falls back to Berlin`(@TempDir dir: Path) {
        dir.resolve("X.toml").writeText(
            """
            [schedule]
            enabled = true
            timezone = "Not/AZone"
            """.trimIndent() + "\n"
        )
        val mgr = SmartScalingConfigManager(dir)
        mgr.init()
        assertEquals(ZoneId.of("Europe/Berlin"), mgr.getConfig("X")!!.schedule.timezone)
    }

    @Test
    fun `reload picks up new files`(@TempDir dir: Path) {
        val mgr = SmartScalingConfigManager(dir)
        mgr.init()
        assertNull(mgr.getConfig("Late"))
        dir.resolve("Late.toml").writeText(
            """
            [schedule]
            enabled = false
            """.trimIndent() + "\n"
        )
        mgr.reload()
        assertNotNull(mgr.getConfig("Late"))
    }

    @Test
    fun `rule with unknown day names yields no rule`(@TempDir dir: Path) {
        dir.resolve("G.toml").writeText(
            """
            [schedule]
            enabled = true

            [[schedule.rules]]
            name = "bad"
            days = ["NOTADAY"]
            from = "10:00"
            to = "11:00"
            min_instances = 1
            """.trimIndent() + "\n"
        )
        val mgr = SmartScalingConfigManager(dir)
        mgr.init()
        assertTrue(mgr.getConfig("G")!!.schedule.rules.isEmpty())
    }

    @Test
    fun `ensureConfig is idempotent`(@TempDir dir: Path) {
        val mgr = SmartScalingConfigManager(dir)
        mgr.init()
        mgr.ensureConfig("A")
        val first = dir.resolve("A.toml").toFile().readText()
        mgr.ensureConfig("A")
        val second = dir.resolve("A.toml").toFile().readText()
        assertEquals(first, second)
    }
}
