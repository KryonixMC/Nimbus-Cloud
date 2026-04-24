package dev.nimbuspowered.nimbus.module.perms

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * Tests that multiple logical callers invoking the PermissionManager API in turn do
 * not corrupt in-memory state or produce unexpected exceptions.
 *
 * SQLite supports only one writer at a time. The tests here serialise all DB writes
 * behind a [Mutex] so each `db.query { }` call completes before the next begins —
 * exactly the same guarantee the production code assumes when it is driven by a
 * single-threaded event loop. The Mutex mimics that serialisation without requiring
 * thread-parallel code and avoids SQLITE_BUSY errors in tests.
 */
class PermissionConcurrencyTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var mgr: PermissionManager

    @BeforeEach
    fun setup() = runTest {
        mgr = PermissionManager(buildPermsTestDb(tmp))
        mgr.init()
    }

    @Test
    fun `sequential addPermission across many groups accumulates correctly`() = runTest {
        // Create 10 groups, then add 5 permissions to each in interleaved order
        // (group 1 perm 1, group 2 perm 1, …, group 1 perm 2, …) — mimics many
        // callers driving the manager in turn.
        for (i in 1..10) {
            mgr.createGroup("ConcGroup-$i")
        }

        val dbMutex = Mutex()
        for (j in 1..5) {
            for (i in 1..10) {
                dbMutex.withLock {
                    mgr.addPermission("ConcGroup-$i", "conc.group$i.perm$j")
                }
            }
        }

        // Every group must have all 5 permissions
        for (i in 1..10) {
            val group = mgr.getGroup("ConcGroup-$i")
            assertNotNull(group, "Group ConcGroup-$i should exist")
            for (j in 1..5) {
                assertTrue(
                    "conc.group$i.perm$j" in group!!.permissions,
                    "Group ConcGroup-$i should have perm conc.group$i.perm$j"
                )
            }
        }
    }

    @Test
    fun `registerPlayer for many different UUIDs accumulates correctly`() = runTest {
        val uuids = (1..50).map { UUID.randomUUID().toString() }

        val dbMutex = Mutex()
        uuids.forEachIndexed { idx, uuid ->
            dbMutex.withLock {
                mgr.registerPlayer(uuid, "Player-$idx")
            }
        }

        for ((idx, uuid) in uuids.withIndex()) {
            assertNotNull(mgr.getPlayer(uuid), "Player $uuid (Player-$idx) should be registered")
        }
    }

    @Test
    fun `interleaved promote and demote on same player does not corrupt state`() = runTest {
        mgr.createGroup("TrackGroupA")
        mgr.createGroup("TrackGroupB")
        mgr.createGroup("TrackGroupC")
        mgr.createTrack("conc-track", listOf("TrackGroupA", "TrackGroupB", "TrackGroupC"))

        val uuid = UUID.randomUUID().toString()
        mgr.registerPlayer(uuid, "TrackPlayer")
        mgr.setPlayerGroup(uuid, "TrackPlayer", "TrackGroupA")

        val trackGroups = setOf("trackgroupa", "trackgroupb", "trackgroupc")

        // Interleave 10 promotes and 10 demotes sequentially (serialised by a Mutex
        // to guarantee no concurrent in-memory mutation of entry.groups).
        val dbMutex = Mutex()
        val results = mutableListOf<String?>()
        val steps = (1..10).map { "promote" } + (1..10).map { "demote" }
        for (step in steps.shuffled()) {
            dbMutex.withLock {
                val result = when (step) {
                    "promote" -> try { mgr.promote(uuid, "conc-track") } catch (_: IllegalArgumentException) { null }
                    else      -> try { mgr.demote(uuid, "conc-track") }  catch (_: IllegalArgumentException) { null }
                }
                results.add(result)
            }
        }

        // Player must still exist and belong to at least one valid track group
        val entry = mgr.getPlayer(uuid)
        assertNotNull(entry, "Player should still exist after interleaved promote/demote")
        val playerGroups = entry!!.groups.map { it.lowercase() }.toSet()
        val hasValidGroup = trackGroups.any { it in playerGroups }
        assertTrue(hasValidGroup, "Player should be in at least one valid track group; actual groups: ${entry.groups}")
    }
}
