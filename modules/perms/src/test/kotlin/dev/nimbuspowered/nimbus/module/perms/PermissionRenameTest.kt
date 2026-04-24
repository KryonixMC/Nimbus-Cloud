package dev.nimbuspowered.nimbus.module.perms

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PermissionRenameTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var mgr: PermissionManager

    @BeforeEach
    fun setup() = runTest {
        mgr = PermissionManager(buildPermsTestDb(tmp))
        mgr.init()
    }

    @Test
    fun `rename replaces old node in group`() = runTest {
        mgr.createGroup("Mod")
        mgr.addPermission("Mod", "nimbus.players")

        val report = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")

        assertEquals(1, report.totalReplacements)
        assertEquals(listOf("Mod"), report.groupsUpdated)
        assertFalse(report.noop)
        val perms = mgr.getGroup("Mod")!!.permissions
        assertTrue("nimbus.cloud.players" in perms)
        assertFalse("nimbus.players" in perms)
    }

    @Test
    fun `rename dedupes when both old and new present`() = runTest {
        mgr.createGroup("Mod")
        mgr.addPermission("Mod", "nimbus.players")
        mgr.addPermission("Mod", "nimbus.cloud.players")

        val report = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")

        assertEquals(1, report.totalReplacements)
        assertEquals(listOf("Mod"), report.groupsUpdated)
        val perms = mgr.getGroup("Mod")!!.permissions
        assertEquals(1, perms.count { it == "nimbus.cloud.players" })
        assertFalse("nimbus.players" in perms)
    }

    @Test
    fun `rename rewrites negated form`() = runTest {
        mgr.createGroup("Mod")
        mgr.addPermission("Mod", "-nimbus.players")

        val report = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")

        assertEquals(1, report.totalReplacements)
        val perms = mgr.getGroup("Mod")!!.permissions
        assertTrue("-nimbus.cloud.players" in perms)
        assertFalse("-nimbus.players" in perms)
    }

    @Test
    fun `rename preserves contextual server scope`() = runTest {
        mgr.createGroup("Mod")
        mgr.addPermission("Mod", "nimbus.players", PermissionContext(server = "lobby"))

        val report = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")

        assertEquals(1, report.totalReplacements)
        val group = mgr.getGroup("Mod")!!
        assertFalse("nimbus.players" in group.contextualPermissions)
        val ctxs = group.contextualPermissions["nimbus.cloud.players"]!!
        assertEquals(1, ctxs.size)
        assertEquals("lobby", ctxs[0].server)
    }

    @Test
    fun `rename leaves wildcard-only group untouched`() = runTest {
        // Admin seeded with "*" only
        val report = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")

        assertTrue(report.noop)
        assertEquals(0, report.totalReplacements)
        assertTrue("*" in mgr.getGroup("Admin")!!.permissions)
    }

    @Test
    fun `second invocation is a noop`() = runTest {
        mgr.createGroup("Mod")
        mgr.addPermission("Mod", "nimbus.players")

        val first = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")
        assertFalse(first.noop)

        val second = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")
        assertTrue(second.noop)
        assertEquals(0, second.totalReplacements)
    }

    @Test
    fun `no group has old node returns noop`() = runTest {
        mgr.createGroup("Mod")
        mgr.addPermission("Mod", "nimbus.cloud.other")

        val report = mgr.renamePermissionInAllGroups("nimbus.players", "nimbus.cloud.players")

        assertTrue(report.noop)
        assertEquals(0, report.totalReplacements)
    }
}
