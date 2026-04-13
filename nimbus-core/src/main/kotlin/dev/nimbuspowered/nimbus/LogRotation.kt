package dev.nimbuspowered.nimbus

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import org.slf4j.LoggerFactory

/**
 * Minecraft-style log rotation: on startup, compress the previous
 * `latest.log` to `logs/YYYY-MM-DD-N.log.gz` and start fresh.
 */
object LogRotation {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val logger = LoggerFactory.getLogger(LogRotation::class.java)

    fun rotate(logsDir: Path, maxArchives: Int = 30) {
        val latestLog = logsDir.resolve("latest.log")
        if (!latestLog.exists() || latestLog.fileSize() == 0L) return

        if (!logsDir.exists()) Files.createDirectories(logsDir)

        val today = LocalDate.now().format(DATE_FORMAT)
        val archiveName = nextAvailableName(logsDir, today)
        val archivePath = logsDir.resolve(archiveName)

        // Compress latest.log → YYYY-MM-DD-N.log.gz
        BufferedInputStream(Files.newInputStream(latestLog)).use { input ->
            GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(archivePath))).use { gzip ->
                input.copyTo(gzip)
            }
        }

        // Truncate latest.log so logback starts fresh
        Files.newOutputStream(latestLog, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).close()

        // Enforce retention: keep only the newest maxArchives .log.gz files
        if (maxArchives > 0) {
            pruneOldArchives(logsDir, maxArchives)
        }
    }

    private fun pruneOldArchives(logsDir: Path, maxArchives: Int) {
        try {
            val archives = Files.list(logsDir).use { stream ->
                stream.filter { it.toString().endsWith(".log.gz") }
                    .sorted(Comparator.comparing<Path, java.nio.file.attribute.FileTime> { Files.getLastModifiedTime(it) }.reversed())
                    .toList()
            }
            if (archives.size > maxArchives) {
                val toDelete = archives.drop(maxArchives)
                for (file in toDelete) {
                    Files.deleteIfExists(file)
                }
                logger.info("Log retention: deleted {} old archive(s)", toDelete.size)
            }
        } catch (e: Exception) {
            logger.warn("Failed to prune old log archives: {}", e.message)
        }
    }

    private fun nextAvailableName(logsDir: Path, date: String): String {
        var n = 1
        while (true) {
            val name = "$date-$n.log.gz"
            if (!logsDir.resolve(name).exists()) return name
            n++
        }
    }
}
