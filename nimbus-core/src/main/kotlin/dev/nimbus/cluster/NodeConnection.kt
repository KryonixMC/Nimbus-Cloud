package dev.nimbus.cluster

import dev.nimbus.protocol.ClusterMessage
import dev.nimbus.protocol.clusterJson
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

class NodeConnection(
    val nodeId: String,
    val host: String,
    val maxMemory: String,
    val maxServices: Int,
    private var session: DefaultWebSocketServerSession?
) {
    private val logger = LoggerFactory.getLogger(NodeConnection::class.java)
    private val sendMutex = Mutex()

    @Volatile var isConnected: Boolean = true
    @Volatile var lastHeartbeat: Long = System.currentTimeMillis()
    @Volatile var currentServices: Int = 0
    @Volatile var cpuUsage: Double = 0.0
    @Volatile var memoryUsedMb: Long = 0
    @Volatile var memoryTotalMb: Long = 0
    @Volatile var agentVersion: String = "0.1.0"
    @Volatile var os: String = ""
    @Volatile var arch: String = ""

    /** Remote service handles, keyed by service name */
    val remoteHandles = mutableMapOf<String, RemoteServiceHandle>()

    suspend fun send(message: ClusterMessage) {
        sendMutex.withLock {
            val json = clusterJson.encodeToString(ClusterMessage.serializer(), message)
            session?.send(Frame.Text(json))
        }
    }

    fun updateHeartbeat(response: ClusterMessage.HeartbeatResponse) {
        lastHeartbeat = System.currentTimeMillis()
        cpuUsage = response.cpuUsage
        memoryUsedMb = response.memoryUsedMb
        memoryTotalMb = response.memoryTotalMb
        currentServices = response.services.size
    }

    fun hasMemoryFor(memoryRequired: String): Boolean {
        val requiredMb = parseMemoryMb(memoryRequired)
        val maxMb = parseMemoryMb(maxMemory)
        return (memoryUsedMb + requiredMb) <= maxMb
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
        fun parseMemoryMb(memory: String): Long {
            val num = memory.dropLast(1).toLongOrNull() ?: return 0
            return when (memory.last().uppercaseChar()) {
                'G' -> num * 1024
                'M' -> num
                else -> num
            }
        }
    }
}
