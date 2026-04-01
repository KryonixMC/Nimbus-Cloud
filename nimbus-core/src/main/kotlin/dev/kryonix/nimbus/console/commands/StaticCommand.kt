package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.config.ConfigLoader
import dev.kryonix.nimbus.config.GroupType
import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StaticCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val groupsDir: Path
) : Command {

    override val name = "static"
    override val description = "Set a group to static or convert a running service to static"
    override val usage = "static group <name> | static service <name>"

    override suspend fun execute(args: List<String>) {
        if (args.size < 2) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        when (args[0].lowercase()) {
            "group" -> handleGroup(args[1])
            "service" -> handleService(args[1])
            else -> println(ConsoleFormatter.error("Usage: $usage"))
        }
    }

    private suspend fun handleGroup(groupName: String) {
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            println(ConsoleFormatter.error("Group '$groupName' not found."))
            return
        }

        if (group.isStatic) {
            println(ConsoleFormatter.warn("Group '$groupName' is already static."))
            return
        }

        // Update in-memory
        groupManager.updateGroupType(groupName, GroupType.STATIC)

        // Persist to TOML file
        val tomlFile = groupsDir.resolve("${groupName.lowercase()}.toml")
        if (tomlFile.exists()) {
            val content = tomlFile.readText()
            val updated = updateTypeInToml(content, "STATIC")
            tomlFile.writeText(updated)
        }

        println(ConsoleFormatter.success("Group '$groupName' is now STATIC."))
        println(ConsoleFormatter.info("New services will preserve their working directory across restarts."))

        val running = registry.getByGroup(groupName)
        if (running.isNotEmpty()) {
            println(ConsoleFormatter.warn("${running.size} running service(s) are still dynamic. Use 'static service <name>' to convert them individually."))
        }
    }

    private suspend fun handleService(serviceName: String) {
        val service = registry.get(serviceName)
        if (service == null) {
            println(ConsoleFormatter.error("Service '$serviceName' not found."))
            return
        }

        if (service.isStatic) {
            println(ConsoleFormatter.warn("Service '$serviceName' is already static."))
            return
        }

        println(ConsoleFormatter.info("Converting '$serviceName' to static (copying working directory)..."))
        val success = serviceManager.convertToStatic(serviceName)
        if (success) {
            println(ConsoleFormatter.success("Service '$serviceName' is now static."))
            println(ConsoleFormatter.info("Working directory will be preserved when the service stops."))
        } else {
            println(ConsoleFormatter.error("Failed to convert '$serviceName' to static."))
        }
    }

    /**
     * Updates the type field in a TOML group config string.
     * Handles both existing type = "..." lines and missing type field.
     */
    private fun updateTypeInToml(content: String, type: String): String {
        val typePattern = Regex("""^(\s*type\s*=\s*)["']?\w+["']?""", RegexOption.MULTILINE)
        return if (typePattern.containsMatchIn(content)) {
            typePattern.replace(content) { match ->
                "${match.groupValues[1]}\"$type\""
            }
        } else {
            // Insert type after the name field
            val namePattern = Regex("""^(\s*name\s*=\s*"[^"]*"\s*)$""", RegexOption.MULTILINE)
            namePattern.replace(content) { match ->
                "${match.value}\ntype = \"$type\""
            }
        }
    }
}
