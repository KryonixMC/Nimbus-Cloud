package dev.nimbus.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NimbusConfig(
    val network: NetworkConfig = NetworkConfig(),
    val controller: ControllerConfig = ControllerConfig(),
    val console: ConsoleConfig = ConsoleConfig(),
    val paths: PathsConfig = PathsConfig()
)

@Serializable
data class NetworkConfig(
    val name: String = "Nimbus",
    val bind: String = "0.0.0.0"
)

@Serializable
data class ControllerConfig(
    @SerialName("max_memory")
    val maxMemory: String = "10G",
    @SerialName("max_services")
    val maxServices: Int = 20,
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long = 5000
)

@Serializable
data class ConsoleConfig(
    val colored: Boolean = true,
    @SerialName("log_events")
    val logEvents: Boolean = true,
    @SerialName("history_file")
    val historyFile: String = ".nimbus_history"
)

@Serializable
data class PathsConfig(
    val templates: String = "templates",
    val running: String = "running",
    val logs: String = "logs"
)
