package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.protocol.clusterJson
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

class NodeConnection(
    val nodeId: String,
    host: String,
    val maxMemory: String,
    val maxServices: Int,
    private var session: DefaultWebSocketServerSession?
) {
    // Mutable so the agent can update its advertised public_host on reconnect
    // (e.g. after an IP change or a config reload).
    @Volatile var host: String = host
        private set
    private val logger = LoggerFactory.getLogger(NodeConnection::class.java)
    private val sendMutex = Mutex()

    @Volatile var isConnected: Boolean = true
    @Volatile var lastHeartbeat: Long = System.currentTimeMillis()
    @Volatile var currentServices: Int = 0
    @Volatile var cpuUsage: Double = 0.0
    @Volatile var processCpuLoad: Double = -1.0
    @Volatile var memoryUsedMb: Long = 0
    @Volatile var memoryTotalMb: Long = 0
    /** Sum of RSS across all services running on this node. Used for placement decisions. */
    @Volatile var servicesUsedMb: Long = 0
    @Volatile var agentVersion: String = "dev"
    @Volatile var os: String = ""
    @Volatile var arch: String = ""
    // Static host system specs (populated at auth time)
    @Volatile var hostname: String = ""
    @Volatile var osVersion: String = ""
    @Volatile var cpuModel: String = ""
    @Volatile var availableProcessors: Int = 0
    @Volatile var systemMemoryTotalMb: Long = 0
    @Volatile var javaVersion: String = ""
    @Volatile var javaVendor: String = ""

    /** Remote service handles, keyed by service name */
    val remoteHandles = mutableMapOf<String, RemoteServiceHandle>()

    suspend fun send(message: ClusterMessage) {
        sendMutex.withLock {
            val json = clusterJson.encodeToString(ClusterMessage.serializer(), message)
            session?.send(Frame.Text(json))
        }
    }

    fun applyAuthInfo(auth: ClusterMessage.AuthRequest) {
        agentVersion = auth.agentVersion
        os = auth.os
        arch = auth.arch
        hostname = auth.hostname
        osVersion = auth.osVersion
        cpuModel = auth.cpuModel
        availableProcessors = auth.availableProcessors
        systemMemoryTotalMb = auth.systemMemoryTotalMb
        javaVersion = auth.javaVersion
        javaVendor = auth.javaVendor
        if (auth.publicHost.isNotBlank()) host = auth.publicHost
    }

    fun updateHeartbeat(response: ClusterMessage.HeartbeatResponse) {
        lastHeartbeat = System.currentTimeMillis()
        cpuUsage = response.cpuUsage
        processCpuLoad = response.processCpuLoad
        memoryUsedMb = response.memoryUsedMb
        memoryTotalMb = response.memoryTotalMb
        currentServices = response.services.size
        servicesUsedMb = response.services.sumOf { it.memoryUsedMb }
    }

    /**
     * Checks whether this node has budget for another service of the given size.
     *
     * Two separate constraints:
     *  1. Service budget: the configured `max_memory` minus the sum of running
     *     service RSS. This is what the operator actually promised the agent may use.
     *  2. System-RAM safety: the host must still have at least `requiredMb` of free
     *     RAM left, so a misconfigured `max_memory` can't OOM the host.
     */
    fun hasMemoryFor(memoryRequired: String): Boolean {
        val requiredMb = parseMemoryMb(memoryRequired)
        val maxMb = parseMemoryMb(maxMemory)
        if ((servicesUsedMb + requiredMb) > maxMb) return false
        if (memoryTotalMb > 0) {
            val systemFreeMb = (memoryTotalMb - memoryUsedMb).coerceAtLeast(0)
            if (systemFreeMb < requiredMb) return false
        }
        return true
    }

    fun markDisconnected() {
        isConnected = false
        session = null
    }

    fun reconnect(newSession: DefaultWebSocketServerSession) {
        session = newSession
        isConnected = true
        lastHeartbeat = System.currentTimeMillis()
    }

    companion object {
        private val log = LoggerFactory.getLogger(NodeConnection::class.java)

        fun parseMemoryMb(memory: String): Long {
            if (memory.length < 2) {
                log.warn("Invalid memory format '{}': expected format like '512M' or '2G'", memory)
                return 0
            }
            val num = memory.dropLast(1).toLongOrNull()
            if (num == null) {
                log.warn("Invalid memory format '{}': expected format like '512M' or '2G'", memory)
                return 0
            }
            return when (memory.last().uppercaseChar()) {
                'G' -> num * 1024
                'M' -> num
                else -> {
                    log.warn("Unknown memory suffix '{}' in '{}': expected 'M' or 'G'", memory.last(), memory)
                    num
                }
            }
        }
    }
}
