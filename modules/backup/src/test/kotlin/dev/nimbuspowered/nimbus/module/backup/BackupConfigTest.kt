package dev.nimbuspowered.nimbus.module.backup

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.readText

class BackupConfigTest {

    private lateinit var dir: Path
    private lateinit var mgr: BackupConfigManager

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("nimbus-backup-cfg-")
        mgr = BackupConfigManager(dir)
    }

    @AfterEach
    fun tearDown() {
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `init creates default toml and loads it`() {
        mgr.init()
        val file = dir.resolve("backup.toml")
        assertTrue(Files.exists(file))
        val cfg = mgr.getConfig()
        assertTrue(cfg.enabled)
        assertEquals(3, cfg.compressionLevel)
        assertTrue(cfg.schedules.isNotEmpty())
    }

    @Test
    fun `update validates and atomically rewrites`() {
        mgr.init()
        val cfg = mgr.getConfig().copy(
            compressionLevel = 7,
            schedules = listOf(
                ScheduleEntry("nightly", "0 3 * * *", "daily", listOf("all"))
            )
        )
        mgr.update(cfg)
        assertEquals(7, mgr.getConfig().compressionLevel)
        assertEquals("nightly", mgr.getConfig().schedules.single().name)

        // Round-trip: reload from disk and verify it survives
        val fresh = BackupConfigManager(dir)
        fresh.init()
        assertEquals(7, fresh.getConfig().compressionLevel)
        assertEquals("nightly", fresh.getConfig().schedules.single().name)

        // And the rewritten TOML contains the new schedule
        val text = dir.resolve("backup.toml").readText()
        assertTrue(text.contains("nightly"), "expected 'nightly' in rewritten TOML")
    }

    @Test
    fun `update rejects invalid compressionLevel`() {
        mgr.init()
        val bad = mgr.getConfig().copy(compressionLevel = 99)
        assertThrows<IllegalArgumentException> { mgr.update(bad) }
    }

    @Test
    fun `update rejects invalid cron`() {
        mgr.init()
        val bad = mgr.getConfig().copy(
            schedules = listOf(ScheduleEntry("bad", "this is not cron", "daily", listOf("all")))
        )
        assertThrows<IllegalArgumentException> { mgr.update(bad) }
    }

    @Test
    fun `update rejects duplicate schedule names`() {
        mgr.init()
        val bad = mgr.getConfig().copy(
            schedules = listOf(
                ScheduleEntry("x", "0 1 * * *", "daily", listOf("all")),
                ScheduleEntry("x", "0 2 * * *", "daily", listOf("all"))
            )
        )
        assertThrows<IllegalArgumentException> { mgr.update(bad) }
    }

    @Test
    fun `update rejects unknown target`() {
        mgr.init()
        val bad = mgr.getConfig().copy(
            schedules = listOf(ScheduleEntry("s", "0 1 * * *", "daily", listOf("not_a_target")))
        )
        assertThrows<IllegalArgumentException> { mgr.update(bad) }
    }

    @Test
    fun `update rejects blank schedule name`() {
        mgr.init()
        val bad = mgr.getConfig().copy(
            schedules = listOf(ScheduleEntry("", "0 1 * * *", "daily", listOf("all")))
        )
        assertThrows<IllegalArgumentException> { mgr.update(bad) }
    }
}
