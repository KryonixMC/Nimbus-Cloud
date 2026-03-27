package dev.nimbus.service

import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.Collections

class PortAllocator(
    private val proxyPort: Int = 25565,
    private val backendBasePort: Int = 30000
) {

    private val logger = LoggerFactory.getLogger(PortAllocator::class.java)
    private val allocatedPorts: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Allocates the fixed proxy port (25565).
     */
    fun allocateProxyPort(): Int {
        synchronized(allocatedPorts) {
            allocatedPorts.add(proxyPort)
        }
        logger.info("Allocated proxy port {}", proxyPort)
        return proxyPort
    }

    /**
     * Allocates a backend port from the high range (30000+).
     */
    fun allocateBackendPort(): Int {
        var port = backendBasePort
        synchronized(allocatedPorts) {
            while (allocatedPorts.contains(port) || !isPortAvailable(port)) {
                port++
            }
            allocatedPorts.add(port)
        }
        logger.info("Allocated backend port {}", port)
        return port
    }

    fun release(port: Int) {
        if (allocatedPorts.remove(port)) {
            logger.info("Released port {}", port)
        } else {
            logger.warn("Attempted to release untracked port {}", port)
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
