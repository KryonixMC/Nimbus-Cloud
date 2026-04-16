package dev.nimbuspowered.nimbus.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DedicatedServiceConfig(
    val dedicated: DedicatedDefinition
)

@Serializable
data class DedicatedDefinition(
    val name: String,
    val port: Int,
    val software: ServerSoftware = ServerSoftware.PAPER,
    val version: String = "1.21.4",
    @SerialName("jar_name")
    val jarName: String = "",
    @SerialName("ready_pattern")
    val readyPattern: String = "",
    @SerialName("java_path")
    val javaPath: String = "",
    @SerialName("proxy_enabled")
    val proxyEnabled: Boolean = true,
    val memory: String = "2G",
    @SerialName("restart_on_crash")
    val restartOnCrash: Boolean = true,
    @SerialName("max_restarts")
    val maxRestarts: Int = 5,
    val jvm: JvmConfig = JvmConfig(),
    val placement: PlacementConfig = PlacementConfig(),
    val sync: SyncConfig = SyncConfig(),
    val docker: DockerServiceConfig = DockerServiceConfig()
)
