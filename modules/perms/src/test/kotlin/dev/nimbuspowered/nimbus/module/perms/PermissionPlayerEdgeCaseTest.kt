package dev.nimbuspowered.nimbus.module.perms

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class PermissionPlayerEdgeCaseTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var mgr: PermissionManager

    @BeforeEach
    fun setup() = runTest {
        mgr = PermissionManager(buildPermsTestDb(tmp))
        mgr.init()
    }

    @Test
    fun `getPlayerByName is case-insensitive`() = runTest {
        val uuid = UUID.randomUUID().toString()
        mgr.registerPlayer(uuid, "Alice")

        val byLower = mgr.getPlayerByName("alice")
        assertNotNull(byLower, "getPlayerByName(\"alice\") should find player registered as \"Alice\"")
        assertEquals(uuid, byLower!!.first)

        val byUpper = mgr.getPlayerByName("ALICE")
        assertNotNull(byUpper, "getPlayerByName(\"ALICE\") should find player registered as \"Alice\"")
        assertEquals(uuid, byUpper!!.first)
    }

    @Test
    fun `player with no explicit group gets default group permissions`() = runTest {
        // Add a permission to the default group
        mgr.addPermission("Default", "default.perm")

        val uuid = UUID.randomUUID().toString()
        mgr.registerPlayer(uuid, "NoGroupPlayer")
        // Do NOT call setPlayerGroup — registerPlayer auto-assigns to default

        val effective = mgr.getEffectivePermissions(uuid)
        assertTrue("default.perm" in effective, "Player should inherit default group's permissions")
    }

    @Test
    fun `getPlayerDisplay returns result without crash when player has no non-default groups`() = runTest {
        val uuid = UUID.randomUUID().toString()
        mgr.registerPlayer(uuid, "PlainPlayer")
        // Player is in Default group only — no custom prefix/suffix set on Default by default

        // Should not throw even with minimal group setup
        val display = mgr.getPlayerDisplay(uuid)
        assertNotNull(display, "getPlayerDisplay should return a result, not null")
        // priority should be the Default group's priority (0 by default)
        assertTrue(display.priority >= 0, "Priority should be non-negative")
    }

    @Test
    fun `registerPlayer updates name on second call with same UUID`() = runTest {
        val uuid = UUID.randomUUID().toString()
        mgr.registerPlayer(uuid, "OldName")

        val firstEntry = mgr.getPlayer(uuid)
        assertNotNull(firstEntry)
        assertEquals("OldName", firstEntry!!.name)

        mgr.registerPlayer(uuid, "NewName")

        val updatedEntry = mgr.getPlayer(uuid)
        assertNotNull(updatedEntry)
        assertEquals("NewName", updatedEntry!!.name, "Player name should be updated on second registerPlayer call")
    }

    @Test
    fun `getAllPlayers returns empty map when no players registered`() = runTest {
        // Fresh manager — no players registered yet (init() only creates groups, not players)
        val allPlayers = mgr.getAllPlayers()
        assertTrue(allPlayers.isEmpty(), "getAllPlayers() should return an empty map on a fresh manager")
    }

    @Test
    fun `getPlayerByName returns null for unknown name`() = runTest {
        val result = mgr.getPlayerByName("NonExistentPlayer")
        assertNull(result, "getPlayerByName should return null for an unknown player name")
    }
}
