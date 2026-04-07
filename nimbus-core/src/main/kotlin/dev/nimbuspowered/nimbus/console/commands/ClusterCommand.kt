package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConfigWriter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceRegistry
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

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty() || args[0].lowercase() == "status") {
            showStatus(output)
            return true
        }

        when (args[0].lowercase()) {
            "enable" -> {
                if (config.cluster.enabled) {
                    output.info("Cluster mode is already enabled.")
                } else {
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
                    output.success("Cluster mode enabled.")
                    if (config.cluster.token.isBlank()) {
                        output.text("  Generated token: ${ConsoleFormatter.CYAN}$token${ConsoleFormatter.RESET}")
                        output.info("Save this token — agents need it to connect.")
                    }
                    output.info("Restart required to apply changes.")
                }
            }
            "disable" -> {
                if (!config.cluster.enabled) {
                    output.info("Cluster mode is already disabled.")
                } else {
                    ConfigWriter.updateSection(configPath, "cluster", mapOf(
                        "enabled" to "false",
                        "token" to "\"${config.cluster.token}\"",
                        "agent_port" to "${config.cluster.agentPort}",
                        "bind" to "\"${config.cluster.bind}\"",
                        "heartbeat_interval" to "${config.cluster.heartbeatInterval}",
                        "node_timeout" to "${config.cluster.nodeTimeout}",
                        "placement_strategy" to "\"${config.cluster.placementStrategy}\""
                    ))
                    output.success("Cluster mode disabled.")
                    output.info("Restart required to apply changes.")
                }
            }
            "token" -> {
                val tokenArgs = args.drop(1)
                if (tokenArgs.isNotEmpty() && tokenArgs[0].lowercase() == "regenerate") {
                    val newToken = generateToken()
                    ConfigWriter.updateValue(configPath, "cluster", "token", "\"$newToken\"")
                    output.success("Token regenerated.")
                    output.text("  New token: ${ConsoleFormatter.CYAN}$newToken${ConsoleFormatter.RESET}")
                    output.info("Update all agents with this token.")
                    output.info("Restart required to apply changes.")
                } else {
                    val token = config.cluster.token
                    if (token.isBlank()) {
                        output.info("No cluster token configured.")
                        output.info("Use 'cluster enable' to generate one, or 'cluster token regenerate'.")
                    } else {
                        output.text("  Token: ${ConsoleFormatter.CYAN}$token${ConsoleFormatter.RESET}")
                    }
                }
            }
            else -> {
                output.info("Unknown subcommand: ${args[0]}")
                output.text("")
                output.text("  ${ConsoleFormatter.BOLD}Usage:${ConsoleFormatter.RESET}")
                output.text(ConsoleFormatter.commandEntry("cluster status", "Show cluster status", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster enable", "Enable cluster mode", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster disable", "Disable cluster mode", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster token", "Show auth token", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster token regenerate", "Generate a new auth token", padWidth = 30))
            }
        }
        return true
    }

    private fun showStatus(output: CommandOutput) {
        output.header("Cluster Mode")
        if (config.cluster.enabled) {
            output.text(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(true)))
            output.text(ConsoleFormatter.field("Agent Port", config.cluster.agentPort.toString()))
            output.text(ConsoleFormatter.field("Bind", config.cluster.bind))
            output.text(ConsoleFormatter.field("Placement", config.cluster.placementStrategy))
            output.text(ConsoleFormatter.field("Heartbeat", "${config.cluster.heartbeatInterval}ms"))
            output.text(ConsoleFormatter.field("Timeout", "${config.cluster.nodeTimeout}ms"))
            val tokenMasked = maskToken(config.cluster.token)
            output.text(ConsoleFormatter.field("Token", tokenMasked))
            if (nodeManager != null) {
                output.text(ConsoleFormatter.field("Nodes", "${nodeManager.getOnlineNodeCount()}/${nodeManager.getNodeCount()} online"))
                val nodeServices = registry.getAll().filter { it.nodeId != "local" }
                output.text(ConsoleFormatter.field("Remote", "${nodeServices.size} service(s) on remote nodes"))
            }
        } else {
            output.text(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(false)))
            output.info("Use 'cluster enable' to activate")
        }
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
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

}
