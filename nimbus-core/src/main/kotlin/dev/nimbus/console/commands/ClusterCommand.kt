package dev.nimbus.console.commands

import dev.nimbus.cluster.NodeManager
import dev.nimbus.config.NimbusConfig
import dev.nimbus.console.Command
import dev.nimbus.console.ConfigWriter
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.service.ServiceRegistry
import java.nio.file.Path
import java.security.SecureRandom

class ClusterCommand(
    private val config: NimbusConfig,
    private val configPath: Path,
    private val nodeManager: NodeManager?,
    private val registry: ServiceRegistry
) : Command {
    override val name = "cluster"
    override val description = "Manage cluster mode (multi-node)"
    override val usage = "cluster <status|enable|disable|token> [args]"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printStatus()
            return
        }

        when (args[0].lowercase()) {
            "status" -> printStatus()
            "enable" -> enableCluster()
            "disable" -> disableCluster()
            "token" -> handleToken(args.drop(1))
            else -> {
                println(ConsoleFormatter.warn("Unknown subcommand: ${args[0]}"))
                printUsage()
            }
        }
    }

    private fun printStatus() {
        println(ConsoleFormatter.header("Cluster Mode"))
        if (config.cluster.enabled) {
            println("  Status:     ${ConsoleFormatter.success("ENABLED")}")
            println("  Agent Port: ${config.cluster.agentPort}")
            println("  Bind:       ${config.cluster.bind}")
            println("  Placement:  ${config.cluster.placementStrategy}")
            println("  Heartbeat:  ${config.cluster.heartbeatInterval}ms")
            println("  Timeout:    ${config.cluster.nodeTimeout}ms")
            val tokenMasked = maskToken(config.cluster.token)
            println("  Token:      $tokenMasked")
            if (nodeManager != null) {
                println("  Nodes:      ${nodeManager.getOnlineNodeCount()}/${nodeManager.getNodeCount()} online")
                val nodeServices = registry.getAll().filter { it.nodeId != "local" }
                println("  Remote:     ${nodeServices.size} service(s) on remote nodes")
            }
        } else {
            println("  Status:     ${ConsoleFormatter.warn("DISABLED")}")
            println(ConsoleFormatter.colorize("  Use 'cluster enable' to activate", ConsoleFormatter.DIM))
        }
    }

    private fun enableCluster() {
        if (config.cluster.enabled) {
            println(ConsoleFormatter.warn("Cluster mode is already enabled."))
            return
        }

        val token = if (config.cluster.token.isBlank()) generateToken() else config.cluster.token

        ConfigWriter.updateSection(configPath, "cluster", mapOf(
            "enabled" to "true",
            "token" to "\"$token\"",
            "agent_port" to "${config.cluster.agentPort}",
            "bind" to "\"${config.cluster.bind}\"",
            "heartbeat_interval" to "${config.cluster.heartbeatInterval}",
            "node_timeout" to "${config.cluster.nodeTimeout}",
            "placement_strategy" to "\"${config.cluster.placementStrategy}\""
        ))

        println(ConsoleFormatter.success("Cluster mode enabled."))
        if (config.cluster.token.isBlank()) {
            println("  Generated token: ${ConsoleFormatter.CYAN}$token${ConsoleFormatter.RESET}")
            println(ConsoleFormatter.colorize("  Save this token — agents need it to connect.", ConsoleFormatter.DIM))
        }
        println()
        ConfigWriter.printRestartHint(configPath)
    }

    private fun disableCluster() {
        if (!config.cluster.enabled) {
            println(ConsoleFormatter.warn("Cluster mode is already disabled."))
            return
        }

        ConfigWriter.updateSection(configPath, "cluster", mapOf(
            "enabled" to "false",
            "token" to "\"${config.cluster.token}\"",
            "agent_port" to "${config.cluster.agentPort}",
            "bind" to "\"${config.cluster.bind}\"",
            "heartbeat_interval" to "${config.cluster.heartbeatInterval}",
            "node_timeout" to "${config.cluster.nodeTimeout}",
            "placement_strategy" to "\"${config.cluster.placementStrategy}\""
        ))

        println(ConsoleFormatter.success("Cluster mode disabled."))
        ConfigWriter.printRestartHint(configPath)
    }

    private fun handleToken(args: List<String>) {
        if (args.isNotEmpty() && args[0].lowercase() == "regenerate") {
            val newToken = generateToken()
            ConfigWriter.updateValue(configPath, "cluster", "token", "\"$newToken\"")
            println(ConsoleFormatter.success("Token regenerated."))
            println("  New token: ${ConsoleFormatter.CYAN}$newToken${ConsoleFormatter.RESET}")
            println(ConsoleFormatter.colorize("  Update all agents with this token.", ConsoleFormatter.DIM))
            println()
            ConfigWriter.printRestartHint(configPath)
            return
        }

        val token = config.cluster.token
        if (token.isBlank()) {
            println(ConsoleFormatter.warn("No cluster token configured."))
            println(ConsoleFormatter.info("Use 'cluster enable' to generate one, or 'cluster token regenerate'."))
        } else {
            println("  Token: ${ConsoleFormatter.CYAN}$token${ConsoleFormatter.RESET}")
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun maskToken(token: String): String {
        if (token.isBlank()) return ConsoleFormatter.warn("(none)")
        return if (token.length > 8) {
            token.take(4) + "*".repeat(token.length - 8) + token.takeLast(4)
        } else {
            "*".repeat(token.length)
        }
    }

    private fun printUsage() {
        println()
        println("  ${ConsoleFormatter.BOLD}Usage:${ConsoleFormatter.RESET}")
        println("    cluster status              Show cluster status")
        println("    cluster enable              Enable cluster mode")
        println("    cluster disable             Disable cluster mode")
        println("    cluster token               Show auth token")
        println("    cluster token regenerate    Generate a new auth token")
    }
}
