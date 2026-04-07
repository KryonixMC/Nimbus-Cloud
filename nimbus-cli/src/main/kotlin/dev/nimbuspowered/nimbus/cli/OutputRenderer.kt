package dev.nimbuspowered.nimbus.cli

/**
 * Renders typed output lines from the controller into ANSI-formatted console output.
 * Mirrors the output styling of ConsoleFormatter on the controller.
 */
object OutputRenderer {

    private const val RESET = "\u001B[0m"
    private const val RED = "\u001B[31m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val CYAN = "\u001B[36m"
    private const val BOLD = "\u001B[1m"
    private const val DIM = "\u001B[2m"
    private const val BRIGHT_CYAN = "\u001B[96m"

    fun render(type: String, text: String): String {
        return when (type) {
            "header" -> renderHeader(text)
            "info" -> "${CYAN}$text$RESET"
            "success" -> "${GREEN}$text$RESET"
            "error" -> "${RED}$text$RESET"
            "item" -> "  $text"
            "text" -> text // Already formatted with ANSI or plain text
            else -> text
        }
    }

    private fun renderHeader(title: String): String {
        val width = 52
        val pad = (width - title.length - 4).coerceAtLeast(0)
        val line = "─".repeat(pad)
        return "$BOLD$BRIGHT_CYAN── $title $DIM$line$RESET"
    }

    fun renderEvent(type: String, data: Map<String, String>, timestamp: String): String {
        val time = timestamp.substringAfter("T").take(8) // HH:mm:ss from ISO timestamp
        val formatted = when {
            type == "SERVICE_STARTING" -> {
                val svc = data["service"] ?: "?"
                val port = data["port"] ?: "?"
                "${YELLOW}▲${RESET} ${BOLD}STARTING${RESET} $svc ${DIM}(port=$port)${RESET}"
            }
            type == "SERVICE_READY" -> {
                val svc = data["service"] ?: "?"
                "${GREEN}●${RESET} ${GREEN}READY${RESET} $svc"
            }
            type == "SERVICE_STOPPED" -> {
                val svc = data["service"] ?: "?"
                "${DIM}○ STOPPED $svc${RESET}"
            }
            type == "SERVICE_CRASHED" -> {
                val svc = data["service"] ?: "?"
                val exit = data["exitCode"] ?: "?"
                val attempt = data["restartAttempt"] ?: "?"
                "${RED}✖${RESET} ${RED}CRASHED${RESET} $svc ${DIM}(exit=$exit, attempt=$attempt)${RESET}"
            }
            type == "SERVICE_RECOVERED" -> {
                val svc = data["service"] ?: "?"
                "${GREEN}↻${RESET} ${GREEN}RECOVERED${RESET} $svc"
            }
            type == "SCALE_UP" -> {
                val group = data["group"] ?: "?"
                val from = data["from"] ?: "?"
                val to = data["to"] ?: "?"
                val reason = data["reason"] ?: ""
                "${GREEN}↑${RESET} ${BOLD}SCALE UP${RESET} $group $from → $to ${DIM}($reason)${RESET}"
            }
            type == "SCALE_DOWN" -> {
                val svc = data["service"] ?: "?"
                val reason = data["reason"] ?: ""
                "${YELLOW}↓${RESET} ${BOLD}SCALE DOWN${RESET} $svc ${DIM}($reason)${RESET}"
            }
            type == "PLAYER_CONNECTED" -> {
                val player = data["player"] ?: "?"
                val svc = data["service"] ?: "?"
                "${GREEN}+${RESET} $player → $svc"
            }
            type == "PLAYER_DISCONNECTED" -> {
                val player = data["player"] ?: "?"
                "${RED}-${RESET} $player"
            }
            type == "NODE_CONNECTED" -> {
                val nodeId = data["nodeId"] ?: "?"
                "${GREEN}⬡${RESET} Node ${BOLD}$nodeId${RESET} connected"
            }
            type == "NODE_DISCONNECTED" -> {
                val nodeId = data["nodeId"] ?: "?"
                "${RED}⬡${RESET} Node ${BOLD}$nodeId${RESET} disconnected"
            }
            type == "MAINTENANCE_ENABLED" -> {
                val scope = data["scope"] ?: "global"
                "${YELLOW}⚠${RESET} Maintenance ${BOLD}ON${RESET} ${DIM}($scope)${RESET}"
            }
            type == "MAINTENANCE_DISABLED" -> {
                val scope = data["scope"] ?: "global"
                "${GREEN}✓${RESET} Maintenance ${BOLD}OFF${RESET} ${DIM}($scope)${RESET}"
            }
            type == "GROUP_CREATED" -> {
                "${GREEN}+${RESET} Group ${BOLD}${data["group"]}${RESET} created"
            }
            type == "GROUP_DELETED" -> {
                "${RED}-${RESET} Group ${BOLD}${data["group"]}${RESET} deleted"
            }
            type == "CLI_SESSION_CONNECTED" -> {
                val user = data["user"] ?: "?"
                val ip = data["remoteIp"] ?: "?"
                val sid = data["sessionId"] ?: "?"
                "${BRIGHT_CYAN}◆${RESET} CLI ${BOLD}$user${RESET} connected from ${CYAN}$ip${RESET} ${DIM}(#$sid)${RESET}"
            }
            type == "CLI_SESSION_DISCONNECTED" -> {
                val user = data["user"] ?: "?"
                val ip = data["remoteIp"] ?: "?"
                val dur = data["durationSeconds"]?.toLongOrNull()?.let { formatDuration(it) } ?: "?"
                val cmds = data["commandCount"] ?: "0"
                "${CYAN}◇${RESET} CLI ${BOLD}$user${RESET} disconnected ${DIM}($ip, $dur, $cmds cmds)${RESET}"
            }
            type == "MODULE_LOADED" -> {
                val name = data["moduleName"] ?: "?"
                "${CYAN}◆${RESET} Module ${BOLD}$name${RESET} loaded"
            }
            else -> {
                val details = data.entries.joinToString(", ") { "${it.key}=${it.value}" }
                "${DIM}$type${RESET}${if (details.isNotEmpty()) " $DIM($details)$RESET" else ""}"
            }
        }
        return "${DIM}[$time]${RESET} $formatted"
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }
}
