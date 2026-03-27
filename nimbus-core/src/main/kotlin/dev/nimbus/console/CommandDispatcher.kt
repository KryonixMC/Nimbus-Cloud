package dev.nimbus.console

import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceRegistry
import org.slf4j.LoggerFactory

interface Command {
    val name: String
    val description: String
    val usage: String
    suspend fun execute(args: List<String>)
}

class CommandDispatcher {

    private val logger = LoggerFactory.getLogger(CommandDispatcher::class.java)
    private val commands = linkedMapOf<String, Command>()

    // Set externally for contextual tab completion
    var registry: ServiceRegistry? = null
    var groupManager: GroupManager? = null

    // Commands that take a service name as first arg
    private val serviceArgCommands = setOf("stop", "restart", "screen", "exec", "logs", "players")
    // Commands that take a group name as first arg
    private val groupArgCommands = setOf("start", "info")

    fun register(command: Command) {
        commands[command.name.lowercase()] = command
        logger.debug("Registered command: {}", command.name)
    }

    /**
     * Dispatches the given input line to the matching command.
     * Returns false when the "shutdown" command is invoked, signaling exit.
     */
    suspend fun dispatch(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return true

        val parts = trimmed.split("\\s+".toRegex())
        val commandName = when (parts[0].lowercase()) {
            "?" -> "help"
            else -> parts[0].lowercase()
        }
        val args = parts.drop(1)

        val command = commands[commandName]
        if (command == null) {
            println(ConsoleFormatter.error("Unknown command: $commandName") +
                    " " + ConsoleFormatter.colorize("Type 'help' for available commands.", ConsoleFormatter.DIM))
            return true
        }

        try {
            command.execute(args)
        } catch (e: Exception) {
            logger.error("Error executing command '{}'", commandName, e)
            println(ConsoleFormatter.error("Error executing '$commandName': ${e.message}"))
        }

        // Return false when shutdown was invoked
        return commandName != "shutdown"
    }

    fun getCommands(): List<Command> = commands.values.toList()

    fun getCommand(name: String): Command? = commands[name.lowercase()]

    /**
     * Provides tab-completion candidates for the given input buffer.
     */
    fun complete(buffer: String): List<String> {
        val trimmed = buffer.trimStart()
        val parts = trimmed.split("\\s+".toRegex())

        return if (parts.size <= 1) {
            // Complete command names
            val prefix = parts.firstOrNull()?.lowercase() ?: ""
            commands.keys.filter { it.startsWith(prefix) }
        } else {
            // Complete arguments based on command
            val commandName = parts[0].lowercase()
            val argPrefix = parts.last()

            when (commandName) {
                in serviceArgCommands -> {
                    val services = registry?.getAll()?.map { it.name } ?: emptyList()
                    services.filter { it.startsWith(argPrefix, ignoreCase = true) }
                }
                in groupArgCommands -> {
                    val groups = groupManager?.getAllGroups()?.map { it.name } ?: emptyList()
                    groups.filter { it.startsWith(argPrefix, ignoreCase = true) }
                }
                "api" -> {
                    val subcommands = listOf("start", "stop", "status", "token")
                    subcommands.filter { it.startsWith(argPrefix, ignoreCase = true) }
                }
                else -> emptyList()
            }
        }
    }
}
