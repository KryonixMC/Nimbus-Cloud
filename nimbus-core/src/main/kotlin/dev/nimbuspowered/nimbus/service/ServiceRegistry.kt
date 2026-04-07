package dev.nimbuspowered.nimbus.service

import java.util.concurrent.ConcurrentHashMap

class ServiceRegistry {

    private val services = ConcurrentHashMap<String, Service>()
    private val groupLocks = ConcurrentHashMap<String, Any>()

    fun register(service: Service) {
        services[service.name] = service
    }

    /**
     * Atomically checks that the group has fewer than [maxInstances] services,
     * then registers the service. Returns true if registered, false if at limit.
     * This prevents race conditions where concurrent startService calls could
     * exceed the max instance count.
     */
    fun registerIfUnderLimit(service: Service, maxInstances: Int): Boolean {
        val lock = groupLocks.computeIfAbsent(service.groupName) { Any() }
        synchronized(lock) {
            val currentCount = services.values.count { it.groupName == service.groupName }
            if (currentCount >= maxInstances) return false
            services[service.name] = service
            return true
        }
    }

    fun unregister(name: String) {
        services.remove(name)
    }

    fun get(name: String): Service? = services[name]

    fun getByGroup(groupName: String): List<Service> =
        services.values.filter { it.groupName == groupName }

    fun getAll(): List<Service> = services.values.toList()

    fun countByGroup(groupName: String): Int =
        services.values.count { it.groupName == groupName }
}
