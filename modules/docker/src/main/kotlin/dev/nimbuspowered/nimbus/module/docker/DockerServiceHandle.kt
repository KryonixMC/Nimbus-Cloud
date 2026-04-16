package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.service.ServiceHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Path
import kotlin.time.Duration

/**
 * [ServiceHandle] implementation backed by a Docker container.
 *
 * Container lifecycle:
 *   create → attach (hijacked stdin+stdout) → start → stream stdout into [_stdoutLines]
 *   → stop (write "stop" to stdin) or stopContainer (SIGTERM/KILL) → remove
 *
 * The attach connection is the single source of truth for I/O:
 *   - reads from stdin of the container go through [stdinWriter]
 *   - writes to stdout of the container are split on `\n` and emitted to [_stdoutLines]
 */
class DockerServiceHandle(
    private val client: DockerClient,
    private val serviceName: String,
    val containerId: String,
    val containerName: String
) : ServiceHandle {

    private val logger = LoggerFactory.getLogger(DockerServiceHandle::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _stdoutLines = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val stdoutLines: SharedFlow<String> = _stdoutLines.asSharedFlow()

    private var donePattern: Regex = Regex("""Done \(""")

    private var attachStream: DockerStream? = null
    private var stdinWriter: BufferedWriter? = null

    private val exitDeferred = CompletableDeferred<Int?>()

    fun setReadyPattern(pattern: Regex) {
        donePattern = pattern
    }

    /**
     * Wires the attach connection and starts the container. Called by the factory
     * when creating a brand-new container — attaches first so early stdout isn't
     * lost, then issues `startContainer`.
     */
    fun startAndAttach() {
        attach()
        client.startContainer(containerId)
        logger.info("Started Docker container '{}' ({}) for service '{}'",
            containerName, containerId.take(12), serviceName)
        launchExitWatcher()
    }

    /**
     * Reattaches to an already-running container (used during crash recovery on
     * controller restart). Opens a fresh stdio stream and resumes the exit
     * watcher — the container itself is left untouched.
     */
    fun reattach() {
        attach()
        logger.info("Reattached to Docker container '{}' ({}) for service '{}'",
            containerName, containerId.take(12), serviceName)
        launchExitWatcher()
    }

    private fun attach() {
        val stream = client.attach(containerId)
        attachStream = stream
        stdinWriter = BufferedWriter(OutputStreamWriter(stream.output, Charsets.UTF_8))

        scope.launch {
            try {
                val reader = stream.input.bufferedReader(Charsets.UTF_8)
                reader.useLines { lines ->
                    for (line in lines) {
                        _stdoutLines.emit(line)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Attach stream for '{}' ended: {}", serviceName, e.message)
            }
        }
    }

    /**
     * Polls the container state until it exits, then resolves [exitDeferred].
     * Lightweight and decoupled from the attach stream — survives stream drops.
     */
    private fun launchExitWatcher() {
        scope.launch {
            try {
                while (isActive) {
                    val state = inspectState()
                    if (state == null || state.running == false) {
                        exitDeferred.complete(state?.exitCode)
                        break
                    }
                    delay(1_000)
                }
            } catch (e: Exception) {
                logger.debug("Exit watcher for '{}' ended: {}", serviceName, e.message)
                if (!exitDeferred.isCompleted) exitDeferred.complete(null)
            }
        }
    }

    /** Live memory stats (bytes) and CPU% from the Docker daemon. Null if unavailable. */
    fun liveStats(): DockerStats? = runCatching { client.stats(containerId) }.getOrNull()

    override suspend fun sendCommand(command: String) {
        withContext(Dispatchers.IO) {
            val w = stdinWriter
            if (w == null) {
                logger.warn("Cannot send command to '{}': attach stdin is not available", serviceName)
                return@withContext
            }
            try {
                w.write(command)
                w.newLine()
                w.flush()
                logger.debug("Sent command '{}' to container '{}'", command, containerName)
            } catch (e: Exception) {
                logger.warn("Failed to write to container stdin for '{}': {}", serviceName, e.message)
            }
        }
    }

    override suspend fun waitForReady(timeout: Duration): Boolean {
        return try {
            withTimeout(timeout.inWholeMilliseconds) {
                stdoutLines.first { line -> donePattern.containsMatchIn(line) }
                true
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn("Timed out waiting for container '{}' ready after {}", containerName, timeout)
            false
        }
    }

    override suspend fun stopGracefully(timeout: Duration) {
        logger.info("Gracefully stopping container '{}' (timeout {})", containerName, timeout)
        try {
            // Write the MC/Velocity stop command first; give the server a chance to
            // persist world state before Docker sends SIGTERM.
            runCatching { sendCommand("stop") }

            val exited = withTimeoutOrNull(timeout.inWholeMilliseconds) {
                exitDeferred.await()
                true
            } ?: false

            if (!exited) {
                logger.warn("Container '{}' did not exit within {} — stopping via daemon", containerName, timeout)
                val secs = timeout.inWholeSeconds.coerceAtLeast(1L).toInt()
                runCatching { client.stopContainer(containerId, timeoutSeconds = secs) }
            }
        } catch (e: Exception) {
            logger.error("Error during graceful stop of '{}'", containerName, e)
            runCatching { client.killContainer(containerId) }
        }
    }

    override fun isAlive(): Boolean {
        return try {
            inspectState()?.running == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the container process's host PID (from Docker inspect). For
     * rootless / restricted Docker setups this may be 0; callers treat null/0 as
     * "unknown pid" — the name+container-id is the real identifier anyway.
     */
    override fun pid(): Long? {
        return try {
            inspectState()?.pid?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    override fun exitCode(): Int? {
        return try {
            val state = inspectState() ?: return null
            if (state.running == true) null else state.exitCode
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun awaitExit(): Int? {
        return exitDeferred.await()
    }

    override fun destroy() {
        logger.info("Destroying Docker container handle for '{}'", containerName)
        scope.cancel()
        runCatching { stdinWriter?.close() }
        runCatching { attachStream?.close() }
        // Best-effort removal — ignore errors (container may already be gone).
        runCatching { client.removeContainer(containerId, force = true, volumes = false) }
        if (!exitDeferred.isCompleted) exitDeferred.complete(null)
    }

    private data class ContainerState(
        val running: Boolean?,
        val exitCode: Int?,
        val pid: Long?
    )

    private fun inspectState(): ContainerState? {
        val info = client.inspect(containerId) ?: return null
        val state = info["State"]?.jsonObject ?: return null
        return ContainerState(
            running = state["Running"]?.jsonPrimitive?.booleanOrNull,
            exitCode = state["ExitCode"]?.jsonPrimitive?.intOrNull,
            pid = state["Pid"]?.jsonPrimitive?.longOrNull
        )
    }

    /** Container path where the service work dir is bind-mounted. */
    val workDirInContainer: String = "/server"
}
