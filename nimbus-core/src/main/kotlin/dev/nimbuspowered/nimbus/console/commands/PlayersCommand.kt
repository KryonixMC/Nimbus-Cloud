package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServerListPing
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState

class PlayersCommand(
    private val registry: ServiceRegistry
) : Command {

    override val name = "players"
    override val description = "List all connected players across services"
    override val usage = "players [service]"

    private data class PlayerEntry(val playerName: String, val serviceName: String, val serverGroup: String)

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val services = if (args.isNotEmpty()) {
            val service = registry.get(args[0])
            if (service == null) {
                output.error("Service '${args[0]}' not found.")
                return true
            }
            listOf(service)
        } else {
            registry.getAll()
        }

        val readyServices = services.filter { it.state == ServiceState.READY }

        if (readyServices.isEmpty()) {
            output.info("No ready services to query.")
            return true
        }

        val players = mutableListOf<PlayerEntry>()

        for (service in readyServices) {
            val result = ServerListPing.ping("127.0.0.1", service.port) ?: continue
            for (playerName in result.playerNames) {
                players.add(PlayerEntry(playerName, service.name, service.groupName))
            }
        }

        if (players.isEmpty()) {
            output.info("No players online.")
            return true
        }

        val headers = listOf("PLAYER", "SERVICE", "SERVER")
        val rows = players.sortedBy { it.playerName.lowercase() }.map { entry ->
            listOf(
                ConsoleFormatter.colorize(entry.playerName, ConsoleFormatter.BOLD),
                entry.serviceName,
                entry.serverGroup
            )
        }

        output.header("Players")
        output.text(ConsoleFormatter.formatTable(headers, rows))
        output.text(ConsoleFormatter.count(players.size, "player") + " online")
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
