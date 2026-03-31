package dev.nimbus.display

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DisplayConfig(
    val display: DisplayDefinition
)

@Serializable
data class DisplayDefinition(
    val name: String,
    val sign: SignDisplay = SignDisplay(),
    val npc: NpcDisplay = NpcDisplay(),
    val states: Map<String, String> = defaultStateLabels()
)

@Serializable
data class SignDisplay(
    val line1: String = "&1&l★ {name} ★",
    val line2: String = "&8{players}/{max_players} online",
    val line3: String = "&7{state}",
    @SerialName("line4_online")
    val line4Online: String = "&2▶ Click to join!",
    @SerialName("line4_offline")
    val line4Offline: String = "&4✖ Offline"
)

@Serializable
data class NpcDisplay(
    @SerialName("display_name")
    val displayName: String = "&b&l{name}",
    val subtitle: String = "&7{players}/{max_players} online &8| &7{state}",
    @SerialName("subtitle_offline")
    val subtitleOffline: String = "&c✖ Offline",
    @SerialName("floating_item")
    val floatingItem: String = "GRASS_BLOCK",
    @SerialName("status_items")
    val statusItems: Map<String, String> = defaultStatusItems(),
    val inventory: NpcInventoryConfig = NpcInventoryConfig()
)

@Serializable
data class NpcInventoryConfig(
    val title: String = "&8» &b&l{name} Servers",
    val size: Int = 27,
    @SerialName("item_name")
    val itemName: String = "&b{name}",
    @SerialName("item_lore")
    val itemLore: List<String> = listOf(
        "&7Players: &f{players}/{max_players}",
        "&7State: &f{state}",
        "",
        "&aClick to join!"
    )
)

fun defaultStateLabels(): Map<String, String> = mapOf(
    "PREPARING" to "STARTING",
    "STARTING" to "STARTING",
    "READY" to "ONLINE",
    "STOPPING" to "STOPPING",
    "STOPPED" to "OFFLINE",
    "CRASHED" to "OFFLINE",
    "WAITING" to "WAITING",
    "INGAME" to "INGAME",
    "ENDING" to "ENDING"
)

fun defaultStatusItems(): Map<String, String> = mapOf(
    "ONLINE" to "LIME_WOOL",
    "STARTING" to "YELLOW_WOOL",
    "INGAME" to "ORANGE_WOOL",
    "WAITING" to "LIGHT_BLUE_WOOL",
    "OFFLINE" to "GRAY_WOOL",
    "FULL" to "RED_WOOL",
    "ENDING" to "ORANGE_WOOL",
    "STOPPING" to "RED_WOOL"
)
