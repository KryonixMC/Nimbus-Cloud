package dev.nimbus.service

import java.util.concurrent.ConcurrentHashMap

class ServiceRegistry {

    private val services = ConcurrentHashMap<String, Service>()

    fun register(service: Service) {
        services[service.name] = service
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
