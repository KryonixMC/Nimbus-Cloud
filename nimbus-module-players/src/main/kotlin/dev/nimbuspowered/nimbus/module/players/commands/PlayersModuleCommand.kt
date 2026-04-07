package dev.nimbuspowered.nimbus.module.players.commands

import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.GREEN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.module.ModuleCommand
import dev.nimbuspowered.nimbus.module.players.PlayerTracker
import java.time.Duration
import java.time.Instant

class PlayersModuleCommand(private val tracker: PlayerTracker) : ModuleCommand {
    override val name = "players"
    override val description = "Player tracking and management"
    override val usage = "players [list|info <name>|history <name>|stats]"
    override val permission = "nimbus.players"

    override suspend fun execute(args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "list"

        when (sub) {
            "list" -> {
                val service = args.getOrNull(1)
                val players = if (service != null) {
                    tracker.getPlayersOnService(service)
                } else {
                    tracker.getOnlinePlayers().toList()
                }

                if (players.isEmpty()) {
                    println(ConsoleFormatter.info("No players online" + if (service != null) " on $service" else ""))
                    return
                }

                println(ConsoleFormatter.header("Online Players (${players.size})"))
                for (p in players.sortedBy { it.currentService }) {
                    val duration = formatDuration(Duration.between(p.connectedAt, Instant.now()))
                    println("  $BOLD${p.name}$RESET ${DIM}on$RESET $CYAN${p.currentService}$RESET ${DIM}(${duration})$RESET")
                }
            }

            "info" -> {
                val name = args.getOrNull(1) ?: run {
                    println(ConsoleFormatter.error("Usage: players info <name>"))
                    return
                }
                val online = tracker.getPlayerByName(name)
                val meta = if (online != null) {
                    tracker.getPlayerMeta(online.uuid)
                } else {
                    null
                }

                if (online != null) {
                    println(ConsoleFormatter.header("Player: ${online.name}"))
                    println("  UUID: $BOLD${online.uuid}$RESET")
                    println("  Service: $CYAN${online.currentService}$RESET (${online.currentGroup})")
                    println("  Status: ${GREEN}Online$RESET")
                    val duration = formatDuration(Duration.between(online.connectedAt, Instant.now()))
                    println("  Session: $duration")
                    if (meta != null) {
                        println("  First seen: ${meta["firstSeen"]}")
                        println("  Total playtime: ${formatDuration(Duration.ofSeconds(meta["totalPlaytimeSeconds"]!!.toLong()))}")
                    }
                } else {
                    println(ConsoleFormatter.warn("Player '$name' is not online"))
                }
            }

            "history" -> {
                val name = args.getOrNull(1) ?: run {
                    println(ConsoleFormatter.error("Usage: players history <name>"))
                    return
                }
                val online = tracker.getPlayerByName(name)
                val uuid = online?.uuid ?: run {
                    println(ConsoleFormatter.warn("Player '$name' is not online (history requires UUID lookup)"))
                    return
                }
                val history = tracker.getSessionHistory(uuid, 10)
                if (history.isEmpty()) {
                    println(ConsoleFormatter.info("No session history for $name"))
                    return
                }
                println(ConsoleFormatter.header("Session History: $name (last 10)"))
                for (entry in history) {
                    val disconnected = entry["disconnectedAt"] ?: "${GREEN}active$RESET"
                    println("  ${entry["service"]} ${DIM}(${entry["group"]})$RESET  ${entry["connectedAt"]} → $disconnected")
                }
            }

            "stats" -> {
                val stats = tracker.getStats()
                println(ConsoleFormatter.header("Player Stats"))
                println("  Online: $BOLD${stats["online"]}$RESET")
                println("  Total unique: $BOLD${stats["totalUnique"]}$RESET")
                @Suppress("UNCHECKED_CAST")
                val perService = stats["perService"] as? Map<String, Int> ?: emptyMap()
                if (perService.isNotEmpty()) {
                    println("  Per service:")
                    for ((svc, count) in perService) {
                        println("    $CYAN$svc$RESET: $count")
                    }
                }
            }

            else -> {
                println(ConsoleFormatter.error("Unknown subcommand: $sub"))
                println(ConsoleFormatter.info("Usage: $usage"))
            }
        }
    }

    private fun formatDuration(d: Duration): String {
        val hours = d.toHours()
        val minutes = d.toMinutesPart()
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${d.seconds}s"
        }
    }
}
