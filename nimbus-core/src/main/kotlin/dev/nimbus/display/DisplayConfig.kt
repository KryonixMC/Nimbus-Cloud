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
    val item: String = "GRASS_BLOCK",
    val subtitle: String = "&7{players}/{max_players} online &8| &7{state}",
    @SerialName("subtitle_offline")
    val subtitleOffline: String = "&c✖ Offline"
)

fun defaultStateLabels(): Map<String, String> = mapOf(
    "PREPARING" to "STARTING",
    "STARTING" to "STARTING",
    "READY" to "ONLINE",
    "STOPPING" to "STOPPING",
    "STOPPED" to "OFFLINE",
    "CRASHED" to "OFFLINE",
    // Custom states (plugins can add more)
    "WAITING" to "WAITING",
    "INGAME" to "INGAME",
    "ENDING" to "ENDING"
)
