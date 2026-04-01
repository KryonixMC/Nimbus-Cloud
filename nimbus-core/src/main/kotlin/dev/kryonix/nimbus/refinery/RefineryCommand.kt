package dev.kryonix.nimbus.refinery

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.console.ConsoleFormatter.BOLD
import dev.kryonix.nimbus.console.ConsoleFormatter.CYAN
import dev.kryonix.nimbus.console.ConsoleFormatter.DIM
import dev.kryonix.nimbus.console.ConsoleFormatter.GREEN
import dev.kryonix.nimbus.console.ConsoleFormatter.RED
import dev.kryonix.nimbus.console.ConsoleFormatter.RESET
import dev.kryonix.nimbus.console.ConsoleFormatter.WHITE
import dev.kryonix.nimbus.console.ConsoleFormatter.YELLOW
import dev.kryonix.nimbus.service.ServiceRegistry

class RefineryCommand(
    private val integration: RefineryIntegration,
    private val registry: ServiceRegistry
) : Command {

    override val name = "refinery"
    override val description = "Manage Refinery performance engine across the fleet"
    override val usage = "refinery <status|config|emergency|module|crashes>"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            showStatus()
            return
        }

        when (args[0].lowercase()) {
            "status" -> showStatus()
            "config" -> handleConfig(args.drop(1))
            "emergency" -> handleEmergency(args.drop(1))
            "module" -> handleModule(args.drop(1))
            "crashes" -> showCrashes(args.drop(1))
            "reload" -> handleReload(args.drop(1))
            else -> {
                println(ConsoleFormatter.warn("Unknown subcommand: ${args[0]}"))
                println(ConsoleFormatter.info("Usage: $usage"))
            }
        }
    }

    private fun showStatus() {
        val store = integration.store
        val telemetry = store.getAllTelemetry()

        // Template detection section
        val detections = store.getTemplateDetections()
        if (detections.isNotEmpty()) {
            println(ConsoleFormatter.header("Refinery Template Detection"))
            val detHeaders = listOf("Group", "Core JAR", "Addon JAR", "Status")
            val detRows = detections.values.sortedBy { it.groupName }.map { d ->
                val coreStatus = if (d.hasCoreJar) "${GREEN}✓$RESET" else "${RED}✗$RESET"
                val addonStatus = if (d.hasAddonJar) "${GREEN}✓$RESET" else "${YELLOW}✗$RESET"
                val status = when {
                    d.hasCoreJar && d.hasAddonJar -> "${GREEN}Full$RESET"
                    d.hasCoreJar && !d.hasAddonJar -> "${YELLOW}Standalone$RESET"
                    else -> "${DIM}—$RESET"
                }
                listOf(d.groupName, coreStatus, addonStatus, status)
            }
            println(ConsoleFormatter.formatTable(detHeaders, detRows))
            println()
        }

        if (telemetry.isEmpty() && detections.isEmpty()) {
            println(ConsoleFormatter.info("No Refinery detected — neither in templates nor via telemetry."))
            println(ConsoleFormatter.info("Install refinery-core.jar in a group template's plugins/ folder."))
            return
        }

        if (telemetry.isEmpty()) {
            println(ConsoleFormatter.info("Refinery JARs found in templates but no telemetry received yet."))
            println(ConsoleFormatter.info("Services need to be running with RefineryNimbus addon for live data."))
            println()
            return
        }

        // Live telemetry section
        println(ConsoleFormatter.header("Refinery Fleet Status"))

        val headers = listOf("Server", "TPS", "MSPT", "Memory", "Entities", "Chunks", "Active Modules")
        val rows = telemetry.map { t ->
            val tpsColor = when (t.tpsLevel) {
                "NORMAL" -> GREEN
                "WARNING" -> YELLOW
                "CRITICAL" -> RED
                else -> RED
            }
            listOf(
                "$BOLD${t.serviceName}$RESET",
                "$tpsColor${String.format("%.1f", t.tps)}$RESET",
                "${String.format("%.1f", t.mspt)}ms",
                "${t.memoryPct}%",
                t.entityCount.toString(),
                t.chunksLoaded.toString(),
                t.activeModules.joinToString(", ")
            )
        }

        println(ConsoleFormatter.formatTable(headers, rows))

        // Services without Refinery
        val refineryServiceNames = store.getAnnouncements().map { it.serviceName }.toSet()
        val allServices = registry.getAll().map { it.name }
        val withoutRefinery = allServices.filter { it !in refineryServiceNames }
        if (withoutRefinery.isNotEmpty()) {
            println()
            println("${DIM}Services without Refinery: ${withoutRefinery.joinToString(", ")}$RESET")
        }

        // Show overloaded services
        val overloaded = store.getOverloadedServices()
        if (overloaded.isNotEmpty()) {
            println()
            println(ConsoleFormatter.warn("Overloaded services:"))
            for (hint in overloaded) {
                println("  ${RED}▲$RESET ${BOLD}${hint.serviceName}$RESET TPS: ${RED}${String.format("%.1f", hint.tps)}$RESET (${hint.consecutiveReports} consecutive reports)")
            }
        }

        println()
        println("${DIM}${store.getRefineryServiceCount()} services with Refinery | Use 'refinery crashes' for crash history$RESET")
    }

    private suspend fun handleConfig(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.info("Usage: refinery config push <group|all> --set <key>=<value>"))
            println(ConsoleFormatter.info("       refinery config reload <service|group|all>"))
            return
        }

        when (args[0].lowercase()) {
            "push" -> handleConfigPush(args.drop(1))
            "reload" -> handleReload(args.drop(1))
            else -> println(ConsoleFormatter.warn("Unknown config subcommand: ${args[0]}"))
        }
    }

    private suspend fun handleConfigPush(args: List<String>) {
        if (args.size < 2) {
            println(ConsoleFormatter.info("Usage: refinery config push <group|all> --set <key>=<value>"))
            return
        }

        val target = args[0]

        // Parse --set key=value pairs
        val setParams = mutableMapOf<String, String>()
        var i = 1
        while (i < args.size) {
            if (args[i] == "--set" && i + 1 < args.size) {
                val kv = args[i + 1].split("=", limit = 2)
                if (kv.size == 2) {
                    setParams["key"] = kv[0]
                    setParams["value"] = kv[1]
                }
                i += 2
            } else {
                i++
            }
        }

        if (setParams.isEmpty()) {
            println(ConsoleFormatter.warn("No --set parameters provided"))
            return
        }

        if (target.lowercase() == "all") {
            integration.sendConfigToAll("set", setParams)
            println(ConsoleFormatter.success("Config pushed to all Refinery services"))
        } else {
            integration.sendConfigToGroup(target, "set", setParams)
            val count = registry.getByGroup(target).size
            println(ConsoleFormatter.success("Config pushed to $count servers ($target)"))
        }
    }

    private suspend fun handleEmergency(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.info("Usage: refinery emergency <service|group|all>"))
            return
        }

        val target = args[0]

        if (target.lowercase() == "all") {
            integration.sendConfigToAll("emergency", mapOf("enabled" to "true"))
            println(ConsoleFormatter.warn("Emergency mode activated on ALL Refinery services"))
        } else {
            // Check if it's a service name or group name
            val service = registry.get(target)
            if (service != null) {
                integration.sendConfigCommand(target, "emergency", mapOf("enabled" to "true"))
                println(ConsoleFormatter.warn("Emergency mode activated on $target"))
            } else {
                integration.sendConfigToGroup(target, "emergency", mapOf("enabled" to "true"))
                val count = registry.getByGroup(target).size
                println(ConsoleFormatter.warn("Emergency mode activated on $count servers ($target)"))
            }
        }
    }

    private suspend fun handleModule(args: List<String>) {
        if (args.size < 3) {
            println(ConsoleFormatter.info("Usage: refinery module <service|group|all> <module-id> <on|off>"))
            return
        }

        val target = args[0]
        val moduleId = args[1]
        val enabled = args[2].lowercase() == "on"
        val params = mapOf("module" to moduleId, "enabled" to enabled.toString())

        if (target.lowercase() == "all") {
            integration.sendConfigToAll("module_toggle", params)
            println(ConsoleFormatter.success("Module '$moduleId' set to ${if (enabled) "ON" else "OFF"} on all Refinery services"))
        } else {
            val service = registry.get(target)
            if (service != null) {
                integration.sendConfigCommand(target, "module_toggle", params)
                println(ConsoleFormatter.success("Module '$moduleId' set to ${if (enabled) "ON" else "OFF"} on $target"))
            } else {
                integration.sendConfigToGroup(target, "module_toggle", params)
                val count = registry.getByGroup(target).size
                println(ConsoleFormatter.success("Module '$moduleId' set to ${if (enabled) "ON" else "OFF"} on $count servers ($target)"))
            }
        }
    }

    private suspend fun handleReload(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.info("Usage: refinery reload <service|group|all>"))
            return
        }

        val target = args[0]

        if (target.lowercase() == "all") {
            integration.sendConfigToAll("reload")
            println(ConsoleFormatter.success("Reload triggered on all Refinery services"))
        } else {
            val service = registry.get(target)
            if (service != null) {
                integration.sendConfigCommand(target, "reload")
                println(ConsoleFormatter.success("Reload triggered on $target"))
            } else {
                integration.sendConfigToGroup(target, "reload")
                val count = registry.getByGroup(target).size
                println(ConsoleFormatter.success("Reload triggered on $count servers ($target)"))
            }
        }
    }

    private fun showCrashes(args: List<String>) {
        val limit = args.firstOrNull()?.toIntOrNull() ?: 10
        val crashes = integration.store.getRecentCrashReports(limit)

        if (crashes.isEmpty()) {
            println(ConsoleFormatter.success("No crash reports recorded."))
            return
        }

        println(ConsoleFormatter.header("Refinery Crash Reports (last $limit)"))

        for (crash in crashes) {
            val timeAgo = java.time.Duration.between(crash.timestamp, java.time.Instant.now())
            val ago = when {
                timeAgo.toMinutes() < 1 -> "just now"
                timeAgo.toMinutes() < 60 -> "${timeAgo.toMinutes()}m ago"
                timeAgo.toHours() < 24 -> "${timeAgo.toHours()}h ago"
                else -> "${timeAgo.toDays()}d ago"
            }

            println("  ${RED}✖$RESET ${BOLD}${crash.serviceName}$RESET ${DIM}($ago)$RESET")
            println("    Level: ${RED}${crash.level}$RESET | TPS: ${crash.tps} | Players: ${crash.players}")
            println("    Actions: ${crash.actions.joinToString(", ")}")

            // Show first 3 lines of dump summary if available
            if (crash.dumpSummary.isNotBlank()) {
                val preview = crash.dumpSummary.lines().take(3).joinToString("\n    ${DIM}")
                println("    ${DIM}$preview$RESET")
            }
            println()
        }
    }
}
