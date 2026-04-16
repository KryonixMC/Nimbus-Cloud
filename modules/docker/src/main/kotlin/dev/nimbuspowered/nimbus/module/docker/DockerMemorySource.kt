package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceMemorySource

/**
 * Docker-aware memory source — reads resident set from `docker stats`, which
 * reports cgroup memory (the full container RSS, including any helper processes).
 * On Linux this matches `memory.current` from cgroup v2.
 *
 * Returns null when the service doesn't belong to a Nimbus-managed container,
 * so [dev.nimbuspowered.nimbus.service.ServiceMemoryResolver] falls through to
 * `/proc/<pid>/status` for plain-process services.
 */
class DockerMemorySource(
    private val handleLookup: (String) -> DockerServiceHandle?
) : ServiceMemorySource {

    override fun readRssMb(service: Service): Long? {
        val handle = handleLookup(service.name) ?: return null
        val stats = handle.liveStats() ?: return null
        if (stats.memoryBytes <= 0) return null
        return stats.memoryBytes / 1024 / 1024
    }
}
