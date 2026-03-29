package dev.nimbus.console

import dev.nimbus.event.NimbusEvent
import dev.nimbus.service.ServiceState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ConsoleFormatter {

    // ── ANSI colors ─────────────────────────────────────────────
    const val RESET = "\u001B[0m"
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val BLUE = "\u001B[34m"
    const val MAGENTA = "\u001B[35m"
    const val CYAN = "\u001B[36m"
    const val GRAY = "\u001B[37m"
    const val DIM = "\u001B[2m"
    const val BOLD = "\u001B[1m"
    const val WHITE = "\u001B[97m"
    const val BRIGHT_BLUE = "\u001B[94m"
    const val BRIGHT_CYAN = "\u001B[96m"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    // ── Basic colorizers ────────────────────────────────────────

    fun colorize(text: String, color: String): String = "$color$text$RESET"

    fun success(text: String): String = colorize(text, GREEN)
    fun error(text: String): String = colorize(text, RED)
    fun warn(text: String): String = colorize(text, YELLOW)
    fun info(text: String): String = colorize(text, CYAN)

    // ── Layout helpers ──────────────────────────────────────────

    fun header(title: String, width: Int = 52): String {
        val lineLen = maxOf(0, width - title.length - 4)
        return "$CYAN── $BOLD$WHITE$title $RESET$CYAN${"─".repeat(lineLen)}$RESET"
    }

    fun separator(width: Int = 56): String =
        colorize("─".repeat(width), DIM)

    fun section(title: String): String =
        "$CYAN▸ $BOLD$title$RESET"

    // ── State formatting ────────────────────────────────────────

    fun stateIcon(state: ServiceState): String = when (state) {
        ServiceState.READY -> "${GREEN}●$RESET"
        ServiceState.STARTING -> "${YELLOW}●$RESET"
        ServiceState.PREPARING -> "${BLUE}○$RESET"
        ServiceState.STOPPING -> "${YELLOW}●$RESET"
        ServiceState.STOPPED -> "${DIM}○$RESET"
        ServiceState.CRASHED -> "${RED}●$RESET"
    }

    fun stateColor(state: ServiceState): String = when (state) {
        ServiceState.READY -> GREEN
        ServiceState.STARTING -> YELLOW
        ServiceState.PREPARING -> BLUE
        ServiceState.STOPPING -> YELLOW
        ServiceState.STOPPED -> GRAY
        ServiceState.CRASHED -> RED
    }

    fun coloredState(state: ServiceState): String =
        "${stateIcon(state)} ${colorize(state.name, stateColor(state))}"

    // ── Progress bar ────────────────────────────────────────────

    fun progressBar(current: Int, max: Int, width: Int = 30): String {
        val filled = if (max > 0) (current.toDouble() / max * width).toInt().coerceIn(0, width) else 0
        val empty = width - filled
        val color = when {
            max == 0 -> DIM
            current.toDouble() / max > 0.9 -> RED
            current.toDouble() / max > 0.7 -> YELLOW
            else -> GREEN
        }
        return "$color${"█".repeat(filled)}$DIM${"░".repeat(empty)}$RESET"
    }

    // ── Table formatting ────────────────────────────────────────

    fun formatTable(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty()) return ""

        val columnWidths = headers.indices.map { col ->
            maxOf(
                headers[col].length,
                rows.maxOfOrNull { row -> row.getOrElse(col) { "" }.stripAnsi().length } ?: 0
            )
        }

        val sb = StringBuilder()

        // Header row
        val headerLine = headers.mapIndexed { i, h ->
            colorize(h.padEnd(columnWidths[i]), "$BOLD$BRIGHT_CYAN")
        }.joinToString("  ")
        sb.appendLine(headerLine)

        // Separator
        val sep = columnWidths.joinToString("  ") { "─".repeat(it) }
        sb.appendLine(colorize(sep, DIM))

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

    // ── Event formatting ────────────────────────────────────────

    fun formatEvent(event: NimbusEvent): String {
        val time = "${DIM}[${timeFormatter.format(event.timestamp)}]$RESET"
        val message = when (event) {
            is NimbusEvent.ServiceStarting -> {
                val nodeInfo = if (event.nodeId != "local") ", node=${event.nodeId}" else ""
                "${warn("▲ STARTING")} ${BOLD}${event.serviceName}${RESET} ${DIM}(group=${event.groupName}, port=${event.port}$nodeInfo)${RESET}"
            }
            is NimbusEvent.ServiceReady ->
                "${success("● READY")} ${BOLD}${event.serviceName}${RESET} ${DIM}(group=${event.groupName})${RESET}"
            is NimbusEvent.ServiceStopping ->
                "${warn("▼ STOPPING")} ${BOLD}${event.serviceName}${RESET}"
            is NimbusEvent.ServiceStopped ->
                "${info("○ STOPPED")} ${BOLD}${event.serviceName}${RESET}"
            is NimbusEvent.ServiceCrashed ->
                "${error("✖ CRASHED")} ${BOLD}${event.serviceName}${RESET} ${DIM}(exit=${event.exitCode}, attempt=${event.restartAttempt})${RESET}"
            is NimbusEvent.ScaleUp ->
                "${success("↑ SCALE UP")} group=${BOLD}${event.groupName}${RESET} ${event.currentInstances} → ${event.targetInstances} ${DIM}(${event.reason})${RESET}"
            is NimbusEvent.ScaleDown ->
                "${warn("↓ SCALE DOWN")} ${BOLD}${event.serviceName}${RESET} ${DIM}from group=${event.groupName} (${event.reason})${RESET}"
            is NimbusEvent.PlayerConnected ->
                "${success("+")} ${BOLD}${event.playerName}${RESET} joined ${CYAN}${event.serviceName}${RESET}"
            is NimbusEvent.PlayerDisconnected ->
                "${error("−")} ${BOLD}${event.playerName}${RESET} left ${CYAN}${event.serviceName}${RESET}"
            is NimbusEvent.ApiStarted ->
                "${colorize("◆ API", BRIGHT_CYAN)} started on ${BOLD}${event.bind}:${event.port}${RESET}"
            is NimbusEvent.ApiStopped ->
                "${colorize("◇ API", CYAN)} stopped ${DIM}(${event.reason})${RESET}"
            is NimbusEvent.ApiWarning ->
                "${warn("◆ API")} ${event.message}"
            is NimbusEvent.ApiError ->
                "${error("◆ API")} ${event.error}"
            is NimbusEvent.GroupCreated ->
                "${success("+ GROUP")} ${BOLD}${event.groupName}${RESET} created"
            is NimbusEvent.GroupUpdated ->
                "${info("~ GROUP")} ${BOLD}${event.groupName}${RESET} updated"
            is NimbusEvent.GroupDeleted ->
                "${warn("- GROUP")} ${BOLD}${event.groupName}${RESET} deleted"
            is NimbusEvent.ServiceCustomStateChanged ->
                "${info("~ STATE")} ${BOLD}${event.serviceName}${RESET} ${DIM}${event.oldState ?: "-"} → ${event.newState ?: "-"}${RESET}"
            is NimbusEvent.ServiceMessage ->
                "${info("✉ MSG")} ${BOLD}${event.fromService}${RESET} → ${BOLD}${event.toService}${RESET} ${DIM}[${event.channel}]${RESET}"
            is NimbusEvent.PermissionGroupCreated ->
                "${success("+ PERM")} group ${BOLD}${event.groupName}${RESET} created"
            is NimbusEvent.PermissionGroupUpdated ->
                "${info("~ PERM")} group ${BOLD}${event.groupName}${RESET} updated"
            is NimbusEvent.PermissionGroupDeleted ->
                "${warn("- PERM")} group ${BOLD}${event.groupName}${RESET} deleted"
            is NimbusEvent.PlayerPermissionsUpdated ->
                "${info("~ PERM")} ${BOLD}${event.playerName}${RESET} ${DIM}(${event.uuid})${RESET} updated"
            is NimbusEvent.ProxyUpdateAvailable ->
                "${warn("↑ UPDATE")} Velocity ${BOLD}${event.currentVersion}${RESET} → ${BOLD}${event.newVersion}${RESET} available"
            is NimbusEvent.ProxyUpdateApplied ->
                "${success("✓ UPDATE")} Velocity updated to ${BOLD}${event.newVersion}${RESET} ${DIM}(restart proxy to apply)${RESET}"
            is NimbusEvent.TabListUpdated ->
                "${info("~ TAB")} tab list updated ${DIM}(interval=${event.updateInterval}s)${RESET}"
            is NimbusEvent.MotdUpdated ->
                "${info("~ MOTD")} MOTD updated"
            is NimbusEvent.PlayerTabUpdated ->
                "${info("~ TAB")} player ${BOLD}${event.uuid}${RESET} ${if (event.format != null) "override set" else "override cleared"}"
            is NimbusEvent.ChatFormatUpdated ->
                "${info("~ CHAT")} chat format updated ${DIM}(enabled=${event.enabled})${RESET}"
            is NimbusEvent.ConfigReloaded ->
                "${info("↻ CONFIG")} reloaded ${BOLD}${event.groupsLoaded}${RESET} group(s)"
            is NimbusEvent.ClusterStarted ->
                "${colorize("◆ CLUSTER", MAGENTA)} started on ${BOLD}${event.bind}:${event.port}${RESET} ${DIM}(${event.strategy})${RESET}"
            is NimbusEvent.NodeConnected ->
                "${colorize("◆ NODE", MAGENTA)} ${BOLD}${event.nodeId}${RESET} connected from ${DIM}${event.host}${RESET}"
            is NimbusEvent.NodeDisconnected ->
                "${warn("◇ NODE")} ${BOLD}${event.nodeId}${RESET} disconnected"
            is NimbusEvent.NodeHeartbeat ->
                "${colorize("♥ NODE", DIM)} ${event.nodeId} ${DIM}cpu=${String.format("%.0f", event.cpuUsage * 100)}% services=${event.services}${RESET}"
            is NimbusEvent.LoadBalancerStarted ->
                "${colorize("◆ LB", BRIGHT_BLUE)} started on ${BOLD}${event.bind}:${event.port}${RESET} ${DIM}(${event.strategy})${RESET}"
            is NimbusEvent.LoadBalancerStopped ->
                "${colorize("◇ LB", BLUE)} stopped ${DIM}(${event.reason})${RESET}"
        }
        return "$time $message"
    }

    // ── Utility ─────────────────────────────────────────────────

    /**
     * Strips ANSI escape sequences from a string for width calculation.
     */
    private fun String.stripAnsi(): String =
        replace(Regex("\u001B\\[[;\\d]*m"), "")

    fun formatUptime(startedAt: Instant?): String {
        if (startedAt == null) return "${DIM}-${RESET}"
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
        appendLine("""$BLUE   _  __ __ _   __ ___  _ __  ___$RESET""")
        appendLine("""$BRIGHT_BLUE  / |/ // // \,' // o.)/// /,' _/$RESET""")
        appendLine("""$CYAN / || // // \,' // o \/ U /_\ `. $RESET""")
        appendLine("""$BRIGHT_CYAN/_/|_//_//_/ /_//___,'\_,'/___,' $RESET""")
        appendLine("${DIM}            C L O U D$RESET")
        appendLine()
        if (networkName.isNotEmpty()) {
            appendLine("${DIM}Network:${RESET}  ${BOLD}$networkName${RESET}")
            appendLine("${DIM}Version:${RESET}  ${CYAN}v0.2.0${RESET}")
            appendLine("${DIM}${"─".repeat(34)}$RESET")
            appendLine()
        }
    }
}
