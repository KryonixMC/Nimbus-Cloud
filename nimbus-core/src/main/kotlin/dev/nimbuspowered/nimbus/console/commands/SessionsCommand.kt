package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.database.CliSessionTracker
import dev.nimbuspowered.nimbus.module.CommandOutput
import java.time.Duration
import java.time.Instant

class SessionsCommand(
    private val tracker: CliSessionTracker
) : Command {

    override val name = "sessions"
    override val description = "Show Remote CLI session history"
    override val usage = "sessions [active|history] [--limit <n>]"

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val subcommand = args.firstOrNull()?.lowercase() ?: "active"
        val limit = parseLimitArg(args)

        when (subcommand) {
            "active" -> showActive(output)
            "history" -> showHistory(output, limit)
            else -> {
                output.error("Usage: $usage")
                output.info("  sessions active   — Show currently connected CLI sessions")
                output.info("  sessions history  — Show recent session history")
            }
        }
        return true
    }

    private suspend fun showActive(output: CommandOutput) {
        val active = tracker.getActiveSessions()

        output.header("Active CLI Sessions")

        if (active.isEmpty()) {
            output.info("No active CLI sessions.")
            return
        }

        val headers = listOf("ID", "IP", "USER", "CONNECTED", "DURATION")
        val rows = active.map { session ->
            val connectedAt = try {
                Instant.parse(session.connectedAt)
            } catch (_: Exception) { null }

            val duration = if (connectedAt != null) {
                formatDuration(Duration.between(connectedAt, Instant.now()).seconds)
            } else "?"

            val timeStr = formatTimestamp(session.connectedAt)

            listOf(
                ConsoleFormatter.colorize("#${session.sessionId}", ConsoleFormatter.BOLD),
                session.remoteIp,
                ConsoleFormatter.colorize(session.authenticatedAs, ConsoleFormatter.CYAN),
                timeStr,
                ConsoleFormatter.colorize(duration, ConsoleFormatter.GREEN)
            )
        }

        output.text(ConsoleFormatter.formatTable(headers, rows))
        output.text(ConsoleFormatter.count(active.size, "active session"))
    }

    private suspend fun showHistory(output: CommandOutput, limit: Int) {
        val sessions = tracker.getRecentSessions(limit)

        output.header("CLI Session History")

        if (sessions.isEmpty()) {
            output.info("No CLI sessions recorded.")
            return
        }

        val headers = listOf("ID", "IP", "USER", "CONNECTED", "DURATION", "CMDS", "STATUS")
        val rows = sessions.map { session ->
            val timeStr = formatTimestamp(session.connectedAt)
            val duration = if (session.durationSeconds != null) {
                formatDuration(session.durationSeconds)
            } else {
                // Still active
                val connectedAt = try {
                    Instant.parse(session.connectedAt)
                } catch (_: Exception) { null }
                if (connectedAt != null) {
                    formatDuration(Duration.between(connectedAt, Instant.now()).seconds)
                } else "?"
            }

            val status = if (session.disconnectedAt == null) {
                ConsoleFormatter.colorize("● online", ConsoleFormatter.GREEN)
            } else {
                "${ConsoleFormatter.DIM}○ closed${ConsoleFormatter.RESET}"
            }

            listOf(
                ConsoleFormatter.colorize("#${session.sessionId}", ConsoleFormatter.BOLD),
                session.remoteIp,
                ConsoleFormatter.colorize(session.authenticatedAs, ConsoleFormatter.CYAN),
                timeStr,
                duration,
                session.commandCount.toString(),
                status
            )
        }

        output.text(ConsoleFormatter.formatTable(headers, rows))
        output.text(ConsoleFormatter.count(sessions.size, "session"))
    }

    private fun formatTimestamp(iso: String): String {
        return try {
            val instant = Instant.parse(iso)
            val formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(instant)
        } catch (_: Exception) {
            iso.take(16)
        }
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }

    private fun parseLimitArg(args: List<String>): Int {
        val idx = args.indexOf("--limit")
        if (idx >= 0 && idx + 1 < args.size) {
            return args[idx + 1].toIntOrNull()?.coerceIn(1, 100) ?: 20
        }
        return 20
    }
}
