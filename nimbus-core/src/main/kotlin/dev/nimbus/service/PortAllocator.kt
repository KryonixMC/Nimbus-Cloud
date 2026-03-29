package dev.nimbus.service

import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.Collections

class PortAllocator(
    private val proxyPort: Int = 25565,
    private val backendBasePort: Int = 30000,
    private val lbEnabled: Boolean = false
) {

    private val logger = LoggerFactory.getLogger(PortAllocator::class.java)
    private val allocatedPorts: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Allocates the fixed proxy port (25565).
     */
    fun allocateProxyPort(): Int {
        if (lbEnabled) {
            // LB owns 25565; proxies get backend ports
            return allocateBackendPort()
        }
        synchronized(allocatedPorts) {
            allocatedPorts.add(proxyPort)
        }
        logger.info("Allocated proxy port {}", proxyPort)
        return proxyPort
    }

    /**
     * Allocates a backend port from the high range (30000+).
     * @throws IllegalStateException if no port is available in range 30000-39999
     */
    fun allocateBackendPort(): Int {
        val maxPort = backendBasePort + 9999
        var port = backendBasePort
        synchronized(allocatedPorts) {
            while (allocatedPorts.contains(port) || !isPortAvailable(port)) {
                port++
                if (port > maxPort) {
                    throw IllegalStateException("No available backend ports in range $backendBasePort-$maxPort")
                }
            }
            allocatedPorts.add(port)

            val remaining = maxPort - allocatedPorts.size
            if (remaining < 100) {
                logger.warn("Low on backend ports: only {} remaining in range {}-{}", remaining, backendBasePort, maxPort)
            }
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
