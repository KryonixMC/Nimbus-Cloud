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
    val jvm: JvmConfig = JvmConfig(),
    val placement: PlacementConfig = PlacementConfig(),
    val sync: SyncConfig = SyncConfig()
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

/**
 * Placement policy for services in a group.
 *
 *   node = ""         → any available node (default scheduler behavior)
 *   node = "local"    → force controller-local (used for services that MUST stay local)
 *   node = "<id>"     → pin to a specific agent node by its node_name
 *
 *   fallback = "local" | "wait" | "fail"
 *     local: fall back to controller if the pinned node is offline
 *            (NOTE: only safe for stateless groups — stateful data stays on the node!)
 *     wait:  don't start until the pinned node is available (default for pinned services)
 *     fail:  refuse to start with an error
 *
 * For STATIC and DEDICATED services pinned to a node, `fallback = "wait"` is strongly
 * recommended — falling back to the controller creates divergent data on two hosts.
 */
@Serializable
data class PlacementConfig(
    val node: String = "",
    val fallback: String = "wait"
)

/**
 * State sync policy for a group. When enabled, the controller keeps the canonical
 * copy of the service's working directory in `services/state/<name>/` and the agent
 * pulls from it on start and pushes back on graceful stop. This allows unpinned
 * services to move between nodes while preserving data across restarts.
 *
 * Data loss model: if a service **crashes** instead of stopping gracefully, all
 * changes since the last push are lost. For zero-loss guarantees, use pinning
 * (`[group.placement] node = "<id>"`) instead.
 *
 * Mutual exclusion: setting both `sync.enabled = true` AND `placement.node` is
 * nonsensical and logged as a warning at startup. Sync wins (service floats).
 *
 * Excludes are rsync-style glob patterns (trailing `/` = directory, `*.ext` =
 * extension). Matched files are neither uploaded nor deleted during reconcile.
 */
@Serializable
data class SyncConfig(
    val enabled: Boolean = false,
    val excludes: List<String> = listOf(
        "logs/",
        "cache/",
        "crash-reports/",
        "*.tmp",
        "*.lock",
        "*.pid",
        "session.lock"
    ),
    /**
     * Periodic snapshot interval in seconds. 0 = only push on graceful stop
     * (no periodic snapshots). When set, the agent sends [snapshotFlushCommand]
     * to the service's stdin every N seconds, waits [snapshotFlushWaitMs], and
     * pushes the current state to the controller as a new canonical checkpoint.
     *
     * Shrinks the crash data-loss window from "last stop" to "last interval".
     * Tradeoff: runtime bandwidth + brief TPS hiccup while the server flushes.
     */
    @SerialName("snapshot_interval")
    val snapshotInterval: Long = 0,
    /**
     * Console command sent to the service's stdin before a periodic snapshot.
     * Empty string = no flush command (file-scan only, less consistent).
     * Defaults are software-specific: Paper/Purpur/Pufferfish/Leaf/Folia/Forge/NeoForge/Fabric
     * use `save-all flush`; Vanilla uses `save-all`; Velocity has no persistent
     * world data so the default is empty.
     */
    @SerialName("snapshot_flush_command")
    val snapshotFlushCommand: String = "",
    /**
     * Milliseconds to wait after sending [snapshotFlushCommand] before scanning
     * the filesystem. Gives the server time to finish flushing chunks to disk.
     * Default 3000ms is comfortable for a single world; bump it for modded
     * servers with many dimensions.
     */
    @SerialName("snapshot_flush_wait_ms")
    val snapshotFlushWaitMs: Long = 3000
)
