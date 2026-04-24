package dev.nimbuspowered.nimbus.service

/**
 * Optional alternative source of resident-memory readings. Modules that know
 * about a more accurate source than `/proc/<pid>/status` (e.g. Docker stats via
 * cgroups — which accounts for everything running inside the container, not just
 * the main java PID) register themselves via
 * [dev.nimbuspowered.nimbus.module.api.ModuleContext.registerService] keyed by this
 * interface. [ServiceMemoryResolver] asks every registered source before falling
 * back to `/proc`.
 *
 * Implementations **must** return `null` when they don't know about [service] so
 * the resolver can cleanly fall through. Return `0L` only when you are sure the
 * service is truly using zero memory (not just "I don't know").
 */
interface ServiceMemorySource {
    /** Resident memory in MB for [service], or `null` when this source doesn't apply. */
    fun readRssMb(service: Service): Long?
}
