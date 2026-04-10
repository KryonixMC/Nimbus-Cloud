package dev.nimbuspowered.nimbus.service

import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Reads live resident memory (RSS) for an arbitrary process by PID.
 *
 *  - Linux / WSL: reads `/proc/<pid>/status` and parses the `VmRSS:` field.
 *  - Windows: shells out to `tasklist /FI "PID eq <pid>" /FO CSV /NH` and
 *    parses the "Mem Usage" column.
 *  - Other OSes: returns `null`.
 *
 * Used to populate memory stats for services that don't have the Nimbus SDK
 * deployed (modded backends, dedicated services, custom JARs).
 */
object ProcessMemoryReader {

    private val logger = LoggerFactory.getLogger(ProcessMemoryReader::class.java)

    private val isWindows: Boolean by lazy {
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }

    /** Resident set size of the process in MB, or `null` if unavailable. */
    fun readRssMb(pid: Long): Long? {
        if (pid <= 0) return null
        // Prefer /proc if present (Linux, WSL, Docker) — fastest and most reliable
        val procStatus = Paths.get("/proc/$pid/status")
        if (procStatus.exists()) {
            return readFromProc(pid, procStatus.toString())
        }
        if (isWindows) {
            return readFromTasklist(pid)
        }
        return null
    }

    private fun readFromProc(pid: Long, path: String): Long? = try {
        val line = Paths.get(path).readLines().firstOrNull { it.startsWith("VmRSS:") }
        if (line == null) {
            logger.debug("No VmRSS line in /proc/{}/status", pid)
            null
        } else {
            val parts = line.substringAfter(":").trim().split(Regex("\\s+"))
            val kb = parts.firstOrNull()?.toLongOrNull()
            if (kb == null) {
                logger.debug("Could not parse VmRSS from '{}' for pid {}", line, pid)
                null
            } else {
                kb / 1024
            }
        }
    } catch (e: Exception) {
        logger.debug("Failed to read /proc/{}/status: {}", pid, e.message)
        null
    }

    /**
     * Windows fallback. Example output:
     * `"java.exe","12345","Console","1","1,234,567 K"`
     */
    private fun readFromTasklist(pid: Long): Long? {
        return try {
            val process = ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.debug("tasklist timed out for pid {}", pid)
                return null
            }
            if (output.isEmpty() || output.startsWith("INFO")) {
                // "INFO: No tasks..." → process not found
                return null
            }
            // Parse CSV: last field is memory "X,XXX,XXX K" (with commas as thousand separators)
            val fields = output.split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
                .map { it.trim().removePrefix("\"").removeSuffix("\"") }
            val memField = fields.lastOrNull() ?: return null
            val kb = memField.removeSuffix("K").trim().replace(",", "").replace(".", "").toLongOrNull()
            kb?.let { it / 1024 }
        } catch (e: Exception) {
            logger.debug("Failed to run tasklist for pid {}: {}", pid, e.message)
            null
        }
    }
}
