package dev.nimbuspowered.nimbus.module.display

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class DisplayManagerTest {

    @Test
    fun `reload parses sign lines and state labels`(@TempDir dir: Path) {
        dir.resolve("Lobby.toml").writeText(
            """
            [display]
            name = "Lobby"

            [display.sign]
            line1 = "LINE1"
            line2 = "LINE2"
            line3 = "LINE3"
            line4_online = "ONLINE"
            line4_offline = "OFFLINE"

            [display.npc]
            display_name = "NAME"
            subtitle = "SUB"
            subtitle_offline = "SUBOFF"
            floating_item = "NETHER_STAR"

            [display.npc.status_items]
            ONLINE = "LIME_WOOL"
            OFFLINE = "GRAY_WOOL"

            [display.npc.inventory]
            title = "TITLE"
            size = 27
            item_name = "IN"
            item_lore = ["a", "b", "c"]

            [display.states]
            READY = "ONLINE"
            STOPPED = "OFFLINE"
            CUSTOM = "CUSTOM_LABEL"
            """.trimIndent() + "\n"
        )

        val mgr = DisplayManager(dir)
        mgr.init()
        mgr.reload()

        val cfg = mgr.getDisplay("Lobby")
        assertNotNull(cfg)
        val d = cfg!!.display
        assertEquals("Lobby", d.name)
        assertEquals("LINE1", d.sign.line1)
        assertEquals("OFFLINE", d.sign.line4Offline)
        assertEquals("NETHER_STAR", d.npc.floatingItem)
        assertEquals(listOf("a", "b", "c"), d.npc.inventory.itemLore)
        assertEquals("LIME_WOOL", d.npc.statusItems["ONLINE"])
        assertEquals("CUSTOM_LABEL", d.states["CUSTOM"])
    }

    @Test
    fun `resolveStateLabel falls back to raw state when unknown`(@TempDir dir: Path) {
        dir.resolve("G.toml").writeText(
            """
            [display]
            name = "G"

            [display.states]
            READY = "ONLINE"
            """.trimIndent() + "\n"
        )
        val mgr = DisplayManager(dir)
        mgr.init(); mgr.reload()

        assertEquals("ONLINE", mgr.resolveStateLabel("G", "READY"))
        assertEquals("UNMAPPED", mgr.resolveStateLabel("G", "UNMAPPED"))
        assertEquals("READY", mgr.resolveStateLabel("NoSuchGroup", "READY"))
    }

    @Test
    fun `deleteDisplay removes file and memory entry`(@TempDir dir: Path) {
        dir.resolve("X.toml").writeText(
            """
            [display]
            name = "X"
            """.trimIndent() + "\n"
        )
        val mgr = DisplayManager(dir)
        mgr.init(); mgr.reload()
        assertNotNull(mgr.getDisplay("X"))

        mgr.deleteDisplay("X")
        assertNull(mgr.getDisplay("X"))
        assertFalse(dir.resolve("X.toml").exists())
    }

    @Test
    fun `updateDisplay merges sign partial and persists`(@TempDir dir: Path) {
        dir.resolve("U.toml").writeText(
            """
            [display]
            name = "U"

            [display.sign]
            line1 = "OLD1"
            line2 = "OLD2"
            line3 = "OLD3"
            line4_online = "OLDON"
            line4_offline = "OLDOFF"

            [display.states]
            READY = "ONLINE"
            """.trimIndent() + "\n"
        )
        val mgr = DisplayManager(dir)
        mgr.init(); mgr.reload()

        val ok = mgr.updateDisplay(
            "U",
            DisplayUpdate(sign = SignUpdate(line1 = "NEW1"))
        )
        assertTrue(ok)

        val cfg = mgr.getDisplay("U")!!
        assertEquals("NEW1", cfg.display.sign.line1)
        assertEquals("OLD2", cfg.display.sign.line2)

        // Round-trip via reload
        mgr.reload()
        assertEquals("NEW1", mgr.getDisplay("U")!!.display.sign.line1)
    }

    @Test
    fun `updateDisplay returns false for unknown group`(@TempDir dir: Path) {
        val mgr = DisplayManager(dir)
        mgr.init()
        assertFalse(mgr.updateDisplay("Ghost", DisplayUpdate()))
    }

    @Test
    fun `unknown state falls back to raw when no config loaded`(@TempDir dir: Path) {
        val mgr = DisplayManager(dir)
        mgr.init()
        assertEquals("PREPARING", mgr.resolveStateLabel("anything", "PREPARING"))
    }
}
