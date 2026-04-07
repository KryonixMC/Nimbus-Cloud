package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.ConfigLoader
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
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

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.size < 2) {
            output.error("Usage: $usage")
            return true
        }

        when (args[0].lowercase()) {
            "group" -> {
                val groupName = args[1]
                val group = groupManager.getGroup(groupName)
                if (group == null) {
                    output.error("Group '$groupName' not found.")
                    return true
                }

                if (group.isStatic) {
                    output.info("Group '$groupName' is already static.")
                    return true
                }

                groupManager.updateGroupType(groupName, GroupType.STATIC)

                val tomlFile = groupsDir.resolve("${groupName.lowercase()}.toml")
                if (tomlFile.exists()) {
                    val content = tomlFile.readText()
                    val updated = updateTypeInToml(content, "STATIC")
                    tomlFile.writeText(updated)
                }

                output.success("Group '$groupName' is now STATIC.")
                output.info("New services will preserve their working directory across restarts.")

                val running = registry.getByGroup(groupName)
                if (running.isNotEmpty()) {
                    output.info("${running.size} running service(s) are still dynamic. Use 'static service <name>' to convert them individually.")
                }
            }
            "service" -> {
                val serviceName = args[1]
                val service = registry.get(serviceName)
                if (service == null) {
                    output.error("Service '$serviceName' not found.")
                    return true
                }

                if (service.isStatic) {
                    output.info("Service '$serviceName' is already static.")
                    return true
                }

                output.info("Converting '$serviceName' to static (copying working directory)...")
                val success = serviceManager.convertToStatic(serviceName)
                if (success) {
                    output.success("Service '$serviceName' is now static.")
                    output.info("Working directory will be preserved when the service stops.")
                } else {
                    output.error("Failed to convert '$serviceName' to static.")
                }
            }
            else -> output.error("Usage: $usage")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
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
