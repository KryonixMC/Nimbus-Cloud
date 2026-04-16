package dev.nimbuspowered.nimbus.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-group / per-dedicated Docker settings. Opt-in: [enabled] defaults to false so
 * groups that never set a `[docker]` block keep running as bare processes.
 *
 * Empty strings mean "inherit from the global docker module config"
 * (`config/modules/docker/docker.toml`).
 */
@Serializable
data class DockerServiceConfig(
    val enabled: Boolean = false,
    @SerialName("memory_limit")
    val memoryLimit: String = "",
    @SerialName("cpu_limit")
    val cpuLimit: Double = 0.0,
    @SerialName("java_image")
    val javaImage: String = "",
    val network: String = ""
)
