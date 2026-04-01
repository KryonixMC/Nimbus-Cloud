package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.config.GroupType
import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceRegistry
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DynamicCommand(
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val groupsDir: Path
) : Command {

    override val name = "dynamic"
    override val description = "Set a group back to dynamic mode"
    override val usage = "dynamic <group>"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        val groupName = args[0]
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            println(ConsoleFormatter.error("Group '$groupName' not found."))
            return
        }

        if (group.isDynamic) {
            println(ConsoleFormatter.warn("Group '$groupName' is already dynamic."))
            return
        }

        // Update in-memory
        groupManager.updateGroupType(groupName, GroupType.DYNAMIC)

        // Persist to TOML file
        val tomlFile = groupsDir.resolve("${groupName.lowercase()}.toml")
        if (tomlFile.exists()) {
            val content = tomlFile.readText()
            val typePattern = Regex("""^(\s*type\s*=\s*)["']?\w+["']?""", RegexOption.MULTILINE)
            val updated = typePattern.replace(content) { match ->
                "${match.groupValues[1]}\"DYNAMIC\""
            }
            tomlFile.writeText(updated)
        }

        println(ConsoleFormatter.success("Group '$groupName' is now DYNAMIC."))
        println(ConsoleFormatter.info("New services will use temporary directories that are cleaned up on stop."))

        val running = registry.getByGroup(groupName)
        if (running.isNotEmpty()) {
            println(ConsoleFormatter.warn("${running.size} running service(s) remain static until restarted."))
        }
    }
}
