package dev.nimbus.console

import dev.nimbus.event.NimbusEvent
import dev.nimbus.service.ServiceState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ConsoleFormatter {

    // ANSI color constants
    const val RESET = "\u001B[0m"
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val BLUE = "\u001B[34m"
    const val CYAN = "\u001B[36m"
    const val GRAY = "\u001B[37m"
    const val DIM = "\u001B[2m"
    const val BOLD = "\u001B[1m"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun colorize(text: String, color: String): String = "$color$text$RESET"

    fun success(text: String): String = colorize(text, GREEN)
    fun error(text: String): String = colorize(text, RED)
    fun warn(text: String): String = colorize(text, YELLOW)
    fun info(text: String): String = colorize(text, CYAN)

    fun stateColor(state: ServiceState): String = when (state) {
        ServiceState.READY -> GREEN
        ServiceState.STARTING -> YELLOW
        ServiceState.PREPARING -> BLUE
        ServiceState.STOPPING -> YELLOW
        ServiceState.STOPPED -> GRAY
        ServiceState.CRASHED -> RED
    }

    fun coloredState(state: ServiceState): String = colorize(state.name, stateColor(state))

    fun formatTable(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty()) return ""

        // Calculate column widths
        val columnWidths = headers.indices.map { col ->
            maxOf(
                headers[col].length,
                rows.maxOfOrNull { row -> row.getOrElse(col) { "" }.stripAnsi().length } ?: 0
            )
        }

        val sb = StringBuilder()

        // Header row
        val headerLine = headers.mapIndexed { i, h ->
            colorize(h.padEnd(columnWidths[i]), BOLD)
        }.joinToString("  ")
        sb.appendLine(headerLine)

        // Separator
        val separator = columnWidths.joinToString("  ") { "-".repeat(it) }
        sb.appendLine(colorize(separator, DIM))

        // Data rows
        for (row in rows) {
            val line = row.mapIndexed { i, cell ->
                val stripped = cell.stripAnsi()
                val padding = columnWidths[i] - stripped.length
                cell + " ".repeat(maxOf(0, padding))
            }.joinToString("  ")
            sb.appendLine(line)
        }

        return sb.toString().trimEnd()
    }

    fun formatEvent(event: NimbusEvent): String {
        val time = colorize(timeFormatter.format(event.timestamp), DIM)
        val prefix = colorize("[EVENT]", CYAN)
        val message = when (event) {
            is NimbusEvent.ServiceStarting ->
                "${warn("STARTING")} ${BOLD}${event.serviceName}${RESET} (group=${event.groupName}, port=${event.port})"
            is NimbusEvent.ServiceReady ->
                "${success("READY")} ${BOLD}${event.serviceName}${RESET} (group=${event.groupName})"
            is NimbusEvent.ServiceStopping ->
                "${warn("STOPPING")} ${BOLD}${event.serviceName}${RESET}"
            is NimbusEvent.ServiceStopped ->
                "${info("STOPPED")} ${BOLD}${event.serviceName}${RESET}"
            is NimbusEvent.ServiceCrashed ->
                "${error("CRASHED")} ${BOLD}${event.serviceName}${RESET} (exit=${event.exitCode}, attempt=${event.restartAttempt})"
            is NimbusEvent.ScaleUp ->
                "${success("SCALE UP")} group=${BOLD}${event.groupName}${RESET} ${event.currentInstances} -> ${event.targetInstances} (${event.reason})"
            is NimbusEvent.ScaleDown ->
                "${warn("SCALE DOWN")} ${BOLD}${event.serviceName}${RESET} from group=${event.groupName} (${event.reason})"
            is NimbusEvent.PlayerConnected ->
                "${success("+")} ${BOLD}${event.playerName}${RESET} joined ${event.serviceName}"
            is NimbusEvent.PlayerDisconnected ->
                "${error("-")} ${BOLD}${event.playerName}${RESET} left ${event.serviceName}"
        }
        return "$time $prefix $message"
    }

    /**
     * Strips ANSI escape sequences from a string for width calculation.
     */
    private fun String.stripAnsi(): String =
        replace(Regex("\u001B\\[[;\\d]*m"), "")

    fun formatUptime(startedAt: Instant?): String {
        if (startedAt == null) return "-"
        val seconds = java.time.Duration.between(startedAt, Instant.now()).seconds
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun formatMemory(memoryString: String): String = memoryString

    fun banner(networkName: String): String = buildString {
        appendLine()
        appendLine(colorize("               ___  _  __", GRAY))
        appendLine(colorize("            .-~   ~~ ~  ~-.", GRAY))
        appendLine(colorize("          .~                ~.", GRAY))
        appendLine(colorize("        .~   ", GRAY) + colorize("N I M B U S", GREEN) + colorize("    ~.", GRAY))
        appendLine(colorize("    .--~                      ~--.", GRAY))
        appendLine(colorize("   (______________________________ )", GRAY))
        appendLine()
        appendLine(colorize("    The lightweight Minecraft cloud", BOLD) + "   " + colorize("v0.1.0", DIM))
        appendLine(colorize("    Network: $networkName", DIM))
        appendLine()
    }
}
