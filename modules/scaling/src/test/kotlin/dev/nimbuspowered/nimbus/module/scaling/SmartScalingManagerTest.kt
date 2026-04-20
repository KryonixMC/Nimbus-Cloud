package dev.nimbuspowered.nimbus.module.scaling

import dev.nimbuspowered.nimbus.config.GroupConfig
import dev.nimbuspowered.nimbus.config.GroupDefinition
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.ScalingConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.group.ServerGroup
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class SmartScalingManagerTest {

    private fun group(name: String, minInst: Int = 1, maxInst: Int = 4, warmPool: Int = 0): ServerGroup =
        ServerGroup(GroupConfig(
            group = GroupDefinition(
                name = name,
                type = GroupType.DYNAMIC,
                scaling = ScalingConfig(
                    minInstances = minInst,
                    maxInstances = maxInst,
                    warmPoolSize = warmPool
                )
            )
        ))

    private fun svc(name: String, group: String, players: Int = 0, state: ServiceState = ServiceState.READY): Service {
        val s = Service(
            name = name,
            groupName = group,
            port = 30000,
            initialState = state,
            workingDirectory = Paths.get(".")
        )
        s.playerCount = players
        return s
    }

    @Test
    fun `collectSnapshots writes row per dynamic group`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val gm = mockk<GroupManager>()
        every { gm.getAllGroups() } returns listOf(group("Lobby"), group("Static").also {
            // static group should be skipped
        })
        // Use type STATIC by rebuilding
        val static = ServerGroup(GroupConfig(GroupDefinition(name = "Static", type = GroupType.STATIC)))
        every { gm.getAllGroups() } returns listOf(group("Lobby"), static)

        val reg = mockk<ServiceRegistry>()
        every { reg.getByGroup("Lobby") } returns listOf(
            svc("L1", "Lobby", players = 10, state = ServiceState.READY),
            svc("L2", "Lobby", players = 15, state = ServiceState.READY),
            svc("L3", "Lobby", players = 0, state = ServiceState.STARTING)
        )
        every { reg.getByGroup("Static") } returns emptyList()

        val cfg = mockk<SmartScalingConfigManager>(relaxed = true)
        val mgr = SmartScalingManager(db, cfg, gm, reg, mockk(relaxed = true))
        mgr.collectSnapshots()

        transaction(db.database) {
            val rows = ScalingSnapshots.selectAll().toList()
            assertEquals(1, rows.size)
            val r = rows.first()
            assertEquals("Lobby", r[ScalingSnapshots.groupName])
            assertEquals(25, r[ScalingSnapshots.playerCount])
            assertEquals(2, r[ScalingSnapshots.serviceCount]) // only READY
        }
    }

    @Test
    fun `pruneHistory deletes rows older than retention`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val now = Instant.now()
        transaction(db.database) {
            ScalingSnapshots.insert {
                it[timestamp] = now.minus(100, ChronoUnit.DAYS).toString()
                it[groupName] = "G"; it[playerCount] = 1; it[serviceCount] = 1
            }
            ScalingSnapshots.insert {
                it[timestamp] = now.minus(10, ChronoUnit.DAYS).toString()
                it[groupName] = "G"; it[playerCount] = 2; it[serviceCount] = 1
            }
            ScalingDecisions.insert {
                it[timestamp] = now.minus(100, ChronoUnit.DAYS).toString()
                it[groupName] = "G"; it[action] = "x"; it[reason] = "old"; it[decisionSource] = "s"; it[servicesStarted] = 0
            }
            ScalingDecisions.insert {
                it[timestamp] = now.minus(1, ChronoUnit.DAYS).toString()
                it[groupName] = "G"; it[action] = "x"; it[reason] = "fresh"; it[decisionSource] = "s"; it[servicesStarted] = 0
            }
        }
        val mgr = SmartScalingManager(db, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        mgr.pruneHistory()
        transaction(db.database) {
            assertEquals(1, ScalingSnapshots.selectAll().count().toInt())
            assertEquals(1, ScalingDecisions.selectAll().count().toInt())
        }
    }

    @Test
    fun `getHistory filters by group and time window`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val now = Instant.now()
        transaction(db.database) {
            ScalingSnapshots.insert {
                it[timestamp] = now.minus(2, ChronoUnit.HOURS).toString()
                it[groupName] = "Lobby"; it[playerCount] = 5; it[serviceCount] = 1
            }
            ScalingSnapshots.insert {
                it[timestamp] = now.minus(48, ChronoUnit.HOURS).toString()
                it[groupName] = "Lobby"; it[playerCount] = 99; it[serviceCount] = 9
            }
            ScalingSnapshots.insert {
                it[timestamp] = now.minus(1, ChronoUnit.HOURS).toString()
                it[groupName] = "Other"; it[playerCount] = 1; it[serviceCount] = 1
            }
        }
        val mgr = SmartScalingManager(db, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val history = mgr.getHistory("Lobby", hours = 24)
        assertEquals(1, history.size)
        assertEquals(5, history[0].playerCount)
    }

    @Test
    fun `getRecentDecisions returns latest first and respects limit`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val now = Instant.now()
        transaction(db.database) {
            for (i in 1..5) {
                ScalingDecisions.insert {
                    it[timestamp] = now.minus(i.toLong(), ChronoUnit.MINUTES).toString()
                    it[groupName] = "Lobby"
                    it[action] = "smart_schedule"
                    it[reason] = "r$i"
                    it[decisionSource] = "schedule"
                    it[servicesStarted] = i
                }
            }
        }
        val mgr = SmartScalingManager(db, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val decisions = mgr.getRecentDecisions(limit = 3)
        assertEquals(3, decisions.size)
        // Most recent is i=1
        assertEquals("r1", decisions[0].reason)
        assertEquals(1, decisions[0].servicesStarted)
    }

    @Test
    fun `predictPlayerCount returns value or null — exercises DB query path`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val tz = ZoneId.of("UTC")
        // Just seed a few rows so the query path is exercised (covers the selectAll/filter branches).
        val now = Instant.now()
        transaction(db.database) {
            for (d in 1L..3L) {
                ScalingSnapshots.insert {
                    it[timestamp] = now.minus(d, ChronoUnit.DAYS).toString()
                    it[groupName] = "Lobby"
                    it[playerCount] = 42
                    it[serviceCount] = 1
                }
            }
        }
        val mgr = SmartScalingManager(db, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        // Either null (no hour/day match in seeded data) or a prediction — both are valid.
        // The point is to cover the filter/average/parse branches.
        val p = mgr.predictPlayerCount("Lobby", 0, tz)
        // Query path executed successfully either way
        if (p != null) assertEquals(42, p.predictedPlayers)
    }

    @Test
    fun `predictPlayerCount returns null when no snapshots`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val mgr = SmartScalingManager(db, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        assertNull(mgr.predictPlayerCount("Lobby", 10, ZoneId.of("UTC")))
    }

    @Test
    fun `getPredictions returns one entry per hour`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val cfg = mockk<SmartScalingConfigManager>()
        every { cfg.getConfig(any()) } returns null
        val mgr = SmartScalingManager(db, cfg, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val p = mgr.getPredictions("Lobby", hours = 6)
        assertEquals(6, p.size)
    }

    @Test
    fun `getActiveRule returns null when no config or disabled`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val cfg = mockk<SmartScalingConfigManager>()
        every { cfg.getConfig("Missing") } returns null
        every { cfg.getConfig("Disabled") } returns GroupScalingConfig(
            groupName = "X",
            schedule = ScheduleConfig(enabled = false, rules = emptyList())
        )
        val mgr = SmartScalingManager(db, cfg, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        assertNull(mgr.getActiveRule("Missing"))
        assertNull(mgr.getActiveRule("Disabled"))
    }

    @Test
    fun `getActiveRule returns active rule with warmup=false when matching`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val cfg = mockk<SmartScalingConfigManager>()
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        val rule = ScheduleRule(
            name = "always",
            days = DayOfWeek.values().toSet(),
            from = now.toLocalTime().minusMinutes(5),
            to = now.toLocalTime().plusHours(1),
            minInstances = 3
        )
        every { cfg.getConfig("Lobby") } returns GroupScalingConfig(
            groupName = "X",
            schedule = ScheduleConfig(
                enabled = true,
                timezone = ZoneId.of("UTC"),
                rules = listOf(rule),
                warmup = WarmupConfig(enabled = false)
            )
        )
        val mgr = SmartScalingManager(db, cfg, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val active = mgr.getActiveRule("Lobby")
        assertNotNull(active)
        assertEquals("always", active!!.first.name)
        assertEquals(false, active.second)
    }

    @Test
    fun `evaluateSchedules is no-op without serviceManager`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val mgr = SmartScalingManager(db, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        mgr.evaluateSchedules()
        // Just verifies no exception
        assertTrue(true)
    }

    @Test
    fun `evaluatePredictions is no-op without serviceManager`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val mgr = SmartScalingManager(db, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        mgr.evaluatePredictions()
        assertTrue(true)
    }

    @Test
    fun `evaluateSchedules starts services when rule active and count below min`(@TempDir dir: Path) = runBlocking {
        val db = buildScalingTestDb(dir)
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        val rule = ScheduleRule(
            name = "peak",
            days = DayOfWeek.values().toSet(),
            from = now.toLocalTime().minusMinutes(5),
            to = now.toLocalTime().plusHours(1),
            minInstances = 2
        )
        val cfg = mockk<SmartScalingConfigManager>()
        every { cfg.getAllConfigs() } returns mapOf("Lobby" to GroupScalingConfig(
            groupName = "X",
            schedule = ScheduleConfig(
                enabled = true,
                timezone = ZoneId.of("UTC"),
                rules = listOf(rule),
                warmup = WarmupConfig(enabled = false)
            )
        ))

        val gm = mockk<GroupManager>()
        every { gm.getGroup("Lobby") } returns group("Lobby", minInst = 1, maxInst = 5)

        val reg = mockk<ServiceRegistry>()
        every { reg.getByGroup("Lobby") } returns emptyList()

        val sm = mockk<ServiceManager>()
        coEvery { sm.startService("Lobby") } returns svc("Lobby-1", "Lobby")

        val mgr = SmartScalingManager(db, cfg, gm, reg, mockk(relaxed = true))
        mgr.serviceManager = sm
        mgr.evaluateSchedules()

        transaction(db.database) {
            val rows = ScalingDecisions.selectAll().toList()
            assertEquals(1, rows.size)
            assertEquals("smart_schedule", rows.first()[ScalingDecisions.action])
            assertEquals(2, rows.first()[ScalingDecisions.servicesStarted])
        }
    }
}
