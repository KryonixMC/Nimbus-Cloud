package dev.nimbuspowered.nimbus.module.display

import dev.nimbuspowered.nimbus.config.GroupConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Manages display configs for groups (signs + NPCs).
 * Auto-generates a config per group on first load.
 * Configs live in the `config/modules/display/` directory.
 */
class DisplayManager(private val displaysDir: Path) {

    private val logger = LoggerFactory.getLogger(DisplayManager::class.java)
    private val displays = mutableMapOf<String, DisplayConfig>()

    fun init() {
        if (!displaysDir.exists()) displaysDir.createDirectories()
    }

    /**
     * Ensure display configs exist for all groups.
     * Creates default configs for groups that don't have one yet.
     */
    fun ensureDisplays(groupConfigs: List<GroupConfig>) {
        for (config in groupConfigs) {
            val groupName = config.group.name
            // Skip proxy groups — they don't need display configs
            if (config.group.software == ServerSoftware.VELOCITY) continue

            val file = displaysDir.resolve("${groupName}.toml")
            if (!file.exists()) {
                generateDefault(file, config)
                logger.info("Generated display config for group '{}'", groupName)
            }
        }
        reload()
    }

    /** Reload all display configs from disk. */
    fun reload() {
        displays.clear()
        if (!displaysDir.exists()) return

        Files.list(displaysDir)
            .filter { it.toString().endsWith(".toml") }
            .forEach { file ->
                try {
                    val config = parseDisplayConfig(file)
                    displays[config.display.name] = config
                } catch (e: Exception) {
                    logger.warn("Failed to load display config: {}", file.fileName, e)
                }
            }

        logger.info("Loaded {} display config(s)", displays.size)
    }

    /** Get the display config for a group. */
    fun getDisplay(groupName: String): DisplayConfig? = displays[groupName]

    /** Get all display configs. */
    fun getAllDisplays(): Map<String, DisplayConfig> = displays.toMap()

    /**
     * Create a display config for a single group.
     * Skips VELOCITY groups. No-op if the config already exists.
     */
    fun createDisplay(groupConfig: GroupConfig) {
        val groupName = groupConfig.group.name
        if (groupConfig.group.software == ServerSoftware.VELOCITY) return

        val file = displaysDir.resolve("${groupName}.toml")
        if (!file.exists()) {
            generateDefault(file, groupConfig)
            logger.info("Generated display config for new group '{}'", groupName)
        }
        // Load into memory
        try {
            val config = parseDisplayConfig(file)
            displays[config.display.name] = config
        } catch (e: Exception) {
            logger.warn("Failed to load display config for '{}': {}", groupName, e.message)
        }
    }

    /**
     * Delete the display config for a group.
     * Removes the file from disk and the entry from memory.
     */
    fun deleteDisplay(groupName: String) {
        displays.remove(groupName)
        val file = displaysDir.resolve("${groupName}.toml")
        if (file.exists()) {
            try {
                Files.delete(file)
                logger.info("Deleted display config for removed group '{}'", groupName)
            } catch (e: Exception) {
                logger.warn("Failed to delete display config for '{}': {}", groupName, e.message)
            }
        }
    }

    /**
     * Reload a single display config from disk (e.g. after group update).
     */
    fun reloadDisplay(groupName: String) {
        val file = displaysDir.resolve("${groupName}.toml")
        if (!file.exists()) return
        try {
            val config = parseDisplayConfig(file)
            displays[config.display.name] = config
            logger.debug("Reloaded display config for '{}'", groupName)
        } catch (e: Exception) {
            logger.warn("Failed to reload display config for '{}': {}", groupName, e.message)
        }
    }

    /**
     * Update a display config with partial data.
     * Merges the provided fields into the existing config and writes to disk.
     */
    fun updateDisplay(groupName: String, update: DisplayUpdate): Boolean {
        val existing = displays[groupName] ?: return false
        val def = existing.display

        val newSign = if (update.sign != null) SignDisplay(
            line1 = update.sign.line1 ?: def.sign.line1,
            line2 = update.sign.line2 ?: def.sign.line2,
            line3 = update.sign.line3 ?: def.sign.line3,
            line4Online = update.sign.line4Online ?: def.sign.line4Online,
            line4Offline = update.sign.line4Offline ?: def.sign.line4Offline
        ) else def.sign

        val newNpc = if (update.npc != null) NpcDisplay(
            displayName = update.npc.displayName ?: def.npc.displayName,
            subtitle = update.npc.subtitle ?: def.npc.subtitle,
            subtitleOffline = update.npc.subtitleOffline ?: def.npc.subtitleOffline,
            floatingItem = update.npc.floatingItem ?: def.npc.floatingItem,
            statusItems = update.npc.statusItems ?: def.npc.statusItems,
            inventory = if (update.npc.inventory != null) NpcInventoryConfig(
                title = update.npc.inventory.title ?: def.npc.inventory.title,
                size = update.npc.inventory.size ?: def.npc.inventory.size,
                itemName = update.npc.inventory.itemName ?: def.npc.inventory.itemName,
                itemLore = update.npc.inventory.itemLore ?: def.npc.inventory.itemLore
            ) else def.npc.inventory
        ) else def.npc

        val newStates = update.states ?: def.states

        val newConfig = DisplayConfig(DisplayDefinition(groupName, newSign, newNpc, newStates))
        displays[groupName] = newConfig
        writeDisplayToml(groupName, newConfig)
        logger.info("Updated display config for '{}'", groupName)
        return true
    }

    /**
     * Reset a display config to defaults.
     * Deletes the existing file and regenerates from the group config.
     */
    fun resetDisplay(groupName: String, groupConfig: dev.nimbuspowered.nimbus.config.GroupConfig): Boolean {
        val file = displaysDir.resolve("${groupName}.toml")
        if (file.exists()) Files.delete(file)
        generateDefault(file, groupConfig)
        try {
            val config = parseDisplayConfig(file)
            displays[config.display.name] = config
        } catch (e: Exception) {
            logger.warn("Failed to reload display config after reset for '{}': {}", groupName, e.message)
            return false
        }
        logger.info("Reset display config for '{}' to defaults", groupName)
        return true
    }

    /**
     * Resolve a state label for display.
     * Falls back to the raw state if no label is configured.
     */
    fun resolveStateLabel(groupName: String, rawState: String): String {
        val config = displays[groupName] ?: return rawState
        return config.display.states[rawState] ?: rawState
    }

    // ── Config Generation ─────────────────────────────────────────────

    private fun generateDefault(file: Path, groupConfig: GroupConfig) {
        val name = groupConfig.group.name
        val maxPlayers = groupConfig.group.resources.maxPlayers
        val toml = """
            |# ⛅ Nimbus — Display Config for $name
            |# Controls how this group appears on signs, NPCs, and scoreboards.
            |# Auto-generated — feel free to customize!
            |
            |[display]
            |name = "$name"
            |
            |# ── Sign Layout ──────────────────────────────────────────────
            |# Placeholders: {name}, {players}, {max_players}, {servers}, {state}
            |# Color codes: &1-&f, &l (bold), &o (italic), &n (underline)
            |
            |[display.sign]
            |line1 = "&1&l★ $name ★"
            |line2 = "&8{players}/${maxPlayers} online"
            |line3 = "&7{state}"
            |line4_online = "&2▶ Click to join!"
            |line4_offline = "&4✖ Offline"
            |
            |# ── NPC Appearance ────────────────────────────────────────────
            |# Placeholders: {name}, {players}, {max_players}, {servers}, {state}
            |
            |[display.npc]
            |display_name = "&b&l$name"
            |subtitle = "&7{players}/${maxPlayers} online &8| &7{state}"
            |subtitle_offline = "&c✖ Offline"
            |floating_item = "${guessFloatingItem(name)}"
            |
            |# ── NPC Status Items ──────────────────────────────────────────
            |# Material shown in NPC's hand based on resolved state label.
            |# Also used as item material in the server selector inventory.
            |
            |[display.npc.status_items]
            |ONLINE = "LIME_WOOL"
            |STARTING = "YELLOW_WOOL"
            |INGAME = "ORANGE_WOOL"
            |WAITING = "LIGHT_BLUE_WOOL"
            |OFFLINE = "GRAY_WOOL"
            |FULL = "RED_WOOL"
            |ENDING = "ORANGE_WOOL"
            |STOPPING = "RED_WOOL"
            |
            |# ── NPC Server Inventory ──────────────────────────────────────
            |# Opened when a player interacts with the NPC (INVENTORY action).
            |# Placeholders: {name}, {players}, {max_players}, {servers}, {state}
            |
            |[display.npc.inventory]
            |title = "&8» &b&l$name Servers"
            |size = 27
            |item_name = "&b{name}"
            |item_lore = ["&7Players: &f{players}/{max_players}", "&7State: &f{state}", "", "&aClick to join!"]
            |
            |# ── State Labels ──────────────────────────────────────────────
            |# Maps internal states to display-friendly names.
            |# Add custom states from your plugins here.
            |
            |[display.states]
            |PREPARING = "STARTING"
            |STARTING = "STARTING"
            |READY = "ONLINE"
            |STOPPING = "STOPPING"
            |STOPPED = "OFFLINE"
            |CRASHED = "OFFLINE"
            |WAITING = "WAITING"
            |INGAME = "INGAME"
            |ENDING = "ENDING"
        """.trimMargin()

        file.writeText(toml + "\n")
    }

    /** Guess a fitting floating item based on group name. */
    private fun guessFloatingItem(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("bedwar") -> "RED_BED"
            lower.contains("skywar") -> "EYE_OF_ENDER"
            lower.contains("skyblock") -> "GRASS_BLOCK"
            lower.contains("survival") -> "DIAMOND_PICKAXE"
            lower.contains("creative") -> "PAINTING"
            lower.contains("lobby") -> "NETHER_STAR"
            lower.contains("practice") -> "IRON_SWORD"
            lower.contains("pvp") -> "DIAMOND_SWORD"
            lower.contains("build") -> "BRICKS"
            lower.contains("prison") -> "IRON_BARS"
            lower.contains("faction") -> "TNT"
            lower.contains("kitpvp") -> "GOLDEN_APPLE"
            lower.contains("duels") -> "BOW"
            lower.contains("tnt") -> "TNT"
            lower.contains("party") -> "CAKE"
            else -> "GRASS_BLOCK"
        }
    }

    // ── TOML Writing ──────────────────────────────────────────────────

    private fun writeDisplayToml(groupName: String, config: DisplayConfig) {
        val d = config.display
        val s = d.sign
        val n = d.npc
        val inv = n.inventory
        val statusItemsToml = n.statusItems.entries.joinToString("\n") { """"${it.key}" = "${it.value}"""" }
        val stateToml = d.states.entries.joinToString("\n") { """${it.key} = "${it.value}"""" }
        val loreToml = inv.itemLore.joinToString(", ") { """"$it"""" }
        val toml = """
            |# ⛅ Nimbus — Display Config for $groupName
            |
            |[display]
            |name = "$groupName"
            |
            |[display.sign]
            |line1 = "${s.line1}"
            |line2 = "${s.line2}"
            |line3 = "${s.line3}"
            |line4_online = "${s.line4Online}"
            |line4_offline = "${s.line4Offline}"
            |
            |[display.npc]
            |display_name = "${n.displayName}"
            |subtitle = "${n.subtitle}"
            |subtitle_offline = "${n.subtitleOffline}"
            |floating_item = "${n.floatingItem}"
            |
            |[display.npc.status_items]
            |$statusItemsToml
            |
            |[display.npc.inventory]
            |title = "${inv.title}"
            |size = ${inv.size}
            |item_name = "${inv.itemName}"
            |item_lore = [$loreToml]
            |
            |[display.states]
            |$stateToml
        """.trimMargin()

        displaysDir.resolve("${groupName}.toml").writeText(toml + "\n")
    }

    // ── TOML Parsing ──────────────────────────────────────────────────

    private fun parseDisplayConfig(file: Path): DisplayConfig {
        val content = file.readText()
        val name = extractString(content, "name") ?: file.fileName.toString().removeSuffix(".toml")

        // Parse sign section
        val line1 = extractString(content, "line1") ?: "&1&l★ $name ★"
        val line2 = extractString(content, "line2") ?: "&8{players}/{max_players} online"
        val line3 = extractString(content, "line3") ?: "&7{state}"
        val line4Online = extractString(content, "line4_online") ?: "&2▶ Click to join!"
        val line4Offline = extractString(content, "line4_offline") ?: "&4✖ Offline"
        val sign = SignDisplay(line1, line2, line3, line4Online, line4Offline)

        // Parse NPC section
        val displayName = extractString(content, "display_name") ?: "&b&l$name"
        val subtitle = extractString(content, "subtitle") ?: "&7{players}/{max_players} online"
        val subtitleOffline = extractString(content, "subtitle_offline") ?: "&c✖ Offline"
        val floatingItem = extractString(content, "floating_item") ?: "GRASS_BLOCK"
        val statusItems = extractSection(content, "display.npc.status_items")
            .ifEmpty { defaultStatusItems() }
        val inventoryTitle = extractString(content, "title", "display.npc.inventory") ?: "&8» &b&l$name Servers"
        val inventorySize = extractInt(content, "size", "display.npc.inventory") ?: 27
        val inventoryItemName = extractString(content, "item_name", "display.npc.inventory") ?: "&b{name}"
        val inventoryItemLore = extractStringArray(content, "item_lore", "display.npc.inventory")
            ?: listOf("&7Players: &f{players}/{max_players}", "&7State: &f{state}", "", "&aClick to join!")
        val npcInventory = NpcInventoryConfig(inventoryTitle, inventorySize, inventoryItemName, inventoryItemLore)
        val npc = NpcDisplay(displayName, subtitle, subtitleOffline, floatingItem, statusItems, npcInventory)

        // Parse states section
        val states = extractStates(content)

        return DisplayConfig(DisplayDefinition(name, sign, npc, states))
    }

    private fun extractString(content: String, key: String): String? {
        val regex = Regex("""^\s*$key\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)
    }

    private fun extractStates(content: String): Map<String, String> {
        val states = mutableMapOf<String, String>()
        // Find lines in [display.states] section
        val sectionRegex = Regex("""\[display\.states]\s*\n([\s\S]*?)(?=\n\[|\z)""")
        val section = sectionRegex.find(content)?.groupValues?.get(1) ?: return defaultStateLabels()

        val lineRegex = Regex("""^\s*(\w+)\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        lineRegex.findAll(section).forEach { match ->
            states[match.groupValues[1]] = match.groupValues[2]
        }

        return states.ifEmpty { defaultStateLabels() }
    }

    /** Extract key-value pairs from a TOML section like [display.npc.status_items]. */
    private fun extractSection(content: String, sectionName: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val escaped = sectionName.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[|\z)""")
        val section = sectionRegex.find(content)?.groupValues?.get(1) ?: return result

        val lineRegex = Regex("""^\s*(\w+)\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        lineRegex.findAll(section).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }

    /** Extract a string value from within a specific TOML section. */
    private fun extractString(content: String, key: String, sectionName: String): String? {
        val escaped = sectionName.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[|\z)""")
        val section = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        return regex.find(section)?.groupValues?.get(1)
    }

    /** Extract an integer value from within a specific TOML section. */
    private fun extractInt(content: String, key: String, sectionName: String): Int? {
        val escaped = sectionName.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[|\z)""")
        val section = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
        return regex.find(section)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Extract a TOML array of strings like ["a", "b", "c"]. */
    private fun extractStringArray(content: String, key: String, sectionName: String): List<String>? {
        val escaped = sectionName.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[|\z)""")
        val section = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*\[(.*?)]\s*$""", RegexOption.MULTILINE)
        val match = regex.find(section) ?: return null
        val arrayContent = match.groupValues[1]
        val itemRegex = Regex(""""(.*?)"""")
        return itemRegex.findAll(arrayContent).map { it.groupValues[1] }.toList().ifEmpty { null }
    }
}
