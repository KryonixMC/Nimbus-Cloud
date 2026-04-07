package dev.nimbuspowered.nimbus.service

import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * Abstraction over a running service process.
 * - ProcessHandle: local process (existing implementation)
 * - RemoteServiceHandle: proxy to a service running on a remote agent node
 */
interface ServiceHandle {
    val stdoutLines: SharedFlow<String>
    fun isAlive(): Boolean
    fun pid(): Long?
    fun exitCode(): Int?
    suspend fun sendCommand(command: String)
    suspend fun waitForReady(timeout: Duration): Boolean
    suspend fun stopGracefully(timeout: Duration)
    suspend fun awaitExit(): Int?
    fun destroy()
}
