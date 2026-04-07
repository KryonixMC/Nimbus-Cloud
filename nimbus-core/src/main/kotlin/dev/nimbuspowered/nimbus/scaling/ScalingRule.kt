package dev.nimbuspowered.nimbus.scaling

import java.time.Duration
import java.time.Instant

object ScalingRule {

    /**
     * Determines if a group needs to scale up.
     * @return reason string if should scale up, null otherwise
     */
    fun shouldScaleUp(
        totalPlayers: Int,
        readyInstances: Int,
        maxInstances: Int,
        playersPerInstance: Int,
        scaleThreshold: Double,
        minInstances: Int = 0
    ): String? {
        // If no instances are ready but minInstances requires some, scale up to recover
        if (readyInstances == 0) {
            return if (minInstances > 0) "no ready instances (min_instances=$minInstances)" else null
        }

        val fillRate = totalPlayers.toDouble() / (readyInstances * playersPerInstance)

        if (fillRate > scaleThreshold && readyInstances < maxInstances) {
            return "fill rate ${(fillRate * 100).toInt()}% > threshold ${(scaleThreshold * 100).toInt()}%"
        }

        return null
    }

    /**
     * Determines if a specific service should be scaled down.
     * @return reason string if should scale down, null otherwise
     */
    fun shouldScaleDown(
        servicePlayers: Int,
        idleTimeout: Long,
        serviceIdleSince: Instant?,
        currentInstances: Int,
        minInstances: Int
    ): String? {
        if (idleTimeout <= 0) return null
        if (servicePlayers > 0) return null
        if (currentInstances <= minInstances) return null
        if (serviceIdleSince == null) return null

        val seconds = Duration.between(serviceIdleSince, Instant.now()).seconds

        if (seconds > idleTimeout) {
            return "empty for ${seconds}s > timeout ${idleTimeout}s"
        }

        return null
    }
}
