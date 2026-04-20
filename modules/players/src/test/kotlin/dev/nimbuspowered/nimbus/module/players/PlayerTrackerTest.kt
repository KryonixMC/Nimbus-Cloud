package dev.nimbuspowered.nimbus.module.players

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class PlayerTrackerTest {

    private fun uuid() = UUID.randomUUID().toString()

    @Test
    fun `onPlayerConnect tracks player and opens session`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)

        val id = uuid()
        tracker.onPlayerConnect(id, "Alice", "Lobby-1", "Lobby")

        assertEquals(1, tracker.getOnlineCount())
        val online = tracker.getPlayer(id)
        assertNotNull(online)
        assertEquals("Alice", online!!.name)
        assertEquals("Lobby-1", online.currentService)
        assertEquals("Lobby", online.currentGroup)

        val history = tracker.getSessionHistory(id)
        assertEquals(1, history.size)
        assertEquals("Lobby-1", history[0]["service"])
        assertNull(history[0]["disconnectedAt"])

        val meta = tracker.getPlayerMeta(id)
        assertEquals("Alice", meta!!["name"])
    }

    @Test
    fun `onPlayerDisconnect closes session and removes from online`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)
        val id = uuid()
        tracker.onPlayerConnect(id, "Bob", "Lobby-1", "Lobby")
        tracker.onPlayerDisconnect(id, "Bob", "Lobby-1")

        assertEquals(0, tracker.getOnlineCount())
        val history = tracker.getSessionHistory(id)
        assertEquals(1, history.size)
        assertNotNull(history[0]["disconnectedAt"])
    }

    @Test
    fun `onPlayerServerSwitch closes old and opens new session`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)
        val id = uuid()
        tracker.onPlayerConnect(id, "Carol", "Lobby-1", "Lobby")
        tracker.onPlayerServerSwitch(id, "Carol", "Lobby-1", "BedWars-2", "BedWars")

        val online = tracker.getPlayer(id)!!
        assertEquals("BedWars-2", online.currentService)
        assertEquals("BedWars", online.currentGroup)

        val history = tracker.getSessionHistory(id)
        assertEquals(2, history.size)
        // Newest first (DESC)
        assertEquals("BedWars-2", history[0]["service"])
        assertNull(history[0]["disconnectedAt"])
        assertEquals("Lobby-1", history[1]["service"])
        assertNotNull(history[1]["disconnectedAt"])
    }

    @Test
    fun `resolveUuid returns online UUID case insensitive`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)
        val id = uuid()
        tracker.onPlayerConnect(id, "Dave", "Lobby-1", "Lobby")

        assertEquals(id, tracker.resolveUuid("dave"))
        assertEquals(id, tracker.resolveUuid("DAVE"))
        assertNull(tracker.resolveUuid("nobody"))
    }

    @Test
    fun `resolveUuid falls back to DB after disconnect`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)
        val id = uuid()
        tracker.onPlayerConnect(id, "Eve", "Lobby-1", "Lobby")
        tracker.onPlayerDisconnect(id, "Eve", "Lobby-1")

        assertEquals(id, tracker.resolveUuid("Eve"))
    }

    @Test
    fun `getStats reports online and per-service counts`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)
        tracker.onPlayerConnect(uuid(), "A", "Lobby-1", "Lobby")
        tracker.onPlayerConnect(uuid(), "B", "Lobby-1", "Lobby")
        tracker.onPlayerConnect(uuid(), "C", "BedWars-1", "BedWars")

        val stats = tracker.getStats()
        assertEquals(3, stats["online"])
        @Suppress("UNCHECKED_CAST")
        val per = stats["perService"] as Map<String, Int>
        assertEquals(2, per["Lobby-1"])
        assertEquals(1, per["BedWars-1"])
    }

    @Test
    fun `getPlayersOnService filters by current service`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)
        tracker.onPlayerConnect(uuid(), "A", "Lobby-1", "Lobby")
        tracker.onPlayerConnect(uuid(), "B", "BedWars-1", "BedWars")
        assertEquals(1, tracker.getPlayersOnService("Lobby-1").size)
        assertEquals(1, tracker.getPlayersOnService("BedWars-1").size)
        assertEquals(0, tracker.getPlayersOnService("X").size)
    }

    @Test
    fun `onPlayerConnect upserts existing player meta`(@TempDir dir: Path) = runTest {
        val db = buildTestDb(dir, PlayerSessions, PlayerMeta)
        val tracker = PlayerTracker(db)
        val id = uuid()
        tracker.onPlayerConnect(id, "OldName", "Lobby-1", "Lobby")
        tracker.onPlayerDisconnect(id, "OldName", "Lobby-1")
        tracker.onPlayerConnect(id, "NewName", "Lobby-1", "Lobby")

        val meta = tracker.getPlayerMeta(id)!!
        assertEquals("NewName", meta["name"])
    }
}
