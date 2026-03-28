package dev.nimbus.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NimbusConfig(
    val network: NetworkConfig = NetworkConfig(),
    val controller: ControllerConfig = ControllerConfig(),
    val console: ConsoleConfig = ConsoleConfig(),
    val paths: PathsConfig = PathsConfig(),
    val api: ApiConfig = ApiConfig(),
    val java: JavaConfig = JavaConfig()
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
    val services: String = "services",
    val logs: String = "logs"
)

@Serializable
data class ApiConfig(
    val enabled: Boolean = true,
    val bind: String = "127.0.0.1",
    val port: Int = 8080,
    val token: String = "",
    @SerialName("allowed_origins")
    val allowedOrigins: List<String> = emptyList()
)

@Serializable
data class JavaConfig(
    @SerialName("java_8")
    val java8: String = "",
    @SerialName("java_11")
    val java11: String = "",
    @SerialName("java_16")
    val java16: String = "",
    @SerialName("java_17")
    val java17: String = "",
    @SerialName("java_21")
    val java21: String = ""
) {
    fun toMap(): Map<Int, String> = buildMap {
        if (java8.isNotEmpty()) put(8, java8)
        if (java11.isNotEmpty()) put(11, java11)
        if (java16.isNotEmpty()) put(16, java16)
        if (java17.isNotEmpty()) put(17, java17)
        if (java21.isNotEmpty()) put(21, java21)
    }
}
