package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.service.ServerListPing
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState

class PlayersCommand(
    private val registry: ServiceRegistry
) : Command {

    override val name = "players"
    override val description = "List all connected players across services"
    override val usage = "players [service]"

    override suspend fun execute(args: List<String>) {
        val services = if (args.isNotEmpty()) {
            val service = registry.get(args[0])
            if (service == null) {
                println(ConsoleFormatter.error("Service '${args[0]}' not found."))
                return
            }
            listOf(service)
        } else {
            registry.getAll()
        }

        val readyServices = services.filter { it.state == ServiceState.READY }

        if (readyServices.isEmpty()) {
            println(ConsoleFormatter.warn("No ready services to query."))
            return
        }

        data class PlayerEntry(val playerName: String, val serviceName: String, val serverGroup: String)

        val players = mutableListOf<PlayerEntry>()

        for (service in readyServices) {
            val result = ServerListPing.ping("127.0.0.1", service.port) ?: continue
            for (playerName in result.playerNames) {
                players.add(PlayerEntry(playerName, service.name, service.groupName))
            }
        }

        if (players.isEmpty()) {
            println(ConsoleFormatter.emptyState("No players online."))
            return
        }

        val headers = listOf("PLAYER", "SERVICE", "SERVER")
        val rows = players.sortedBy { it.playerName.lowercase() }.map { entry ->
            listOf(
                ConsoleFormatter.colorize(entry.playerName, ConsoleFormatter.BOLD),
                entry.serviceName,
                entry.serverGroup
            )
        }

        println(ConsoleFormatter.header("Players"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(players.size, "player") + " online")
    }
}
