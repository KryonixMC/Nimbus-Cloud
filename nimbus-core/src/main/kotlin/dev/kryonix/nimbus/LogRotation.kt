package dev.kryonix.nimbus

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Minecraft-style log rotation: on startup, compress the previous
 * `latest.log` to `logs/YYYY-MM-DD-N.log.gz` and start fresh.
 */
object LogRotation {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun rotate(logsDir: Path) {
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
