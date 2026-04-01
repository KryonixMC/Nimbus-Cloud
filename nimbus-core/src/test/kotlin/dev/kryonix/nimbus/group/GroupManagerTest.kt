package dev.kryonix.nimbus.group

import dev.kryonix.nimbus.config.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GroupManagerTest {

    private lateinit var manager: GroupManager

    @BeforeEach
    fun setUp() {
        manager = GroupManager()
    }

    private fun groupConfig(
        name: String,
        type: GroupType = GroupType.DYNAMIC,
        software: ServerSoftware = ServerSoftware.PAPER,
        version: String = "1.21.4",
        minInstances: Int = 1,
        maxInstances: Int = 4
    ): GroupConfig = GroupConfig(
        group = GroupDefinition(
            name = name,
            type = type,
            template = name.lowercase(),
            software = software,
            version = version,
            resources = ResourcesConfig(memory = "1G", maxPlayers = 50),
            scaling = ScalingConfig(minInstances = minInstances, maxInstances = maxInstances)
        )
    )

    @Test
    fun `loadGroups populates map correctly`() {
        val configs = listOf(
            groupConfig("Lobby"),
            groupConfig("BedWars"),
            groupConfig("SkyWars")
        )
        manager.loadGroups(configs)

        assertEquals(3, manager.getAllGroups().size)
        assertNotNull(manager.getGroup("Lobby"))
        assertNotNull(manager.getGroup("BedWars"))
        assertNotNull(manager.getGroup("SkyWars"))
    }

    @Test
    fun `getGroup returns correct ServerGroup`() {
        manager.loadGroups(listOf(groupConfig("Lobby"), groupConfig("BedWars")))

        val lobby = manager.getGroup("Lobby")
        assertNotNull(lobby)
        assertEquals("Lobby", lobby!!.name)
        assertEquals(GroupType.DYNAMIC, lobby.config.group.type)
    }

    @Test
    fun `getGroup returns null for unknown group`() {
        manager.loadGroups(listOf(groupConfig("Lobby")))
        assertNull(manager.getGroup("NonExistent"))
    }

    @Test
    fun `getAllGroups returns all loaded groups`() {
        val configs = listOf(groupConfig("Lobby"), groupConfig("BedWars"))
        manager.loadGroups(configs)

        val all = manager.getAllGroups()
        assertEquals(2, all.size)
        val names = all.map { it.name }.toSet()
        assertTrue(names.contains("Lobby"))
        assertTrue(names.contains("BedWars"))
    }

    @Test
    fun `updateGroupType changes type and returns true`() {
        manager.loadGroups(listOf(groupConfig("Lobby", type = GroupType.DYNAMIC)))

        val result = manager.updateGroupType("Lobby", GroupType.STATIC)
        assertTrue(result)

        val updated = manager.getGroup("Lobby")
        assertNotNull(updated)
        assertTrue(updated!!.isStatic)
        assertFalse(updated.isDynamic)
    }

    @Test
    fun `updateGroupType for unknown group returns false`() {
        manager.loadGroups(listOf(groupConfig("Lobby")))
        val result = manager.updateGroupType("NonExistent", GroupType.STATIC)
        assertFalse(result)
    }

    @Test
    fun `reloadGroups replaces existing groups and adds new ones`() {
        manager.loadGroups(listOf(groupConfig("Lobby"), groupConfig("BedWars")))

        val newConfigs = listOf(
            groupConfig("Lobby", minInstances = 2, maxInstances = 8),
            groupConfig("SkyWars")
        )
        manager.reloadGroups(newConfigs)

        // Lobby was updated
        val lobby = manager.getGroup("Lobby")
        assertNotNull(lobby)
        assertEquals(2, lobby!!.minInstances)
        assertEquals(8, lobby.maxInstances)

        // SkyWars was added
        assertNotNull(manager.getGroup("SkyWars"))

        // BedWars is still tracked (removed groups are kept until restart)
        assertNotNull(manager.getGroup("BedWars"))
    }

    @Test
    fun `loadGroups with empty config list results in empty groups`() {
        // Load some groups first, then clear with empty list
        manager.loadGroups(listOf(groupConfig("Lobby")))
        assertEquals(1, manager.getAllGroups().size)

        manager.loadGroups(emptyList())
        assertTrue(manager.getAllGroups().isEmpty())
    }

    @Test
    fun `loadGroups clears previous groups`() {
        manager.loadGroups(listOf(groupConfig("Lobby"), groupConfig("BedWars")))
        assertEquals(2, manager.getAllGroups().size)

        manager.loadGroups(listOf(groupConfig("SkyWars")))
        assertEquals(1, manager.getAllGroups().size)
        assertNull(manager.getGroup("Lobby"))
        assertNull(manager.getGroup("BedWars"))
        assertNotNull(manager.getGroup("SkyWars"))
    }
}
