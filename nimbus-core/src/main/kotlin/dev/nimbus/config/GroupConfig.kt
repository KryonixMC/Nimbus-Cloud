package dev.nimbus.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GroupType {
    STATIC,
    DYNAMIC
}

@Serializable
enum class ServerSoftware {
    PAPER,
    VELOCITY,
    PURPUR
}

@Serializable
data class GroupConfig(
    val group: GroupDefinition
)

@Serializable
data class GroupDefinition(
    val name: String,
    val type: GroupType = GroupType.DYNAMIC,
    val template: String = "",
    val software: ServerSoftware = ServerSoftware.PAPER,
    val version: String = "1.21.4",
    val resources: ResourcesConfig = ResourcesConfig(),
    val scaling: ScalingConfig = ScalingConfig(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
    val jvm: JvmConfig = JvmConfig()
)

@Serializable
data class ResourcesConfig(
    val memory: String = "1G",
    @SerialName("max_players")
    val maxPlayers: Int = 50
)

@Serializable
data class ScalingConfig(
    @SerialName("min_instances")
    val minInstances: Int = 1,
    @SerialName("max_instances")
    val maxInstances: Int = 4,
    @SerialName("players_per_instance")
    val playersPerInstance: Int = 40,
    @SerialName("scale_threshold")
    val scaleThreshold: Double = 0.8,
    @SerialName("idle_timeout")
    val idleTimeout: Long = 0
)

@Serializable
data class LifecycleConfig(
    @SerialName("stop_on_empty")
    val stopOnEmpty: Boolean = false,
    @SerialName("restart_on_crash")
    val restartOnCrash: Boolean = true,
    @SerialName("max_restarts")
    val maxRestarts: Int = 5
)

@Serializable
data class JvmConfig(
    val args: List<String> = listOf("-XX:+UseG1GC")
)
