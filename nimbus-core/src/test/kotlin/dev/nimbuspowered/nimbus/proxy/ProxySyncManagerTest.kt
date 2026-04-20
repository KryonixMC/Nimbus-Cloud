package dev.nimbuspowered.nimbus.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class ProxySyncManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val managers = mutableListOf<ProxySyncManager>()

    private fun mgr(): ProxySyncManager {
        val m = ProxySyncManager(tempDir)
        managers.add(m)
        return m
    }

    @AfterEach
    fun cancelPendingDebounceJobs() {
        // ProxySyncManager uses a private debounceScope that outlives the test.
        // Cancel it via reflection so queued writes don't fire against the deleted TempDir.
        for (m in managers) {
            val field = ProxySyncManager::class.java.getDeclaredField("debounceScope")
            field.isAccessible = true
            (field.get(m) as CoroutineScope).cancel()
        }
        managers.clear()
    }

    @Test
    fun `init creates default config files`() {
        val m = mgr()
        m.init()
        assertTrue(tempDir.resolve("motd.toml").exists())
        assertTrue(tempDir.resolve("tablist.toml").exists())
        assertTrue(tempDir.resolve("chat.toml").exists())
    }

    @Test
    fun `updateTabList persists changes to tablist toml`() {
        val m = mgr()
        m.init()
        m.updateTabList(header = "MY HEADER", footer = "MY FOOTER", updateInterval = 10)
        val text = tempDir.resolve("tablist.toml").readText()
        assertTrue(text.contains("MY HEADER"))
        assertTrue(text.contains("MY FOOTER"))
        assertTrue(text.contains("update_interval = 10"))
        assertEquals("MY HEADER", m.getConfig().tabList.header)
        assertEquals(10, m.getConfig().tabList.updateInterval)
    }

    @Test
    fun `updateChat persists format and enabled`() {
        val m = mgr()
        m.init()
        m.updateChat(format = "{player}: {message}", enabled = false)
        val cfg = m.getConfig().chat
        assertEquals("{player}: {message}", cfg.format)
        assertFalse(cfg.enabled)
    }

    @Test
    fun `setGlobalMaintenance returns true only on state change`() {
        val m = mgr()
        m.init()
        assertFalse(m.globalMaintenanceEnabled)
        assertTrue(m.setGlobalMaintenance(true))
        assertFalse(m.setGlobalMaintenance(true)) // idempotent
        assertTrue(m.globalMaintenanceEnabled)
        assertTrue(m.setGlobalMaintenance(false))
    }

    @Test
    fun `maintenance whitelist is case insensitive`() {
        val m = mgr()
        m.init()
        assertTrue(m.addToMaintenanceWhitelist("PlayerOne"))
        assertFalse(m.addToMaintenanceWhitelist("playerone")) // dup after lowercasing
        assertTrue(m.isMaintenanceWhitelisted("PLAYERONE"))
        assertTrue(m.removeFromMaintenanceWhitelist("PLAYERONE"))
        assertFalse(m.isMaintenanceWhitelisted("playerone"))
    }

    @Test
    fun `group maintenance add remove`() {
        val m = mgr()
        m.init()
        assertTrue(m.setGroupMaintenance("Lobby", true))
        assertFalse(m.setGroupMaintenance("Lobby", true))
        assertTrue(m.isGroupInMaintenance("Lobby"))
        assertEquals(listOf("Lobby"), m.getMaintenanceGroups())
        assertTrue(m.setGroupMaintenance("Lobby", false))
        assertFalse(m.isGroupInMaintenance("Lobby"))
        assertTrue(m.getMaintenanceGroups().isEmpty())
    }

    @Test
    fun `player tab overrides ephemeral get and clear`() {
        val m = mgr()
        m.init()
        m.setPlayerTabFormat("uuid-1", "<red>{player}")
        assertEquals("<red>{player}", m.getPlayerTabFormat("uuid-1"))
        assertEquals(1, m.getAllPlayerTabOverrides().size)
        m.clearPlayerTabFormat("uuid-1")
        assertNull(m.getPlayerTabFormat("uuid-1"))
    }

    @Test
    fun `reload parses previously written motd`() = runBlocking {
        val m = mgr()
        m.init()
        m.updateMotd(line1 = "NEW L1", line2 = "NEW L2", maxPlayers = 500, playerCountOffset = 5)
        waitUntil(3000) {
            tempDir.resolve("motd.toml").readText().contains("NEW L1")
        }
        val m2 = mgr()
        m2.init()
        val motd = m2.getConfig().motd
        assertEquals("NEW L1", motd.line1)
        assertEquals("NEW L2", motd.line2)
        assertEquals(500, motd.maxPlayers)
        assertEquals(5, motd.playerCountOffset)
    }

    @Test
    fun `maintenance whitelist and group state survive in-memory updates`() {
        val m = mgr()
        m.init()
        m.setGlobalMaintenance(true)
        m.addToMaintenanceWhitelist("admin")
        m.setGroupMaintenance("BedWars", true)
        m.updateGroupMaintenanceConfig("BedWars", "<red>Closed</red>")
        assertTrue(m.globalMaintenanceEnabled)
        assertTrue(m.isMaintenanceWhitelisted("admin"))
        assertEquals(setOf("admin"), m.getMaintenanceWhitelist())
        assertTrue(m.isGroupInMaintenance("BedWars"))
        assertEquals("<red>Closed</red>", m.getGroupMaintenanceState("BedWars")?.kickMessage)
        assertEquals(listOf("BedWars"), m.getMaintenanceGroups())
        assertEquals(1, m.getAllGroupMaintenanceStates().size)
    }

    @Test
    fun `updateGlobalMaintenanceConfig mutates volatile fields`() {
        val m = mgr()
        m.init()
        m.updateGlobalMaintenanceConfig(
            motdLine1 = "L1x",
            motdLine2 = "L2x",
            protocolText = "PTx",
            kickMessage = "KICKx"
        )
        assertEquals("L1x", m.globalMotdLine1)
        assertEquals("L2x", m.globalMotdLine2)
        assertEquals("PTx", m.globalProtocolText)
        assertEquals("KICKx", m.globalKickMessage)
    }

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { cond() }.getOrDefault(false)) return
            delay(50)
        }
    }
}
