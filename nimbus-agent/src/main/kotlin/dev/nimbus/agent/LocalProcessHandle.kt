package dev.nimbus.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Path
import kotlin.time.Duration

class LocalProcessHandle {

    private val logger = LoggerFactory.getLogger(LocalProcessHandle::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null

    private val _stdoutLines = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val stdoutLines: SharedFlow<String> = _stdoutLines.asSharedFlow()

    private var donePattern = Regex("""Done \(""")

    fun setReadyPattern(pattern: Regex) {
        donePattern = pattern
    }

    fun start(workDir: Path, command: List<String>) {
        logger.info("Starting process in {} with command: {}", workDir, command)
        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
        process = pb.start()
        stdinWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream))

        scope.launch {
            try {
                process!!.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        _stdoutLines.emit(line)
                    }
                }
            } catch (e: Exception) {
                logger.warn("stdout reader terminated: {}", e.message)
            }
        }
    }

    suspend fun sendCommand(command: String) {
        withContext(Dispatchers.IO) {
            stdinWriter?.let { writer ->
                writer.write(command)
                writer.newLine()
                writer.flush()
                logger.debug("Sent command: {}", command)
            } ?: logger.warn("Cannot send command, process stdin is not available")
        }
    }

    suspend fun waitForReady(timeout: Duration): Boolean {
        return try {
            withTimeout(timeout.inWholeMilliseconds) {
                stdoutLines.first { line -> donePattern.containsMatchIn(line) }
                true
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn("Timed out waiting for server ready after {}", timeout)
            false
        }
    }

    suspend fun stopGracefully(timeout: Duration) {
        logger.info("Initiating graceful stop with timeout {}", timeout)
        try {
            sendCommand("stop")
            val exited = withContext(Dispatchers.IO) {
                process?.waitFor(timeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: true
            }
            if (!exited) {
                logger.warn("Process did not stop within timeout, force killing")
                process?.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.error("Error during graceful stop, force killing", e)
            process?.destroyForcibly()
        }
    }

    fun isAlive(): Boolean = process?.isAlive ?: false

    fun pid(): Long? = try {
        process?.pid()
    } catch (_: UnsupportedOperationException) {
        null
    }

    fun exitCode(): Int? = try {
        process?.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    suspend fun awaitExit(): Int? {
        return withContext(Dispatchers.IO) {
            process?.onExit()?.await()
            exitCode()
        }
    }

    fun destroy() {
        logger.info("Destroying process handle")
        scope.cancel()
        stdinWriter?.runCatching { close() }
        process?.destroyForcibly()
    }
}
