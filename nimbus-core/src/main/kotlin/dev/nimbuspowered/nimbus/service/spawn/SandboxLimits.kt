package dev.nimbuspowered.nimbus.service.spawn

/**
 * Resolved sandbox limits for a single service spawn.
 *
 * All values of 0 (or [cpuQuota] == 0.0) mean "no enforcement for this dimension".
 * Produced by [SandboxResolver] from the group/global config; consumed by
 * [SystemdRunSandbox.wrapCommand] and friends.
 */
data class SandboxLimits(
    /** Hard memory cap in MB. 0 = unlimited. */
    val memoryMb: Long = 0,
    /** CPU quota as a multiplier (1.0 = 100% of one core, 2.0 = two cores). 0.0 = unlimited. */
    val cpuQuota: Double = 0.0,
    /** Max task (thread+process) count within the scope. 0 = unlimited. */
    val tasksMax: Int = 0
) {
    fun isEmpty(): Boolean = memoryMb == 0L && cpuQuota == 0.0 && tasksMax == 0
}

/**
 * Which enforcement backend to use for a given spawn.
 *
 *  - [BARE]     — plain ProcessBuilder, no kernel-level enforcement (legacy)
 *  - [MANAGED]  — wrap via platform sandbox (systemd-run on Linux)
 *  - [DOCKER]   — delegated to the Docker module (handled outside ProcessHandle)
 */
enum class SandboxMode {
    BARE,
    MANAGED,
    DOCKER
}
