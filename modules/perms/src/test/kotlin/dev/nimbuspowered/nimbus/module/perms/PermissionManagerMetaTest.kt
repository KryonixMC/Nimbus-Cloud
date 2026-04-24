package dev.nimbuspowered.nimbus.module.perms

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class PermissionManagerMetaTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var mgr: PermissionManager

    @BeforeEach
    fun setup() = runTest {
        mgr = PermissionManager(buildPermsTestDb(tmp))
        mgr.init()
    }

    // ── Group Meta ──────────────────────────────────────────────

    @Test
    fun `setGroupMeta creates and retrieves a key`() = runTest {
        mgr.setGroupMeta("Admin", "prefix", "[A]")
        val meta = mgr.getGroupMeta("Admin")
        assertEquals("[A]", meta["prefix"])
    }

    @Test
    fun `setGroupMeta overwrites existing key`() = runTest {
        mgr.setGroupMeta("Admin", "prefix", "[A]")
        mgr.setGroupMeta("Admin", "prefix", "[Admin]")
        val meta = mgr.getGroupMeta("Admin")
        assertEquals("[Admin]", meta["prefix"])
        assertEquals(1, meta.entries.count { it.key == "prefix" })
    }

    @Test
    fun `removeGroupMeta deletes a key`() = runTest {
        mgr.setGroupMeta("Admin", "prefix", "[A]")
        mgr.removeGroupMeta("Admin", "prefix")
        val meta = mgr.getGroupMeta("Admin")
        assertFalse("prefix" in meta)
    }

    @Test
    fun `removeGroupMeta on non-existent key does not throw`() = runTest {
        // Should complete without exception
        mgr.removeGroupMeta("Admin", "nonexistent_key_xyz")
        assertTrue(mgr.getGroupMeta("Admin").isEmpty() || !mgr.getGroupMeta("Admin").containsKey("nonexistent_key_xyz"))
    }

    @Test
    fun `group meta survives reload`() = runTest {
        mgr.setGroupMeta("Admin", "prefix", "[A]")
        mgr.setGroupMeta("Admin", "suffix", " &f")
        mgr.reload()
        val meta = mgr.getGroupMeta("Admin")
        assertEquals("[A]", meta["prefix"])
        assertEquals(" &f", meta["suffix"])
    }

    // ── Player Meta ─────────────────────────────────────────────

    private val testUuid = UUID.randomUUID().toString()

    @Test
    fun `setPlayerMeta creates and retrieves a key`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        mgr.setPlayerMeta(testUuid, "nickname", "Test")
        val meta = mgr.getPlayerMeta(testUuid)
        assertEquals("Test", meta["nickname"])
    }

    @Test
    fun `setPlayerMeta overwrites existing key`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        mgr.setPlayerMeta(testUuid, "nickname", "Old")
        mgr.setPlayerMeta(testUuid, "nickname", "New")
        val meta = mgr.getPlayerMeta(testUuid)
        assertEquals("New", meta["nickname"])
    }

    @Test
    fun `removePlayerMeta deletes a key`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        mgr.setPlayerMeta(testUuid, "nickname", "Test")
        mgr.removePlayerMeta(testUuid, "nickname")
        val meta = mgr.getPlayerMeta(testUuid)
        assertFalse("nickname" in meta)
    }

    @Test
    fun `removePlayerMeta on non-existent key does not throw`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        // Should complete without exception
        mgr.removePlayerMeta(testUuid, "nonexistent_key_xyz")
        assertFalse(mgr.getPlayerMeta(testUuid).containsKey("nonexistent_key_xyz"))
    }

    @Test
    fun `player meta survives reload`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        mgr.setPlayerMeta(testUuid, "nickname", "Tester")
        mgr.setPlayerMeta(testUuid, "rank_color", "&6")
        mgr.reload()
        val meta = mgr.getPlayerMeta(testUuid)
        assertEquals("Tester", meta["nickname"])
        assertEquals("&6", meta["rank_color"])
    }
}
