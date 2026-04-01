package dev.kryonix.nimbus.cluster

import dev.kryonix.nimbus.protocol.ClusterMessage
import dev.kryonix.nimbus.service.ServiceHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration

class RemoteServiceHandle(
    private val serviceName: String,
    private val nodeConnection: NodeConnection
) : ServiceHandle {

    private val logger = LoggerFactory.getLogger(RemoteServiceHandle::class.java)

    private val _stdoutLines = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val stdoutLines: SharedFlow<String> = _stdoutLines.asSharedFlow()

    @Volatile private var alive = true
    @Volatile private var remotePid: Long? = null
    @Volatile private var remoteExitCode: Int? = null

    private val readyDeferred = CompletableDeferred<Boolean>()
    private val exitDeferred = CompletableDeferred<Int?>()

    fun setReadyPattern(pattern: Regex) { /* not used for remote — agent handles ready detection */ }

    /** Called by ClusterWebSocketHandler when stdout arrives from agent */
    suspend fun onStdoutLine(line: String) {
        _stdoutLines.emit(line)
    }

    /** Called by ClusterWebSocketHandler when state changes from agent */
    fun onStateChanged(state: String, pid: Long) {
        remotePid = pid
        when (state) {
            "READY" -> {
                alive = true
                if (!readyDeferred.isCompleted) readyDeferred.complete(true)
            }
            "STOPPED", "CRASHED" -> {
                alive = false
                remoteExitCode = if (state == "CRASHED") -1 else 0
                if (!readyDeferred.isCompleted) readyDeferred.complete(false)
                if (!exitDeferred.isCompleted) exitDeferred.complete(remoteExitCode)
            }
        }
    }

    override fun isAlive(): Boolean = alive
    override fun pid(): Long? = remotePid
    override fun exitCode(): Int? = remoteExitCode

    override suspend fun sendCommand(command: String) {
        nodeConnection.send(ClusterMessage.SendCommand(serviceName, command))
    }

    override suspend fun waitForReady(timeout: Duration): Boolean {
        return try {
            withTimeout(timeout.inWholeMilliseconds) {
                readyDeferred.await()
            }
        } catch (_: Exception) {
            logger.warn("Timed out waiting for remote service '{}' to be ready", serviceName)
            false
        }
    }

    override suspend fun stopGracefully(timeout: Duration) {
        nodeConnection.send(ClusterMessage.StopService(
            serviceName = serviceName,
            timeoutSeconds = timeout.inWholeSeconds.toInt()
        ))
    }

    override suspend fun awaitExit(): Int? = exitDeferred.await()

    override fun destroy() {
        alive = false
        if (!exitDeferred.isCompleted) exitDeferred.complete(null)
    }
}
