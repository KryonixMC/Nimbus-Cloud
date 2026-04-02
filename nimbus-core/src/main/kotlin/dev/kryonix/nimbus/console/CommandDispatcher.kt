package dev.kryonix.nimbus.console

import dev.kryonix.nimbus.console.commands.ShutdownCommand
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.module.ModuleCommand
import dev.kryonix.nimbus.service.ServiceRegistry
import org.slf4j.LoggerFactory

/**
 * Core command interface. Extends [ModuleCommand] so module commands
 * are fully compatible with the command system.
 */
interface Command : ModuleCommand

class CommandDispatcher {

    private val logger = LoggerFactory.getLogger(CommandDispatcher::class.java)
    private val commands = linkedMapOf<String, ModuleCommand>()
    private val completers = mutableMapOf<String, (List<String>, String) -> List<String>>()

    // Set externally for contextual tab completion
    var registry: ServiceRegistry? = null
    var groupManager: GroupManager? = null

    // Commands that take a service name as first arg
    private val serviceArgCommands = setOf("stop", "restart", "screen", "exec", "logs", "players")
    // Commands that take a group name as first arg
    private val groupArgCommands = setOf("start", "info", "dynamic")

    fun register(command: ModuleCommand) {
        commands[command.name.lowercase()] = command
        logger.debug("Registered command: {}", command.name)
    }

    fun unregister(name: String) {
        val removed = commands.remove(name.lowercase())
        if (removed != null) {
            completers.remove(name.lowercase())
            logger.debug("Unregistered command: {}", name)
        }
    }

    fun registerCompleter(commandName: String, completer: (List<String>, String) -> List<String>) {
        completers[commandName.lowercase()] = completer
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
                    " ${ConsoleFormatter.DIM}— type ${ConsoleFormatter.CYAN}help${ConsoleFormatter.DIM} for available commands.${ConsoleFormatter.RESET}")
            return true
        }

        try {
            command.execute(args)
        } catch (e: Exception) {
            logger.error("Error executing command '{}'", commandName, e)
            println(ConsoleFormatter.error("Error executing '$commandName': ${e.message}"))
        }

        // Exit REPL when shutdown command signals it
        if (command is ShutdownCommand && command.shouldExit) return false
        return true
    }

    fun getCommands(): List<ModuleCommand> = commands.values.toList()

    fun getCommand(name: String): ModuleCommand? = commands[name.lowercase()]

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

            // Check module-registered completers first
            val moduleCompleter = completers[commandName]
            if (moduleCompleter != null) {
                return moduleCompleter(parts.drop(1), argPrefix)
            }

            when (commandName) {
                in serviceArgCommands -> {
                    val services = registry?.getAll()?.map { it.name } ?: emptyList()
                    services.filter { it.startsWith(argPrefix, ignoreCase = true) }
                }
                in groupArgCommands -> {
                    val groups = groupManager?.getAllGroups()?.map { it.name } ?: emptyList()
                    groups.filter { it.startsWith(argPrefix, ignoreCase = true) }
                }
                "static" -> {
                    if (parts.size <= 2) {
                        // Complete subcommand: group or service
                        listOf("group", "service").filter { it.startsWith(argPrefix, ignoreCase = true) }
                    } else {
                        // Complete name depending on subcommand
                        val sub = parts[1].lowercase()
                        when (sub) {
                            "group" -> {
                                val groups = groupManager?.getAllGroups()?.map { it.name } ?: emptyList()
                                groups.filter { it.startsWith(argPrefix, ignoreCase = true) }
                            }
                            "service" -> {
                                val services = registry?.getAll()?.map { it.name } ?: emptyList()
                                services.filter { it.startsWith(argPrefix, ignoreCase = true) }
                            }
                            else -> emptyList()
                        }
                    }
                }
                "update" -> {
                    when (parts.size) {
                        2 -> {
                            // Complete group name
                            val groups = groupManager?.getAllGroups()?.map { it.name } ?: emptyList()
                            groups.filter { it.startsWith(argPrefix, ignoreCase = true) }
                        }
                        3 -> {
                            // Complete subcommand: version or software
                            listOf("version", "software").filter { it.startsWith(argPrefix, ignoreCase = true) }
                        }
                        4 -> {
                            // Complete software names when subcommand is "software"
                            if (parts[2].lowercase() == "software") {
                                listOf("paper", "purpur", "forge", "neoforge", "fabric")
                                    .filter { it.startsWith(argPrefix, ignoreCase = true) }
                            } else emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "api" -> {
                    val subcommands = listOf("start", "stop", "status", "token")
                    subcommands.filter { it.startsWith(argPrefix, ignoreCase = true) }
                }
                "cluster" -> {
                    when (parts.size) {
                        2 -> listOf("status", "enable", "disable", "token")
                            .filter { it.startsWith(argPrefix, ignoreCase = true) }
                        3 -> when (parts[1].lowercase()) {
                            "token" -> listOf("regenerate")
                                .filter { it.startsWith(argPrefix, ignoreCase = true) }
                            else -> emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "lb" -> {
                    when (parts.size) {
                        2 -> listOf("status", "enable", "disable", "strategy")
                            .filter { it.startsWith(argPrefix, ignoreCase = true) }
                        3 -> when (parts[1].lowercase()) {
                            "strategy" -> listOf("least-players", "round-robin")
                                .filter { it.startsWith(argPrefix, ignoreCase = true) }
                            else -> emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "stress" -> {
                    when (parts.size) {
                        2 -> listOf("start", "stop", "ramp", "status")
                            .filter { it.startsWith(argPrefix, ignoreCase = true) }
                        3 -> when (parts[1].lowercase()) {
                            "start" -> emptyList() // numeric, no completion
                            "ramp" -> emptyList()
                            else -> emptyList()
                        }
                        4 -> when (parts[1].lowercase()) {
                            "start" -> {
                                // Complete group name after player count
                                val groups = groupManager?.getAllGroups()?.map { it.name } ?: emptyList()
                                groups.filter { it.startsWith(argPrefix, ignoreCase = true) }
                            }
                            else -> emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "purge" -> {
                    val services = registry?.getAll()?.map { it.name } ?: emptyList()
                    val options = mutableListOf("crashed")
                    options.addAll(services)
                    options.filter { it.startsWith(argPrefix, ignoreCase = true) }
                }
                "maintenance" -> {
                    when (parts.size) {
                        2 -> {
                            val options = mutableListOf("on", "off", "list", "add", "remove")
                            val groups = groupManager?.getAllGroups()?.map { it.name } ?: emptyList()
                            options.addAll(groups)
                            options.filter { it.startsWith(argPrefix, ignoreCase = true) }
                        }
                        3 -> {
                            // If second arg is a group name, complete with on/off
                            val groups = groupManager?.getAllGroups()?.map { it.name } ?: emptyList()
                            if (parts[1] in groups || groups.any { it.equals(parts[1], ignoreCase = true) }) {
                                listOf("on", "off").filter { it.startsWith(argPrefix, ignoreCase = true) }
                            } else {
                                emptyList()
                            }
                        }
                        else -> emptyList()
                    }
                }
                "modules" -> {
                    when (parts.size) {
                        2 -> listOf("list", "install", "uninstall")
                            .filter { it.startsWith(argPrefix, ignoreCase = true) }
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
        }
    }
}
