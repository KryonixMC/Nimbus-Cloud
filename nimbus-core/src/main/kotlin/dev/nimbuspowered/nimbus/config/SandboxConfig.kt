package dev.nimbuspowered.nimbus.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-group sandbox configuration. Controls how a service's JVM process is
 * spawned — as a plain bare process, wrapped in a cgroup (Linux systemd-run)
 * for kernel-enforced memory/CPU limits, or delegated to the Docker module.
 *
 *   mode = ""           → resolve via global [sandbox] default_mode
 *   mode = "bare"       → plain ProcessBuilder, no enforcement (legacy behavior)
 *   mode = "managed"    → cgroup v2 via systemd-run on Linux; falls back to bare
 *                         on platforms where that primitive isn't available
 *   mode = "docker"     → only valid when the Docker module is loaded AND
 *                         [group.docker] enabled = true (kept for backwards compat)
 *
 * memory_limit_mb / cpu_quota / tasks_max are only applied when mode = "managed".
 * A value of 0 means "derive from [group.resources] / unlimited".
 */
@Serializable
data class SandboxConfig(
    val mode: String = "",
    @SerialName("memory_limit_mb")
    val memoryLimitMb: Long = 0,
    @SerialName("cpu_quota")
    val cpuQuota: Double = 0.0,
    @SerialName("tasks_max")
    val tasksMax: Int = 0
)

/**
 * Global sandbox defaults. Applied when a group does not set its own
 * [SandboxConfig.mode]. See [SandboxConfig] for the mode values.
 *
 * default_mode = "auto" means: use "managed" if the platform supports it
 * (Linux with a reachable user systemd bus), otherwise "bare" with a
 * one-time INFO log at bootstrap.
 */
@Serializable
data class GlobalSandboxConfig(
    @SerialName("default_mode")
    val defaultMode: String = "auto",
    @SerialName("memory_overhead_percent")
    val memoryOverheadPercent: Int = 30,
    @SerialName("memory_overhead_min_mb")
    val memoryOverheadMinMb: Long = 256
)
