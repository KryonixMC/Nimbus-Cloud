package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class InfoCommand(
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry,
    private val config: NimbusConfig? = null
) : Command {

    override val name = "info"
    override val description = "Show detailed group configuration"
    override val usage = "info <group>"

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

        val def = group.config.group
        val services = registry.getByGroup(groupName)
        val totalPlayers = services.sumOf { it.playerCount }

        println(ConsoleFormatter.header("Group: ${group.name}"))

        println(ConsoleFormatter.field("Type", ConsoleFormatter.colorize(def.type.name, ConsoleFormatter.CYAN), labelWidth = 22))
        println(ConsoleFormatter.field("Software", ConsoleFormatter.colorize(def.software.name, ConsoleFormatter.CYAN), labelWidth = 22))
        println(ConsoleFormatter.field("Version", def.version, labelWidth = 22))
        println(ConsoleFormatter.field("Template", def.template.ifEmpty { ConsoleFormatter.placeholder("(default)") }, labelWidth = 22))

        println(ConsoleFormatter.section("Resources"))
        println(ConsoleFormatter.field("Memory", def.resources.memory, labelWidth = 22))
        println(ConsoleFormatter.field("Max Players", def.resources.maxPlayers.toString(), labelWidth = 22))

        println(ConsoleFormatter.section("Scaling"))
        println(ConsoleFormatter.field("Min Instances", def.scaling.minInstances.toString(), labelWidth = 22))
        println(ConsoleFormatter.field("Max Instances", def.scaling.maxInstances.toString(), labelWidth = 22))
        println(ConsoleFormatter.field("Players/Instance", def.scaling.playersPerInstance.toString(), labelWidth = 22))
        println(ConsoleFormatter.field("Scale Threshold", "${(def.scaling.scaleThreshold * 100).toInt()}%", labelWidth = 22))
        if (def.scaling.idleTimeout > 0) {
            println(ConsoleFormatter.field("Idle Timeout", "${def.scaling.idleTimeout}ms", labelWidth = 22))
        }

        println(ConsoleFormatter.section("Lifecycle"))
        println(ConsoleFormatter.field("Stop on Empty", ConsoleFormatter.yesNo(def.lifecycle.stopOnEmpty), labelWidth = 22))
        println(ConsoleFormatter.field("Restart on Crash", ConsoleFormatter.yesNo(def.lifecycle.restartOnCrash), labelWidth = 22))
        println(ConsoleFormatter.field("Max Restarts", def.lifecycle.maxRestarts.toString(), labelWidth = 22))

        println(ConsoleFormatter.section("JVM"))
        println(ConsoleFormatter.field("Optimize", if (def.jvm.optimize) ConsoleFormatter.success("Aikar's Flags + Config Tuning") else ConsoleFormatter.placeholder("disabled"), labelWidth = 22))
        if (def.jvm.args.isNotEmpty()) {
            println(ConsoleFormatter.field("Custom Args", "", labelWidth = 22))
            for (arg in def.jvm.args) {
                println("  ${ConsoleFormatter.colorize(arg, ConsoleFormatter.DIM)}")
            }
        }

        // Bedrock info for proxy groups
        if (def.software == ServerSoftware.VELOCITY && config?.bedrock?.enabled == true) {
            println(ConsoleFormatter.section("Bedrock"))
            println(ConsoleFormatter.field("Geyser + Floodgate", ConsoleFormatter.success("enabled"), labelWidth = 22))
            val bedrockPorts = services.mapNotNull { it.bedrockPort }
            if (bedrockPorts.isNotEmpty()) {
                println(ConsoleFormatter.field("UDP Port(s)", bedrockPorts.joinToString(", "), labelWidth = 22))
            }
        }

        println(ConsoleFormatter.section("Runtime"))
        println(ConsoleFormatter.field("Running Instances", if (services.isNotEmpty()) ConsoleFormatter.success(services.size.toString()) else ConsoleFormatter.placeholder("0"), labelWidth = 22))
        println(ConsoleFormatter.field("Total Players", if (totalPlayers > 0) ConsoleFormatter.colorize("$totalPlayers", ConsoleFormatter.BOLD) else ConsoleFormatter.placeholder("0"), labelWidth = 22))
    }
}
