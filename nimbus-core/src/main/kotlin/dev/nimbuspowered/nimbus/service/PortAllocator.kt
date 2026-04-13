package dev.nimbuspowered.nimbus.service

import org.slf4j.LoggerFactory
import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.Collections

class PortAllocator(
    private val proxyPort: Int = 25565,
    private val backendBasePort: Int = 30000,
    private val lbEnabled: Boolean = false,
    private val bedrockEnabled: Boolean = false,
    private val bedrockBasePort: Int = 19132
) {

    private val logger = LoggerFactory.getLogger(PortAllocator::class.java)
    private val allocatedPorts: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    private val allocatedBedrockPorts: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    /** Ports that have been probed and confirmed unavailable (occupied by external processes). */
    private val externallyOccupied: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    private val externallyOccupiedUdp: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

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
     * Uses in-memory tracking as the primary check. Socket probing is only
     * done once per port to detect external occupancy, then cached.
     * @throws IllegalStateException if no port is available in range 30000-39999
     */
    fun allocateBackendPort(): Int {
        val maxPort = backendBasePort + 9999
        var port = backendBasePort
        synchronized(allocatedPorts) {
            while (allocatedPorts.contains(port) || isExternallyOccupiedTcp(port)) {
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

    /**
     * Allocates a Bedrock UDP port starting from the configured base port (default 19132).
     * Each proxy instance gets its own UDP port for Geyser.
     */
    fun allocateBedrockPort(): Int {
        if (!bedrockEnabled) {
            throw IllegalStateException("Bedrock support is not enabled")
        }
        val maxPort = bedrockBasePort + 99
        var port = bedrockBasePort
        synchronized(allocatedBedrockPorts) {
            while (allocatedBedrockPorts.contains(port) || isExternallyOccupiedUdp(port)) {
                port++
                if (port > maxPort) {
                    throw IllegalStateException("No available Bedrock ports in range $bedrockBasePort-$maxPort")
                }
            }
            allocatedBedrockPorts.add(port)
        }
        logger.info("Allocated Bedrock UDP port {}", port)
        return port
    }

    fun reserveIfAvailable(port: Int): Boolean {
        synchronized(allocatedPorts) {
            if (allocatedPorts.contains(port) || isExternallyOccupiedTcp(port)) {
                return false
            }
            allocatedPorts.add(port)
        }
        logger.info("Reserved dedicated port {}", port)
        return true
    }

    fun reserve(port: Int) {
        synchronized(allocatedPorts) {
            allocatedPorts.add(port)
        }
        logger.info("Reserved port {} (recovered service)", port)
    }

    fun reserveBedrockPort(port: Int) {
        synchronized(allocatedBedrockPorts) {
            allocatedBedrockPorts.add(port)
        }
        logger.info("Reserved Bedrock port {} (recovered service)", port)
    }

    fun release(port: Int) {
        if (allocatedPorts.remove(port)) {
            externallyOccupied.remove(port)
            logger.info("Released port {}", port)
        } else {
            logger.warn("Attempted to release untracked port {}", port)
        }
    }

    fun releaseBedrockPort(port: Int) {
        if (allocatedBedrockPorts.remove(port)) {
            externallyOccupiedUdp.remove(port)
            logger.info("Released Bedrock port {}", port)
        }
    }

    /**
     * Clears the external port occupancy cache so ports are re-probed on next allocation.
     * Call periodically to discover ports freed by external processes.
     */
    fun invalidateExternalCache() {
        externallyOccupied.clear()
        externallyOccupiedUdp.clear()
        logger.debug("External port occupancy cache cleared")
    }

    /**
     * Checks if a TCP port is occupied by an external process (not tracked by us).
     * Result is cached so the socket probe only happens once per port.
     */
    private fun isExternallyOccupiedTcp(port: Int): Boolean {
        if (externallyOccupied.contains(port)) return true
        val available = try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
        if (!available) {
            externallyOccupied.add(port)
        }
        return !available
    }

    /**
     * Checks if a UDP port is occupied by an external process (not tracked by us).
     * Result is cached so the socket probe only happens once per port.
     */
    private fun isExternallyOccupiedUdp(port: Int): Boolean {
        if (externallyOccupiedUdp.contains(port)) return true
        val available = try {
            DatagramSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
        if (!available) {
            externallyOccupiedUdp.add(port)
        }
        return !available
    }
}
