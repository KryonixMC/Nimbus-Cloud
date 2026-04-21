package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceRegistry
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

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            return true
        }

        val groupName = args[0]
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            output.error("Group '$groupName' not found.")
            return true
        }

        if (group.isDynamic) {
            output.info("Group '$groupName' is already dynamic.")
            return true
        }

        groupManager.updateGroupType(groupName, GroupType.DYNAMIC)

        val tomlFile = groupsDir.resolve("${groupName.lowercase()}.toml")
        if (tomlFile.exists()) {
            val content = tomlFile.readText()
            val typePattern = Regex("""^(\s*type\s*=\s*)["']?\w+["']?""", RegexOption.MULTILINE)
            val updated = typePattern.replace(content) { match ->
                "${match.groupValues[1]}\"DYNAMIC\""
            }
            tomlFile.writeText(updated)
        }

        output.success("Group '$groupName' is now DYNAMIC.")
        output.info("New services will use temporary directories that are cleaned up on stop.")

        val running = registry.getByGroup(groupName)
        if (running.isNotEmpty()) {
            output.info("${running.size} running service(s) remain static until restarted.")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
