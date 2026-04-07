package dev.nimbuspowered.nimbus.config

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
    PURPUR,
    FOLIA,
    FORGE,
    PUFFERFISH,
    LEAF,
    FABRIC,
    NEOFORGE,
    CUSTOM
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
    val templates: List<String> = emptyList(),
    val software: ServerSoftware = ServerSoftware.PAPER,
    val version: String = "1.21.4",
    @SerialName("modloader_version")
    val modloaderVersion: String = "",
    @SerialName("jar_name")
    val jarName: String = "",
    @SerialName("ready_pattern")
    val readyPattern: String = "",
    @SerialName("java_path")
    val javaPath: String = "",
    val resources: ResourcesConfig = ResourcesConfig(),
    val scaling: ScalingConfig = ScalingConfig(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
    val jvm: JvmConfig = JvmConfig()
) {
    /**
     * Returns the effective list of templates. If [templates] is set, it is used directly.
     * Otherwise, falls back to the single [template] field for backwards compatibility.
     */
    val resolvedTemplates: List<String>
        get() = templates.ifEmpty { listOfNotNull(template.ifEmpty { null }) }
}

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
    val idleTimeout: Long = 0,
    @SerialName("warm_pool_size")
    val warmPoolSize: Int = 0
)

@Serializable
data class LifecycleConfig(
    @SerialName("stop_on_empty")
    val stopOnEmpty: Boolean = false,
    @SerialName("restart_on_crash")
    val restartOnCrash: Boolean = true,
    @SerialName("max_restarts")
    val maxRestarts: Int = 5,
    @SerialName("drain_timeout")
    val drainTimeout: Long = 30,
    @SerialName("deploy_on_stop")
    val deployOnStop: Boolean = false,
    @SerialName("deploy_excludes")
    val deployExcludes: List<String> = listOf("logs", "crash-reports", "cache", "libraries", "*.tmp")
)

@Serializable
data class JvmConfig(
    val args: List<String> = emptyList(),
    val optimize: Boolean = true
)
