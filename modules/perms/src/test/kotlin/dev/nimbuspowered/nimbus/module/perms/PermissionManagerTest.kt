package dev.nimbuspowered.nimbus.module.perms

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PermissionManagerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var mgr: PermissionManager

    @BeforeEach
    fun setup() = runTest {
        mgr = PermissionManager(buildPermsTestDb(tmp))
        mgr.init()
    }

    // ── Group CRUD ──────────────────────────────────────────────

    @Test
    fun `init creates Default and Admin groups`() {
        val groups = mgr.getAllGroups().map { it.name }
        assertTrue("Default" in groups)
        assertTrue("Admin" in groups)
    }

    @Test
    fun `createGroup rejects duplicates`() = runTest {
        mgr.createGroup("Mod")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { mgr.createGroup("Mod") }
        }
    }

    @Test
    fun `deleteGroup removes from memory and DB`() = runTest {
        mgr.createGroup("Temp")
        mgr.deleteGroup("Temp")
        assertTrue(mgr.getGroup("Temp") == null)
    }

    // ── Inheritance ─────────────────────────────────────────────

    @Test
    fun `permissions inherit from parent`() = runTest {
        mgr.createGroup("Base")
        mgr.addPermission("Base", "base.perm")
        mgr.createGroup("Child")
        mgr.addParent("Child", "Base")

        mgr.registerPlayer("uuid-a", "Alice")
        mgr.setPlayerGroup("uuid-a", "Alice", "Child")

        val effective = mgr.getEffectivePermissions("uuid-a")
        assertTrue("base.perm" in effective)
    }

    @Test
    fun `circular inheritance is rejected`() = runTest {
        mgr.createGroup("A")
        mgr.createGroup("B")
        mgr.addParent("A", "B")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { mgr.addParent("B", "A") }
        }
    }

    @Test
    fun `deep inheritance chain resolves`() = runTest {
        mgr.createGroup("L1"); mgr.addPermission("L1", "l1.perm")
        mgr.createGroup("L2"); mgr.addParent("L2", "L1")
        mgr.createGroup("L3"); mgr.addParent("L3", "L2")
        mgr.registerPlayer("uuid-b", "Bob")
        mgr.setPlayerGroup("uuid-b", "Bob", "L3")
        assertTrue("l1.perm" in mgr.getEffectivePermissions("uuid-b"))
    }

    // ── Wildcards ───────────────────────────────────────────────

    @Test
    fun `wildcard permission matches descendant nodes`() = runTest {
        mgr.createGroup("X"); mgr.addPermission("X", "nimbus.admin.*")
        mgr.registerPlayer("uuid-c", "Carol")
        mgr.setPlayerGroup("uuid-c", "Carol", "X")
        assertTrue(mgr.hasPermission("uuid-c", "nimbus.admin.kick"))
        assertTrue(mgr.hasPermission("uuid-c", "nimbus.admin.ban"))
        assertFalse(mgr.hasPermission("uuid-c", "nimbus.mod.kick"))
    }

    @Test
    fun `root wildcard grants everything`() = runTest {
        // Admin group is created at init with "*"
        mgr.registerPlayer("uuid-d", "Dan")
        mgr.setPlayerGroup("uuid-d", "Dan", "Admin")
        assertTrue(mgr.hasPermission("uuid-d", "anything.at.all"))
    }

    @Test
    fun `negated permission removes exact-match grant`() = runTest {
        // Negation removes the exact string from the effective set; it does
        // not mask a wildcard ancestor — matching is done against the granted
        // strings themselves after the set-subtract.
        mgr.createGroup("Y")
        mgr.addPermission("Y", "foo.bar")
        mgr.addPermission("Y", "-foo.bar")
        mgr.registerPlayer("uuid-e", "Eve")
        mgr.setPlayerGroup("uuid-e", "Eve", "Y")
        assertFalse(mgr.hasPermission("uuid-e", "foo.bar"))
    }

    @Test
    fun `matchesPermission static util handles segment wildcards`() {
        val eff = setOf("a.b.*")
        assertTrue(PermissionManager.matchesPermission(eff, "a.b.c"))
        assertTrue(PermissionManager.matchesPermission(eff, "a.b.c.d"))
        assertFalse(PermissionManager.matchesPermission(eff, "a.c.b"))
    }

    @Test
    fun `validatePermission rejects bad syntax`() {
        assertThrows(IllegalArgumentException::class.java) {
            PermissionManager.validatePermission("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionManager.validatePermission("has spaces")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionManager.validatePermission("has/slash")
        }
        PermissionManager.validatePermission("valid.node-with_chars.*")
        PermissionManager.validatePermission("-negated.node")
    }

    // ── Weight / Display ────────────────────────────────────────

    @Test
    fun `getPlayerDisplay picks highest priority group`() = runTest {
        mgr.createGroup("Low")
        mgr.updateGroupDisplay("Low", prefix = "&7[Low]", suffix = null, priority = 5)
        mgr.createGroup("High")
        mgr.updateGroupDisplay("High", prefix = "&c[HIGH]", suffix = null, priority = 99)
        mgr.registerPlayer("uuid-f", "Fran")
        mgr.setPlayerGroup("uuid-f", "Fran", "Low")
        mgr.setPlayerGroup("uuid-f", "Fran", "High")
        val display = mgr.getPlayerDisplay("uuid-f")
        assertEquals("&c[HIGH]", display.prefix)
        assertEquals(99, display.priority)
    }

    @Test
    fun `setGroupWeight persists in memory`() = runTest {
        mgr.createGroup("W")
        mgr.setGroupWeight("W", 42)
        assertEquals(42, mgr.getGroup("W")!!.weight)
    }

    // ── Tracks ──────────────────────────────────────────────────

    @Test
    fun `createTrack requires at least 2 groups`() = runTest {
        mgr.createGroup("G1")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { mgr.createTrack("solo", listOf("G1")) }
        }
    }

    @Test
    fun `promote moves player up the track`() = runTest {
        mgr.createGroup("Tier1")
        mgr.createGroup("Tier2")
        mgr.createGroup("Tier3")
        mgr.createTrack("progression", listOf("Tier1", "Tier2", "Tier3"))
        mgr.registerPlayer("uuid-g", "Gus")
        mgr.setPlayerGroup("uuid-g", "Gus", "Tier1")

        val newGroup = mgr.promote("uuid-g", "progression")
        assertEquals("Tier2", newGroup)
        val entry = mgr.getPlayer("uuid-g")!!
        assertTrue(entry.groups.any { it.equals("Tier2", ignoreCase = true) })
        assertFalse(entry.groups.any { it.equals("Tier1", ignoreCase = true) })
    }

    @Test
    fun `promote returns null at top of track`() = runTest {
        mgr.createGroup("T1"); mgr.createGroup("T2")
        mgr.createTrack("t", listOf("T1", "T2"))
        mgr.registerPlayer("uuid-h", "H")
        mgr.setPlayerGroup("uuid-h", "H", "T2")
        assertTrue(mgr.promote("uuid-h", "t") == null)
    }

    @Test
    fun `demote moves player down the track`() = runTest {
        mgr.createGroup("D1"); mgr.createGroup("D2"); mgr.createGroup("D3")
        mgr.createTrack("d", listOf("D1", "D2", "D3"))
        mgr.registerPlayer("uuid-i", "I")
        mgr.setPlayerGroup("uuid-i", "I", "D3")
        assertEquals("D2", mgr.demote("uuid-i", "d"))
    }

    @Test
    fun `demote returns null at bottom of track`() = runTest {
        mgr.createGroup("B1"); mgr.createGroup("B2")
        mgr.createTrack("bt", listOf("B1", "B2"))
        mgr.registerPlayer("uuid-j", "J")
        mgr.setPlayerGroup("uuid-j", "J", "B1")
        assertTrue(mgr.demote("uuid-j", "bt") == null)
    }

    // ── Audit Log ───────────────────────────────────────────────

    @Test
    fun `logAudit persists and readable via getAuditLog`() = runTest {
        mgr.logAudit("console", "GROUP_CREATE", "Mod", "created by test")
        val log = mgr.getAuditLog()
        assertTrue(log.any { it.action == "GROUP_CREATE" && it.target == "Mod" })
    }

    // ── Default Group Handling ──────────────────────────────────

    @Test
    fun `setDefault demotes previous default`() = runTest {
        mgr.createGroup("NewDefault", default = true)
        mgr.setDefault("NewDefault", true)
        assertFalse(mgr.getGroup("Default")!!.default)
        assertTrue(mgr.getGroup("NewDefault")!!.default)
    }

    @Test
    fun `new player auto-assigned to default group`() = runTest {
        mgr.registerPlayer("uuid-k", "Kim")
        val entry = mgr.getPlayer("uuid-k")!!
        assertTrue(entry.groups.any { it.equals("Default", ignoreCase = true) })
    }
}
