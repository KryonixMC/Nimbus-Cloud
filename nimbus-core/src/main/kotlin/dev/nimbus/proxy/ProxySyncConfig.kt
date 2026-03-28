package dev.nimbus.proxy

data class ProxySyncConfig(
    val tabList: TabListConfig = TabListConfig(),
    val motd: MotdConfig = MotdConfig(),
    val chat: ChatConfig = ChatConfig()
)

data class TabListConfig(
    val header: String = "\n<gradient:#58a6ff:#56d4dd><bold>☁ NIMBUS CLOUD</bold></gradient>\n",
    val footer: String = "\n<gray>Online</gray> <white>»</white> <gradient:#56d4dd:#b392f0>{online}</gradient><dark_gray>/</dark_gray><gray>{max}</gray>\n",
    val playerFormat: String = "{prefix}{player}{suffix}",
    val updateInterval: Int = 5
)

data class MotdConfig(
    val line1: String = "  <gradient:#58a6ff:#56d4dd:#b392f0><bold>☁ NIMBUS CLOUD</bold></gradient>",
    val line2: String = "  <gray>» </gray><gradient:#56d364:#56d4dd>{online} players online</gradient>",
    val maxPlayers: Int = -1,
    val playerCountOffset: Int = 0
)

data class ChatConfig(
    val format: String = "{prefix}{player}{suffix} <dark_gray>» <gray>{message}",
    val enabled: Boolean = true
)
