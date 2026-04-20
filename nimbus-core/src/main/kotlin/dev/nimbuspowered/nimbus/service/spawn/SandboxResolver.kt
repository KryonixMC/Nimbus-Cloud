package dev.nimbuspowered.nimbus.service.spawn

import dev.nimbuspowered.nimbus.config.GlobalSandboxConfig
import dev.nimbuspowered.nimbus.config.ResourcesConfig
import dev.nimbuspowered.nimbus.config.SandboxConfig
import org.slf4j.LoggerFactory

/**
 * Resolves the effective [SandboxMode] and [SandboxLimits] for a single
 * service spawn. Consolidates the group override, the global default, and
 * platform availability probing in one place.
 *
 * The resolution precedence:
 *   1. Per-group `[group.sandbox] mode`, if non-empty, wins.
 *   2. Else: global `[sandbox] default_mode`. Value "auto" resolves to
 *      MANAGED when [SystemdRunSandbox.isAvailable] is true, else BARE.
 *   3. Unknown / unrecognized mode strings fall back to BARE with a WARN log.
 *
 * Limits are resolved similarly — a non-zero group value wins, else the value
 * is derived from `[group.resources] memory` + the global overhead budget.
 */
class SandboxResolver(
    private val globalConfig: GlobalSandboxConfig,
    private val systemdRunAvailable: () -> Boolean = { SystemdRunSandbox.isAvailable() }
) {
    private val logger = LoggerFactory.getLogger(SandboxResolver::class.java)

    data class Resolved(val mode: SandboxMode, val limits: SandboxLimits)

    /**
     * Resolve the sandbox for a service that is NOT using the Docker path.
     * Callers that detect a Docker opt-in should short-circuit and not call
     * this at all.
     */
    fun resolve(
        serviceName: String,
        groupSandbox: SandboxConfig,
        resources: ResourcesConfig
    ): Resolved {
        val mode = resolveMode(groupSandbox.mode, globalConfig.defaultMode)
        if (mode != SandboxMode.MANAGED) {
            return Resolved(mode, SandboxLimits())
        }
        val limits = resolveLimits(groupSandbox, resources)
        logger.debug(
            "Resolved managed sandbox for '{}': memMb={}, cpuQuota={}, tasksMax={}",
            serviceName, limits.memoryMb, limits.cpuQuota, limits.tasksMax
        )
        return Resolved(mode, limits)
    }

    private fun resolveMode(groupMode: String, globalDefault: String): SandboxMode {
        val requested = groupMode.trim().ifEmpty { globalDefault.trim() }.lowercase()
        return when (requested) {
            "bare" -> SandboxMode.BARE
            "managed" -> if (systemdRunAvailable()) SandboxMode.MANAGED else SandboxMode.BARE
            "docker" -> SandboxMode.DOCKER
            "auto", "" -> if (systemdRunAvailable()) SandboxMode.MANAGED else SandboxMode.BARE
            else -> {
                logger.warn("Unknown sandbox mode '{}' — falling back to bare", requested)
                SandboxMode.BARE
            }
        }
    }

    private fun resolveLimits(groupSandbox: SandboxConfig, resources: ResourcesConfig): SandboxLimits {
        val memoryMb = if (groupSandbox.memoryLimitMb > 0) {
            groupSandbox.memoryLimitMb
        } else {
            deriveMemoryCap(resources.memory)
        }
        return SandboxLimits(
            memoryMb = memoryMb,
            cpuQuota = groupSandbox.cpuQuota,
            tasksMax = groupSandbox.tasksMax
        )
    }

    /**
     * Derives a hard memory cap from the group's `-Xmx`-equivalent setting,
     * leaving room for JVM overhead (stacks, metaspace, direct buffers).
     * Mirrors the 30%/256MB heuristic the display layer already uses.
     */
    internal fun deriveMemoryCap(memoryString: String): Long {
        val heapMb = ResourcesConfig.parseMemoryMb(memoryString) ?: return 0L
        val overheadFromPercent = heapMb * globalConfig.memoryOverheadPercent / 100
        val overhead = maxOf(overheadFromPercent, globalConfig.memoryOverheadMinMb)
        return heapMb + overhead
    }
}
