package dev.kryonix.nimbus.permissions

import dev.kryonix.nimbus.config.DatabaseConfig
import dev.kryonix.nimbus.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PermissionManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: DatabaseManager
    private lateinit var manager: PermissionManager

    @BeforeEach
    fun setUp() = runBlocking {
        db = DatabaseManager(tempDir, DatabaseConfig())
        db.init()
        manager = PermissionManager(db)
    }

    // ── init / ensureDefaultGroup ───────────────────────────────

    @Test
    fun `init creates Default group if none exists`() = runBlocking {
        manager.init()

        val defaultGroup = manager.getDefaultGroup()
        assertNotNull(defaultGroup)
        assertEquals("Default", defaultGroup!!.name)
        assertTrue(defaultGroup.default)
    }

    @Test
    fun `init creates Admin group with wildcard permission`() = runBlocking {
        manager.init()

        val adminGroup = manager.getGroup("Admin")
        assertNotNull(adminGroup)
        assertEquals("Admin", adminGroup!!.name)
        assertTrue("*" in adminGroup.permissions)
        assertEquals(100, adminGroup.priority)
    }

    @Test
    fun `init does not create default groups if groups already exist`() = runBlocking {
        // Pre-create a group directly via manager
        manager.createGroup("Custom")
        // Now init — should not add Default or Admin
        manager.init()

        assertEquals(1, manager.getAllGroups().size)
        assertEquals("Custom", manager.getAllGroups().first().name)
    }

    // ── createGroup ────────────────────────────────────────────

    @Test
    fun `createGroup adds new group`() = runBlocking {
        manager.init()

        val group = manager.createGroup("VIP")
        assertEquals("VIP", group.name)
        assertFalse(group.default)
        assertNotNull(manager.getGroup("VIP"))
    }

    @Test
    fun `createGroup with duplicate name throws`() = runBlocking {
        manager.init()
        manager.createGroup("Moderator")

        assertThrows<IllegalArgumentException> {
            runBlocking { manager.createGroup("Moderator") }
        }
    }

    @Test
    fun `createGroup duplicate is case-insensitive`() = runBlocking {
        manager.init()
        // Admin already created by init — trying lowercase should throw
        assertThrows<IllegalArgumentException> {
            runBlocking { manager.createGroup("admin") }
        }
    }

    // ── deleteGroup ────────────────────────────────────────────

    @Test
    fun `deleteGroup removes it`() = runBlocking {
        manager.init()
        manager.createGroup("TempGroup")
        assertNotNull(manager.getGroup("TempGroup"))

        manager.deleteGroup("TempGroup")
        assertNull(manager.getGroup("TempGroup"))
    }

    @Test
    fun `deleteGroup throws for unknown group`() = runBlocking {
        manager.init()
        assertThrows<IllegalArgumentException> {
            runBlocking { manager.deleteGroup("NonExistent") }
        }
    }

    @Test
    fun `deleteGroup removes group from players`() = runBlocking {
        manager.init()
        manager.createGroup("VIP")
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.setPlayerGroup(uuid, "Steve", "VIP")

        manager.deleteGroup("VIP")

        val player = manager.getPlayer(uuid)
        assertNotNull(player)
        assertFalse(player!!.groups.any { it.equals("VIP", ignoreCase = true) })
    }

    // ── getGroup ───────────────────────────────────────────────

    @Test
    fun `getGroup returns correct group`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")

        val group = manager.getGroup("Operator")
        assertNotNull(group)
        assertEquals("Operator", group!!.name)
    }

    @Test
    fun `getGroup is case-insensitive`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")

        assertNotNull(manager.getGroup("operator"))
        assertNotNull(manager.getGroup("OPERATOR"))
    }

    @Test
    fun `getGroup returns null for unknown`() = runBlocking {
        manager.init()
        assertNull(manager.getGroup("NonExistent"))
    }

    // ── getAllGroups ────────────────────────────────────────────

    @Test
    fun `getAllGroups returns all groups`() = runBlocking {
        manager.init()
        manager.createGroup("VIP")

        val groups = manager.getAllGroups()
        // Default + Admin (auto) + VIP
        assertEquals(3, groups.size)
        assertTrue(groups.any { it.name == "Default" })
        assertTrue(groups.any { it.name == "Admin" })
        assertTrue(groups.any { it.name == "VIP" })
    }

    // ── addPermission / removePermission ───────────────────────

    @Test
    fun `addPermission modifies group permissions`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")

        manager.addPermission("Operator", "nimbus.cloud.list")
        manager.addPermission("Operator", "nimbus.cloud.start")

        val group = manager.getGroup("Operator")!!
        assertTrue("nimbus.cloud.list" in group.permissions)
        assertTrue("nimbus.cloud.start" in group.permissions)
    }

    @Test
    fun `addPermission does not duplicate`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")

        manager.addPermission("Operator", "nimbus.cloud.list")
        manager.addPermission("Operator", "nimbus.cloud.list")

        val group = manager.getGroup("Operator")!!
        assertEquals(1, group.permissions.count { it == "nimbus.cloud.list" })
    }

    @Test
    fun `removePermission removes from group`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")
        manager.addPermission("Operator", "nimbus.cloud.list")

        manager.removePermission("Operator", "nimbus.cloud.list")

        val group = manager.getGroup("Operator")!!
        assertFalse("nimbus.cloud.list" in group.permissions)
    }

    @Test
    fun `addPermission throws for unknown group`() = runBlocking {
        manager.init()
        assertThrows<IllegalArgumentException> {
            runBlocking { manager.addPermission("NoGroup", "some.perm") }
        }
    }

    // ── setDefault ─────────────────────────────────────────────

    @Test
    fun `setDefault marks group as default and clears previous`() = runBlocking {
        manager.init()
        manager.createGroup("NewDefault")

        manager.setDefault("NewDefault", true)

        val newDefault = manager.getGroup("NewDefault")!!
        assertTrue(newDefault.default)

        val oldDefault = manager.getGroup("Default")!!
        assertFalse(oldDefault.default)
    }

    @Test
    fun `getDefaultGroup returns the default group`() = runBlocking {
        manager.init()

        val defaultGroup = manager.getDefaultGroup()
        assertNotNull(defaultGroup)
        assertTrue(defaultGroup!!.default)
    }

    // ── addParent / removeParent ───────────────────────────────

    @Test
    fun `addParent manages inheritance chain`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")
        manager.createGroup("Moderator")

        manager.addParent("Operator", "Moderator")

        val operator = manager.getGroup("Operator")!!
        assertTrue(operator.parents.any { it.equals("Moderator", ignoreCase = true) })
    }

    @Test
    fun `addParent does not duplicate`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")
        manager.createGroup("Moderator")

        manager.addParent("Operator", "Moderator")
        manager.addParent("Operator", "Moderator")

        val operator = manager.getGroup("Operator")!!
        assertEquals(1, operator.parents.count { it.equals("Moderator", ignoreCase = true) })
    }

    @Test
    fun `addParent throws for unknown parent`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")

        assertThrows<IllegalArgumentException> {
            runBlocking { manager.addParent("Operator", "NonExistent") }
        }
    }

    @Test
    fun `removeParent removes from inheritance`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")
        manager.createGroup("Moderator")
        manager.addParent("Operator", "Moderator")

        manager.removeParent("Operator", "Moderator")

        val operator = manager.getGroup("Operator")!!
        assertFalse(operator.parents.any { it.equals("Moderator", ignoreCase = true) })
    }

    // ── Permission Resolution ──────────────────────────────────

    @Test
    fun `getEffectivePermissions with no parents returns own permissions`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")
        manager.addPermission("Operator", "nimbus.operator")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Operator")

        val perms = manager.getEffectivePermissions(uuid)
        assertTrue("nimbus.operator" in perms)
    }

    @Test
    fun `getEffectivePermissions with parent merges parent permissions`() = runBlocking {
        manager.init()
        manager.createGroup("Moderator")
        manager.addPermission("Moderator", "nimbus.moderate")

        manager.createGroup("Operator")
        manager.addPermission("Operator", "nimbus.operator")
        manager.addParent("Operator", "Moderator")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Operator")

        val perms = manager.getEffectivePermissions(uuid)
        assertTrue("nimbus.operator" in perms)
        assertTrue("nimbus.moderate" in perms)
    }

    @Test
    fun `getEffectivePermissions with grandparent merges all`() = runBlocking {
        manager.init()

        manager.createGroup("GroupC")
        manager.addPermission("GroupC", "perm.c")

        manager.createGroup("GroupB")
        manager.addPermission("GroupB", "perm.b")
        manager.addParent("GroupB", "GroupC")

        manager.createGroup("GroupA")
        manager.addPermission("GroupA", "perm.a")
        manager.addParent("GroupA", "GroupB")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "GroupA")

        val perms = manager.getEffectivePermissions(uuid)
        assertTrue("perm.a" in perms)
        assertTrue("perm.b" in perms)
        assertTrue("perm.c" in perms)
    }

    @Test
    fun `negated permission overrides positive from parent`() = runBlocking {
        manager.init()

        manager.createGroup("Parent")
        manager.addPermission("Parent", "some.perm")

        manager.createGroup("Child")
        manager.addPermission("Child", "-some.perm")
        manager.addParent("Child", "Parent")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Child")

        val perms = manager.getEffectivePermissions(uuid)
        assertFalse("some.perm" in perms)
    }

    @Test
    fun `getEffectivePermissions includes default group permissions`() = runBlocking {
        manager.init()
        manager.addPermission("Default", "basic.perm")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")

        val perms = manager.getEffectivePermissions(uuid)
        assertTrue("basic.perm" in perms)
    }

    // ── matchesPermission ──────────────────────────────────────

    @Test
    fun `matchesPermission exact match`() {
        val effective = setOf("nimbus.cloud.list")
        assertTrue(PermissionManager.matchesPermission(effective, "nimbus.cloud.list"))
    }

    @Test
    fun `matchesPermission wildcard matches child`() {
        val effective = setOf("nimbus.cloud.*")
        assertTrue(PermissionManager.matchesPermission(effective, "nimbus.cloud.list"))
        assertTrue(PermissionManager.matchesPermission(effective, "nimbus.cloud.start"))
    }

    @Test
    fun `matchesPermission global wildcard`() {
        val effective = setOf("*")
        assertTrue(PermissionManager.matchesPermission(effective, "anything.at.all"))
    }

    @Test
    fun `matchesPermission no match`() {
        val effective = setOf("nimbus.cloud.list")
        assertFalse(PermissionManager.matchesPermission(effective, "nimbus.cloud.start"))
    }

    @Test
    fun `matchesPermission wildcard does not match different prefix`() {
        val effective = setOf("nimbus.cloud.*")
        assertFalse(PermissionManager.matchesPermission(effective, "nimbus.admin.list"))
    }

    @Test
    fun `matchesPermission empty set returns false`() {
        val effective = emptySet<String>()
        assertFalse(PermissionManager.matchesPermission(effective, "some.perm"))
    }

    // ── hasPermission ──────────────────────────────────────────

    @Test
    fun `hasPermission resolves through inheritance`() = runBlocking {
        manager.init()

        manager.createGroup("Base")
        manager.addPermission("Base", "nimbus.cloud.*")

        manager.createGroup("Operator")
        manager.addParent("Operator", "Base")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Operator")

        assertTrue(manager.hasPermission(uuid, "nimbus.cloud.list"))
        assertTrue(manager.hasPermission(uuid, "nimbus.cloud.start"))
    }

    @Test
    fun `hasPermission returns false for missing permission`() = runBlocking {
        manager.init()
        manager.createGroup("Basic")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Basic")

        assertFalse(manager.hasPermission(uuid, "nimbus.admin.secret"))
    }

    // ── Player Management ──────────────────────────────────────

    @Test
    fun `registerPlayer creates entry`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"

        val created = manager.registerPlayer(uuid, "Steve")
        assertTrue(created)

        val player = manager.getPlayer(uuid)
        assertNotNull(player)
        assertEquals("Steve", player!!.name)
    }

    @Test
    fun `registerPlayer returns false if already up to date`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"

        manager.registerPlayer(uuid, "Steve")
        val updated = manager.registerPlayer(uuid, "Steve")
        assertFalse(updated)
    }

    @Test
    fun `registerPlayer updates name if changed`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"

        manager.registerPlayer(uuid, "Steve")
        val updated = manager.registerPlayer(uuid, "Steve_New")
        assertTrue(updated)
        assertEquals("Steve_New", manager.getPlayer(uuid)!!.name)
    }

    @Test
    fun `setPlayerGroup assigns group`() = runBlocking {
        manager.init()
        manager.createGroup("VIP")
        val uuid = "550e8400-e29b-41d4-a716-446655440000"

        manager.setPlayerGroup(uuid, "Steve", "VIP")

        val player = manager.getPlayer(uuid)!!
        assertTrue(player.groups.any { it.equals("VIP", ignoreCase = true) })
    }

    @Test
    fun `setPlayerGroup throws for unknown group`() = runBlocking {
        manager.init()
        assertThrows<IllegalArgumentException> {
            runBlocking { manager.setPlayerGroup("some-uuid", "Steve", "NonExistent") }
        }
    }

    @Test
    fun `removePlayerGroup removes assignment`() = runBlocking {
        manager.init()
        manager.createGroup("VIP")
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.setPlayerGroup(uuid, "Steve", "VIP")

        manager.removePlayerGroup(uuid, "VIP")

        val player = manager.getPlayer(uuid)!!
        assertFalse(player.groups.any { it.equals("VIP", ignoreCase = true) })
    }

    @Test
    fun `removePlayerGroup throws for unknown player`() = runBlocking {
        manager.init()
        assertThrows<IllegalArgumentException> {
            runBlocking { manager.removePlayerGroup("unknown-uuid", "Default") }
        }
    }

    @Test
    fun `getPlayerByName finds player`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")

        val result = manager.getPlayerByName("Steve")
        assertNotNull(result)
        assertEquals(uuid, result!!.first)
        assertEquals("Steve", result.second.name)
    }

    @Test
    fun `getPlayerByName is case-insensitive`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")

        assertNotNull(manager.getPlayerByName("steve"))
        assertNotNull(manager.getPlayerByName("STEVE"))
    }

    @Test
    fun `getPlayerByName returns null for unknown`() = runBlocking {
        manager.init()
        assertNull(manager.getPlayerByName("Nobody"))
    }

    // ── getPlayerDisplay ───────────────────────────────────────

    @Test
    fun `getPlayerDisplay returns highest priority group display`() = runBlocking {
        manager.init()

        manager.createGroup("VIP")
        manager.updateGroupDisplay("VIP", prefix = "[VIP] ", suffix = null, priority = 10)

        manager.createGroup("Operator")
        manager.updateGroupDisplay("Operator", prefix = "[Op] ", suffix = " (op)", priority = 50)

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.setPlayerGroup(uuid, "Steve", "VIP")
        manager.setPlayerGroup(uuid, "Steve", "Operator")

        val display = manager.getPlayerDisplay(uuid)
        assertEquals("[Op] ", display.prefix)
        assertEquals(" (op)", display.suffix)
        assertEquals("Operator", display.groupName)
        assertEquals(50, display.priority)
    }

    @Test
    fun `getPlayerDisplay falls back to default group`() = runBlocking {
        manager.init()
        manager.updateGroupDisplay("Default", prefix = "[Member] ", suffix = null, priority = 0)

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")

        val display = manager.getPlayerDisplay(uuid)
        assertEquals("[Member] ", display.prefix)
        assertEquals("Default", display.groupName)
    }

    // ── Persistence ────────────────────────────────────────────

    @Test
    fun `changes persist after save and reload cycle`() = runBlocking {
        manager.init()
        manager.createGroup("Moderator")
        manager.addPermission("Moderator", "nimbus.moderate")
        manager.addPermission("Moderator", "nimbus.chat.mute")

        manager.createGroup("Operator")
        manager.addPermission("Operator", "nimbus.operator")
        manager.addParent("Operator", "Moderator")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.setPlayerGroup(uuid, "Steve", "Operator")

        // Reload from database
        manager.reload()

        val operator = manager.getGroup("Operator")!!
        assertTrue("nimbus.operator" in operator.permissions)
        assertTrue(operator.parents.any { it.equals("Moderator", ignoreCase = true) })

        val mod = manager.getGroup("Moderator")!!
        assertTrue("nimbus.moderate" in mod.permissions)
        assertTrue("nimbus.chat.mute" in mod.permissions)

        val player = manager.getPlayer(uuid)!!
        assertTrue(player.groups.any { it.equals("Operator", ignoreCase = true) })

        val perms = manager.getEffectivePermissions(uuid)
        assertTrue("nimbus.operator" in perms)
        assertTrue("nimbus.moderate" in perms)
    }

    @Test
    fun `reload reads from database with fresh manager`() = runBlocking {
        manager.init()
        manager.createGroup("Operator")
        manager.addPermission("Operator", "nimbus.operator")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Operator")

        // Create a fresh manager pointing to the same database
        val manager2 = PermissionManager(db)
        manager2.init()

        assertNotNull(manager2.getGroup("Operator"))
        assertTrue("nimbus.operator" in manager2.getGroup("Operator")!!.permissions)
        assertNotNull(manager2.getPlayer(uuid))
        assertTrue(manager2.getPlayer(uuid)!!.groups.any { it.equals("Operator", ignoreCase = true) })
    }

    // ── Weight ─────────────────────────────────────────────────────

    @Test
    fun `setGroupWeight updates weight`() = runBlocking {
        manager.init()
        manager.setGroupWeight("Admin", 50)

        val group = manager.getGroup("Admin")!!
        assertEquals(50, group.weight)
    }

    @Test
    fun `weight persists across reload`() = runBlocking {
        manager.init()
        manager.createGroup("VIP")
        manager.setGroupWeight("VIP", 25)

        manager.reload()
        assertEquals(25, manager.getGroup("VIP")!!.weight)
    }

    // ── Group Meta ─────────────────────────────────────────────────

    @Test
    fun `setGroupMeta and getGroupMeta`() = runBlocking {
        manager.init()
        manager.setGroupMeta("Admin", "color", "red")
        manager.setGroupMeta("Admin", "icon", "crown")

        val meta = manager.getGroupMeta("Admin")
        assertEquals("red", meta["color"])
        assertEquals("crown", meta["icon"])
        assertEquals(2, meta.size)
    }

    @Test
    fun `removeGroupMeta removes key`() = runBlocking {
        manager.init()
        manager.setGroupMeta("Admin", "color", "red")
        manager.setGroupMeta("Admin", "icon", "crown")
        manager.removeGroupMeta("Admin", "color")

        val meta = manager.getGroupMeta("Admin")
        assertNull(meta["color"])
        assertEquals("crown", meta["icon"])
    }

    @Test
    fun `group meta persists across reload`() = runBlocking {
        manager.init()
        manager.setGroupMeta("Admin", "color", "red")

        manager.reload()
        assertEquals("red", manager.getGroupMeta("Admin")["color"])
    }

    // ── Player Meta ────────────────────────────────────────────────

    @Test
    fun `setPlayerMeta and getPlayerMeta`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerMeta(uuid, "coins", "500")
        manager.setPlayerMeta(uuid, "rank", "gold")

        val meta = manager.getPlayerMeta(uuid)
        assertEquals("500", meta["coins"])
        assertEquals("gold", meta["rank"])
    }

    @Test
    fun `removePlayerMeta removes key`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerMeta(uuid, "coins", "500")
        manager.setPlayerMeta(uuid, "rank", "gold")
        manager.removePlayerMeta(uuid, "coins")

        val meta = manager.getPlayerMeta(uuid)
        assertNull(meta["coins"])
        assertEquals("gold", meta["rank"])
    }

    @Test
    fun `player meta persists across reload`() = runBlocking {
        manager.init()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerMeta(uuid, "coins", "500")

        manager.reload()
        assertEquals("500", manager.getPlayerMeta(uuid)["coins"])
    }

    // ── Tracks ─────────────────────────────────────────────────────

    @Test
    fun `createTrack and getTrack`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createGroup("MVP")

        val track = manager.createTrack("ranks", listOf("Member", "VIP", "MVP"))
        assertEquals("ranks", track.name)
        assertEquals(listOf("Member", "VIP", "MVP"), track.groups)

        assertNotNull(manager.getTrack("ranks"))
    }

    @Test
    fun `createTrack with less than 2 groups throws`() = runBlocking {
        manager.init()
        manager.createGroup("Member")

        assertThrows<IllegalArgumentException> {
            runBlocking { manager.createTrack("solo", listOf("Member")) }
        }
    }

    @Test
    fun `createTrack with nonexistent group throws`() = runBlocking {
        manager.init()
        manager.createGroup("Member")

        assertThrows<IllegalArgumentException> {
            runBlocking { manager.createTrack("broken", listOf("Member", "NonExistent")) }
        }
    }

    @Test
    fun `deleteTrack removes track`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createTrack("ranks", listOf("Member", "VIP"))

        manager.deleteTrack("ranks")
        assertNull(manager.getTrack("ranks"))
    }

    @Test
    fun `track persists across reload`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createTrack("ranks", listOf("Member", "VIP"))

        manager.reload()
        val track = manager.getTrack("ranks")
        assertNotNull(track)
        assertEquals(listOf("Member", "VIP"), track!!.groups)
    }

    // ── Promote / Demote ───────────────────────────────────────────

    @Test
    fun `promote moves player up on track`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createGroup("MVP")
        manager.createTrack("ranks", listOf("Member", "VIP", "MVP"))

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Member")

        val newGroup = manager.promote(uuid, "ranks")
        assertEquals("VIP", newGroup)

        val entry = manager.getPlayer(uuid)!!
        assertTrue(entry.groups.any { it.equals("VIP", ignoreCase = true) })
        assertFalse(entry.groups.any { it.equals("Member", ignoreCase = true) })
    }

    @Test
    fun `promote returns null at top of track`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createTrack("ranks", listOf("Member", "VIP"))

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "VIP")

        val result = manager.promote(uuid, "ranks")
        assertNull(result)
    }

    @Test
    fun `promote starts at bottom when player not on track`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createTrack("ranks", listOf("Member", "VIP"))

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")

        val newGroup = manager.promote(uuid, "ranks")
        assertEquals("Member", newGroup)
    }

    @Test
    fun `demote moves player down on track`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createGroup("MVP")
        manager.createTrack("ranks", listOf("Member", "VIP", "MVP"))

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "VIP")

        val newGroup = manager.demote(uuid, "ranks")
        assertEquals("Member", newGroup)

        val entry = manager.getPlayer(uuid)!!
        assertTrue(entry.groups.any { it.equals("Member", ignoreCase = true) })
        assertFalse(entry.groups.any { it.equals("VIP", ignoreCase = true) })
    }

    @Test
    fun `demote returns null at bottom of track`() = runBlocking {
        manager.init()
        manager.createGroup("Member")
        manager.createGroup("VIP")
        manager.createTrack("ranks", listOf("Member", "VIP"))

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Member")

        val result = manager.demote(uuid, "ranks")
        assertNull(result)
    }

    // ── Permission Debug ───────────────────────────────────────────

    @Test
    fun `checkPermission returns granted with reason`() = runBlocking {
        manager.init()

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Admin")

        val result = manager.checkPermission(uuid, "nimbus.cloud.list")
        assertTrue(result.result)
        assertTrue(result.reason.contains("Admin"))
        assertTrue(result.chain.isNotEmpty())
    }

    @Test
    fun `checkPermission returns denied for missing permission`() = runBlocking {
        manager.init()

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")

        val result = manager.checkPermission(uuid, "some.random.perm")
        assertFalse(result.result)
        assertTrue(result.reason.contains("Denied"))
    }

    @Test
    fun `checkPermission shows negation in chain`() = runBlocking {
        manager.init()
        manager.createGroup("Restricted")
        manager.addPermission("Restricted", "nimbus.chat")
        manager.addPermission("Restricted", "nimbus.admin")
        manager.addPermission("Restricted", "-nimbus.admin")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")
        manager.setPlayerGroup(uuid, "Steve", "Restricted")

        val result = manager.checkPermission(uuid, "nimbus.admin")
        // nimbus.admin is granted then negated, so net result is denied
        assertFalse(result.result)
        assertTrue(result.chain.any { !it.granted && it.type == "negated" })
    }

    // ── Audit Log ──────────────────────────────────────────────────

    @Test
    fun `logAudit and getAuditLog`() = runBlocking {
        manager.init()
        manager.logAudit("console", "group.create", "VIP", "Created group VIP")
        manager.logAudit("console", "user.addgroup", "uuid-123", "Added Admin to Steve")

        val log = manager.getAuditLog(10)
        assertEquals(2, log.size)
        // Most recent first
        assertEquals("user.addgroup", log[0].action)
        assertEquals("group.create", log[1].action)
    }

    @Test
    fun `getAuditLog respects limit`() = runBlocking {
        manager.init()
        repeat(5) { i ->
            manager.logAudit("console", "action.$i", "target", "details")
        }

        val log = manager.getAuditLog(3)
        assertEquals(3, log.size)
    }

    // ── Temp Permission Cleanup ────────────────────────────────────

    @Test
    fun `cleanupExpired removes expired contexts`() = runBlocking {
        manager.init()
        manager.createGroup("TempVIP")
        manager.addPermission("TempVIP", "vip.fly")

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        manager.registerPlayer(uuid, "Steve")

        // Add a group with expired context
        val expiredContext = PermissionContext(expiresAt = "2020-01-01T00:00:00Z")
        manager.setPlayerGroup(uuid, "Steve", "TempVIP", expiredContext)

        val cleaned = manager.cleanupExpired()
        assertTrue(cleaned > 0)
    }

    // ── Init creates Admin with weight ─────────────────────────────

    @Test
    fun `init creates Admin group with weight 100`() = runBlocking {
        manager.init()

        val admin = manager.getGroup("Admin")!!
        assertEquals(100, admin.weight)
    }
}
