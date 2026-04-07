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
            while (allocatedPorts.contains(port) || !isTcpPortAvailable(port)) {
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
            while (allocatedBedrockPorts.contains(port) || !isUdpPortAvailable(port)) {
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
            logger.info("Released port {}", port)
        } else {
            logger.warn("Attempted to release untracked port {}", port)
        }
    }

    fun releaseBedrockPort(port: Int) {
        if (allocatedBedrockPorts.remove(port)) {
            logger.info("Released Bedrock port {}", port)
        }
    }

    private fun isTcpPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun isUdpPortAvailable(port: Int): Boolean {
        return try {
            DatagramSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
