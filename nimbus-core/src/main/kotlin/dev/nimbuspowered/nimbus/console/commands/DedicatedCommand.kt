package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.DedicatedDefinition
import dev.nimbuspowered.nimbus.config.DedicatedServiceConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.console.InteractivePicker
import dev.nimbuspowered.nimbus.console.NimbusConsole
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.PortAllocator
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal

class DedicatedCommand(
    private val terminal: Terminal,
    private val dedicatedServiceManager: DedicatedServiceManager,
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val portAllocator: PortAllocator,
    private val dedicatedDir: java.nio.file.Path,
    private val console: NimbusConsole,
    private val eventBus: EventBus
) : Command {

    override val name = "dedicated"
    override val description = "Manage dedicated services"
    override val usage = "dedicated <list|create|start|stop|restart|delete|info> [name]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            return true
        }

        return when (args[0].lowercase()) {
            "list" -> { list(output); true }
            "start" -> { startCmd(args.drop(1), output); true }
            "stop" -> { stopCmd(args.drop(1), output); true }
            "restart" -> { restartCmd(args.drop(1), output); true }
            "delete" -> { deleteCmd(args.drop(1), output); true }
            "info" -> { infoCmd(args.drop(1), output); true }
            "create" -> {
                output.error("The create subcommand requires an interactive terminal.")
                true
            }
            else -> {
                output.error("Unknown subcommand: ${args[0]}. Usage: $usage")
                true
            }
        }
    }

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        when (args[0].lowercase()) {
            "list" -> list(ConsoleOutput())
            "create" -> createWizard()
            "start" -> startCmd(args.drop(1), ConsoleOutput())
            "stop" -> stopCmd(args.drop(1), ConsoleOutput())
            "restart" -> restartCmd(args.drop(1), ConsoleOutput())
            "delete" -> deleteCmd(args.drop(1), ConsoleOutput())
            "info" -> infoCmd(args.drop(1), ConsoleOutput())
            else -> println(ConsoleFormatter.error("Unknown subcommand: ${args[0]}. Usage: $usage"))
        }
    }

    private fun list(output: CommandOutput) {
        val configs = dedicatedServiceManager.getAllConfigs()
        if (configs.isEmpty()) {
            output.info("No dedicated services configured.")
            return
        }

        val headers = listOf("NAME", "PORT", "SOFTWARE", "VERSION", "MEMORY", "PROXY", "STATUS")
        val rows = configs.sortedBy { it.dedicated.name }.map { cfg ->
            val def = cfg.dedicated
            val service = registry.get(def.name)
            val status = if (service != null) {
                ConsoleFormatter.colorize(service.state.name, when (service.state) {
                    dev.nimbuspowered.nimbus.service.ServiceState.READY -> ConsoleFormatter.GREEN
                    dev.nimbuspowered.nimbus.service.ServiceState.STARTING -> ConsoleFormatter.YELLOW
                    dev.nimbuspowered.nimbus.service.ServiceState.CRASHED -> ConsoleFormatter.RED
                    else -> ConsoleFormatter.DIM
                })
            } else {
                ConsoleFormatter.placeholder("STOPPED")
            }
            listOf(
                ConsoleFormatter.colorize(def.name, BOLD),
                def.port.toString(),
                def.software.name,
                def.version,
                def.memory,
                ConsoleFormatter.yesNo(def.proxyEnabled),
                status
            )
        }

        output.header("Dedicated Services")
        output.text(ConsoleFormatter.formatTable(headers, rows))
        output.text(ConsoleFormatter.count(configs.size, "dedicated service"))
    }

    private suspend fun startCmd(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) {
            output.error("Usage: dedicated start <name>")
            return
        }
        val name = args[0]
        val config = dedicatedServiceManager.getConfig(name)
        if (config == null) {
            output.error("Dedicated service '$name' not found.")
            return
        }
        val existing = registry.get(name)
        if (existing != null && existing.state != dev.nimbuspowered.nimbus.service.ServiceState.STOPPED &&
            existing.state != dev.nimbuspowered.nimbus.service.ServiceState.CRASHED) {
            output.error("Service '$name' is already running (${existing.state}).")
            return
        }
        output.info("Starting dedicated service '$name'...")
        try {
            serviceManager.startDedicatedService(config.dedicated)
            output.success("Service '$name' start initiated.")
        } catch (e: Exception) {
            output.error("Failed to start '$name': ${e.message}")
        }
    }

    private suspend fun stopCmd(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) {
            output.error("Usage: dedicated stop <name>")
            return
        }
        val name = args[0]
        if (dedicatedServiceManager.getConfig(name) == null) {
            output.error("Dedicated service '$name' not found.")
            return
        }
        val service = registry.get(name)
        if (service == null) {
            output.error("Service '$name' is not running.")
            return
        }
        output.info("Stopping dedicated service '$name'...")
        try {
            serviceManager.stopService(name)
            output.success("Service '$name' stopped.")
        } catch (e: Exception) {
            output.error("Failed to stop '$name': ${e.message}")
        }
    }

    private suspend fun restartCmd(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) {
            output.error("Usage: dedicated restart <name>")
            return
        }
        val name = args[0]
        val config = dedicatedServiceManager.getConfig(name)
        if (config == null) {
            output.error("Dedicated service '$name' not found.")
            return
        }
        val service = registry.get(name)
        if (service != null && service.state != dev.nimbuspowered.nimbus.service.ServiceState.STOPPED &&
            service.state != dev.nimbuspowered.nimbus.service.ServiceState.CRASHED) {
            output.info("Stopping dedicated service '$name'...")
            try {
                serviceManager.stopService(name)
            } catch (e: Exception) {
                output.error("Failed to stop '$name': ${e.message}")
                return
            }
        }
        output.info("Starting dedicated service '$name'...")
        try {
            serviceManager.startDedicatedService(config.dedicated)
            output.success("Service '$name' restart initiated.")
        } catch (e: Exception) {
            output.error("Failed to start '$name': ${e.message}")
        }
    }

    private suspend fun deleteCmd(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) {
            output.error("Usage: dedicated delete <name>")
            return
        }
        val name = args[0]
        if (dedicatedServiceManager.getConfig(name) == null) {
            output.error("Dedicated service '$name' not found.")
            return
        }
        val service = registry.get(name)
        if (service != null && service.state != dev.nimbuspowered.nimbus.service.ServiceState.STOPPED &&
            service.state != dev.nimbuspowered.nimbus.service.ServiceState.CRASHED) {
            output.info("Stopping running service '$name'...")
            try {
                serviceManager.stopService(name)
            } catch (e: Exception) {
                output.error("Failed to stop '$name': ${e.message}")
            }
        }
        dedicatedServiceManager.deleteTOML(name)
        dedicatedServiceManager.removeConfig(name)
        eventBus.emit(NimbusEvent.DedicatedDeleted(name))
        output.success("Dedicated service '$name' deleted.")
    }

    private fun infoCmd(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) {
            output.error("Usage: dedicated info <name>")
            return
        }
        val name = args[0]
        val config = dedicatedServiceManager.getConfig(name)
        if (config == null) {
            output.error("Dedicated service '$name' not found.")
            return
        }

        val def = config.dedicated
        val service = registry.get(name)

        output.header("Dedicated: ${def.name}")

        output.text(ConsoleFormatter.field("Directory", dedicatedServiceManager.getServiceDirectory(def.name).toString(), labelWidth = 22))
        output.text(ConsoleFormatter.field("Port", def.port.toString(), labelWidth = 22))
        output.text(ConsoleFormatter.field("Software", ConsoleFormatter.colorize(def.software.name, CYAN), labelWidth = 22))
        output.text(ConsoleFormatter.field("Version", def.version, labelWidth = 22))
        output.text(ConsoleFormatter.field("Memory", def.memory, labelWidth = 22))
        output.text(ConsoleFormatter.field("Proxy Enabled", ConsoleFormatter.yesNo(def.proxyEnabled), labelWidth = 22))
        output.text(ConsoleFormatter.field("Restart on Crash", ConsoleFormatter.yesNo(def.restartOnCrash), labelWidth = 22))
        output.text(ConsoleFormatter.field("Max Restarts", def.maxRestarts.toString(), labelWidth = 22))
        if (def.jarName.isNotEmpty()) {
            output.text(ConsoleFormatter.field("JAR Name", def.jarName, labelWidth = 22))
        }
        if (def.javaPath.isNotEmpty()) {
            output.text(ConsoleFormatter.field("Java Path", def.javaPath, labelWidth = 22))
        }
        if (def.readyPattern.isNotEmpty()) {
            output.text(ConsoleFormatter.field("Ready Pattern", def.readyPattern, labelWidth = 22))
        }

        output.text(ConsoleFormatter.section("Runtime"))
        if (service != null) {
            val stateColor = when (service.state) {
                dev.nimbuspowered.nimbus.service.ServiceState.READY -> ConsoleFormatter.GREEN
                dev.nimbuspowered.nimbus.service.ServiceState.STARTING -> ConsoleFormatter.YELLOW
                dev.nimbuspowered.nimbus.service.ServiceState.CRASHED -> ConsoleFormatter.RED
                else -> ConsoleFormatter.DIM
            }
            output.text(ConsoleFormatter.field("State", ConsoleFormatter.colorize(service.state.name, stateColor), labelWidth = 22))
            output.text(ConsoleFormatter.field("Players", service.playerCount.toString(), labelWidth = 22))
            output.text(ConsoleFormatter.field("Uptime", ConsoleFormatter.formatUptime(service.startedAt), labelWidth = 22))
        } else {
            output.text(ConsoleFormatter.field("State", ConsoleFormatter.placeholder("STOPPED"), labelWidth = 22))
        }
    }

    // -- Interactive wizard --------------------------------------------------

    private suspend fun createWizard() {
        console.eventsPaused = true
        val w = terminal.writer()
        w.print("\u001B[2J\u001B[H")
        w.flush()

        try {
            w.println(ConsoleFormatter.colorize("Create Dedicated Service", BOLD))
            w.println()

            // Step 1: Name
            val name = promptName(w) ?: return

            // Step 2: Port
            val portStr = prompt("Port", "25566")
            val port = portStr.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                w.println(ConsoleFormatter.error("Invalid port number."))
                return
            }

            // Step 3: Software
            w.println(ConsoleFormatter.hint("Select server software:"))
            val softwareOptions = listOf(
                InteractivePicker.Option("paper", "Paper", "optimized vanilla, plugins"),
                InteractivePicker.Option("purpur", "Purpur", "Paper fork, extra features"),
                InteractivePicker.Option("folia", "Folia", "regionized multithreading"),
                InteractivePicker.Option("forge", "Forge", "mods"),
                InteractivePicker.Option("neoforge", "NeoForge", "modern Forge fork"),
                InteractivePicker.Option("fabric", "Fabric", "lightweight mods"),
                InteractivePicker.Option("custom", "Custom JAR", "bring your own")
            )
            val softwareIndex = InteractivePicker.pickOne(terminal, softwareOptions)
            if (softwareIndex == InteractivePicker.BACK) {
                w.println(ConsoleFormatter.hint("Cancelled."))
                return
            }
            val software = when (softwareOptions[softwareIndex].id) {
                "purpur" -> ServerSoftware.PURPUR
                "folia" -> ServerSoftware.FOLIA
                "forge" -> ServerSoftware.FORGE
                "neoforge" -> ServerSoftware.NEOFORGE
                "fabric" -> ServerSoftware.FABRIC
                "custom" -> ServerSoftware.CUSTOM
                else -> ServerSoftware.PAPER
            }
            w.println(ConsoleFormatter.successLine(softwareOptions[softwareIndex].label))

            // Step 4: Version
            val version = prompt("Minecraft version", "1.21.4")

            // Step 5: Memory
            val memory = normalizeMemory(prompt("Memory", "2G"))

            // Step 6: Proxy enabled
            val proxyInput = prompt("Register with proxy? (y/n)", "y").lowercase()
            val proxyEnabled = proxyInput == "y" || proxyInput == "yes"

            // Step 7: Write TOML + create directory
            w.println()
            val config = DedicatedServiceConfig(
                dedicated = DedicatedDefinition(
                    name = name,
                    port = port,
                    software = software,
                    version = version,
                    memory = memory,
                    proxyEnabled = proxyEnabled
                )
            )
            val createdDir = dedicatedServiceManager.ensureServiceDirectory(name, software)
            dedicatedServiceManager.writeTOML(config)
            dedicatedServiceManager.addConfig(config)
            eventBus.emit(NimbusEvent.DedicatedCreated(name))
            w.println(ConsoleFormatter.hint("Service directory: $createdDir"))
            w.println(ConsoleFormatter.successLine("config/dedicated/${name}.toml"))

            w.println()
            w.println(ConsoleFormatter.successLine("Dedicated service '$name' created!"))
            w.println()

            // Step 9: Start now?
            val startOptions = listOf(
                InteractivePicker.Option("yes", "Yes, start now"),
                InteractivePicker.Option("no", "No, start later")
            )
            val startIndex = InteractivePicker.pickOne(terminal, startOptions, 0)
            if (startIndex != InteractivePicker.BACK && startOptions[startIndex].id == "yes") {
                try {
                    serviceManager.startDedicatedService(config.dedicated)
                    w.println(ConsoleFormatter.successLine("Service start initiated."))
                } catch (e: Exception) {
                    w.println(ConsoleFormatter.errorLine("Failed: ${e.message}"))
                }
            }

            w.println()
            w.flush()

        } catch (_: UserInterruptException) {
            w.println()
            w.println(ConsoleFormatter.hint("Cancelled."))
            w.flush()
        } finally {
            console.eventsPaused = false
            console.reprintBanner()
            console.flushBufferedEvents()
        }
    }

    private fun prompt(label: String, default: String, candidates: List<String> = emptyList()): String {
        val completer = if (candidates.isNotEmpty()) {
            Completer { _, _, list -> candidates.forEach { list.add(Candidate(it)) } }
        } else null
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .let { if (completer != null) it.completer(completer) else it }
            .build()
        val hint = if (default.isNotEmpty()) " ${ConsoleFormatter.hint("[$default]")}" else ""
        val line = reader.readLine("$label$hint${ConsoleFormatter.hint(":")} ").trim()
        return line.ifEmpty { default }
    }

    private fun promptName(w: java.io.PrintWriter): String? {
        while (true) {
            val name = prompt("Service name", "")
            if (name.isBlank()) {
                w.println(ConsoleFormatter.error("Name cannot be empty."))
                w.flush()
                continue
            }
            if (groupManager.getGroup(name) != null) {
                w.println(ConsoleFormatter.error("Name '$name' conflicts with an existing group."))
                w.flush()
                continue
            }
            if (dedicatedServiceManager.getConfig(name) != null) {
                w.println(ConsoleFormatter.error("Dedicated service '$name' already exists."))
                w.flush()
                continue
            }
            return name
        }
    }

    private fun normalizeMemory(input: String): String {
        val trimmed = input.trim()
        if (trimmed.last().isDigit()) {
            val num = trimmed.toIntOrNull() ?: return trimmed
            return if (num >= 256) "${num}M" else "${num}G"
        }
        return trimmed
    }
}
