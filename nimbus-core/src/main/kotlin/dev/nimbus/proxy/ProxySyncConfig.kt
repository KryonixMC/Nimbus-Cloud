package dev.nimbus.proxy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── File-level TOML wrappers (used for ktoml deserialization) ─────

@Serializable
data class MotdFileConfig(
    val motd: MotdTomlSection = MotdTomlSection(),
    val maintenance: MaintenanceTomlSection = MaintenanceTomlSection()
)

@Serializable
data class MotdTomlSection(
    val line1: String = "",
    val line2: String = "",
    @SerialName("max_players")
    val maxPlayers: Int = -1,
    @SerialName("player_count_offset")
    val playerCountOffset: Int = 0
)

@Serializable
data class MaintenanceTomlSection(
    val enabled: Boolean = false,
    @SerialName("motd_line1")
    val motdLine1: String = "<gradient:#ff6b6b:#ee5a24><bold>ᴍᴀɪɴᴛᴇɴᴀɴᴄᴇ</bold></gradient>",
    @SerialName("motd_line2")
    val motdLine2: String = "<gray>We are currently performing maintenance.</gray>",
    @SerialName("protocol_text")
    val protocolText: String = "Maintenance",
    @SerialName("kick_message")
    val kickMessage: String = "<red><bold>Maintenance</bold></red>\n<gray>The server is currently under maintenance.\nPlease try again later.</gray>",
    val whitelist: List<String> = emptyList(),
    val groups: Map<String, GroupMaintenanceTomlSection> = emptyMap()
)

@Serializable
data class GroupMaintenanceTomlSection(
    val enabled: Boolean = false,
    @SerialName("kick_message")
    val kickMessage: String = "<red>This game mode is currently under maintenance.</red>"
)

@Serializable
data class TablistFileConfig(
    val tablist: TablistTomlSection = TablistTomlSection()
)

@Serializable
data class TablistTomlSection(
    val header: String = "",
    val footer: String = "",
    @SerialName("player_format")
    val playerFormat: String = "",
    @SerialName("update_interval")
    val updateInterval: Int = 5
)

@Serializable
data class ChatFileConfig(
    val chat: ChatTomlSection = ChatTomlSection()
)

@Serializable
data class ChatTomlSection(
    val format: String = "{prefix}{player}{suffix} <dark_gray>» <gray>{message}",
    val enabled: Boolean = true
)

// ── Runtime config classes ────────────────────────────────────────

data class ProxySyncConfig(
    val tabList: TabListConfig = TabListConfig(),
    val motd: MotdConfig = MotdConfig(),
    val chat: ChatConfig = ChatConfig()
)

data class TabListConfig(
    val header: String = "\n<gradient:#58a6ff:#56d4dd><bold>ɴɪᴍʙᴜꜱᴄʟᴏᴜᴅ</bold></gradient>\nᴍɪɴᴇᴄʀᴀꜰᴛ ᴄʟᴏᴜᴅ ꜱʏꜱᴛᴇᴍ\n\nv{version}\n",
    val footer: String = "\n<gray>Online</gray> <white>»</white> <gradient:#56d4dd:#b392f0>{online}</gradient><dark_gray>/</dark_gray><gray>{max}</gray>\n<gray>Server</gray> <white>»</white> <green>{server}\n",
    val playerFormat: String = "{prefix}{player}{suffix}",
    val updateInterval: Int = 5
)

data class MotdConfig(
    val line1: String = "<gradient:#58a6ff:#56d4dd><bold>ɴɪᴍʙᴜꜱᴄʟᴏᴜᴅ</bold></gradient>",
    val line2: String = "<gray>» </gray><gradient:#56d364:#56d4dd>{online} players online</gradient>",
    val maxPlayers: Int = -1,
    val playerCountOffset: Int = 0
)

data class ChatConfig(
    val format: String = "{prefix}{player}{suffix} <dark_gray>» <gray>{message}",
    val enabled: Boolean = true
)

// ── Maintenance ────────────────────────────────────────────────────

data class MaintenanceConfig(
    val global: GlobalMaintenanceConfig = GlobalMaintenanceConfig(),
    val groups: Map<String, GroupMaintenanceConfig> = emptyMap()
)

data class GlobalMaintenanceConfig(
    val enabled: Boolean = false,
    val motdLine1: String = "<gradient:#ff6b6b:#ee5a24><bold>ᴍᴀɪɴᴛᴇɴᴀɴᴄᴇ</bold></gradient>",
    val motdLine2: String = "<gray>We are currently performing maintenance.</gray>",
    val protocolText: String = "Maintenance",
    val kickMessage: String = "<red><bold>Maintenance</bold></red>\n<gray>The server is currently under maintenance.\nPlease try again later.</gray>",
    val whitelist: List<String> = emptyList()
)

data class GroupMaintenanceConfig(
    val enabled: Boolean = false,
    val kickMessage: String = "<red>This game mode is currently under maintenance.</red>"
)
