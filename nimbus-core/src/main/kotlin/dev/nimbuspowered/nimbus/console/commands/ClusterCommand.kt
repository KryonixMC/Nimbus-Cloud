package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.cluster.ClusterServer
import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConfigWriter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import java.nio.file.Path
import java.security.SecureRandom

class ClusterCommand(
    private val config: NimbusConfig,
    private val configPath: Path,
    private val nodeManager: NodeManager?,
    private val registry: ServiceRegistry,
    private val clusterServer: ClusterServer? = null
) : Command {
    override val name = "cluster"
    override val description = "Manage cluster mode (multi-node)"
    override val usage = "cluster <status|enable|disable|token|cert|bootstrap-url> [args]"

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
            "cert" -> {
                val certArgs = args.drop(1)
                when {
                    certArgs.isEmpty() -> handleCertShow(output)
                    certArgs[0].lowercase() == "regenerate" -> {
                        val confirmed = certArgs.size >= 2 && certArgs[1].lowercase() == "confirm"
                        handleCertRegenerate(output, confirmed)
                    }
                    else -> handleCertShow(output)
                }
            }
            "bootstrap-url", "bootstrap" -> handleBootstrapUrl(output)
            else -> {
                output.info("Unknown subcommand: ${args[0]}")
                output.text("")
                output.text("  ${ConsoleFormatter.BOLD}Usage:${ConsoleFormatter.RESET}")
                output.text(ConsoleFormatter.commandEntry("cluster status", "Show cluster status", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster enable", "Enable cluster mode", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster disable", "Disable cluster mode", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster token", "Show auth token", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster token regenerate", "Generate a new auth token", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster cert", "Show TLS cert fingerprint + SANs", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster cert regenerate", "Delete cluster.jks (re-trust required)", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("cluster bootstrap-url", "Print agent setup URL + token", padWidth = 30))
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

    private fun handleCertShow(output: CommandOutput) {
        if (!config.cluster.enabled) {
            output.info("Cluster mode is not enabled.")
            return
        }
        val info = clusterServer?.certInfo
        if (info == null) {
            output.info("Cluster server is not running (TLS may be disabled or failed to start).")
            return
        }
        output.header("Cluster TLS Certificate")
        output.text(ConsoleFormatter.field("Fingerprint", info.fingerprint))
        output.text(ConsoleFormatter.field("Valid Until", info.validUntil))
        if (info.sans.isNotEmpty()) {
            output.text(ConsoleFormatter.field("SANs", info.sans.joinToString(", ")))
        }
        output.info("Agents pin this fingerprint via 'trusted_fingerprint' in agent.toml")
        output.info("(the setup wizard does this automatically via /api/cluster/bootstrap).")
    }

    private fun handleCertRegenerate(output: CommandOutput, confirmed: Boolean) {
        if (!config.cluster.enabled) {
            output.info("Cluster mode is not enabled.")
            return
        }
        val keystorePath = if (config.cluster.keystorePath.isNotBlank()) {
            Path.of(config.cluster.keystorePath)
        } else {
            Path.of("config", "cluster.jks")
        }
        if (!java.nio.file.Files.exists(keystorePath)) {
            output.info("No keystore found at $keystorePath — a fresh one will be generated on next start.")
            return
        }

        if (!confirmed) {
            // Dry-run mode — show what would happen and require explicit confirm
            output.header("Cluster Cert Regeneration")
            output.text("  ${ConsoleFormatter.BOLD}This will delete:${ConsoleFormatter.RESET} $keystorePath")
            output.text("")
            output.info("⚠  All agents currently pinned to the old cert will fail to connect.")
            output.info("⚠  Every agent must re-run 'java -jar nimbus-agent.jar --setup' to re-pin.")
            output.text("")
            output.info("To confirm, run:")
            output.text("  ${ConsoleFormatter.CYAN}cluster cert regenerate confirm${ConsoleFormatter.RESET}")
            return
        }

        output.info("Regenerating the cluster certificate (confirmed).")
        try {
            java.nio.file.Files.delete(keystorePath)
            output.success("Deleted $keystorePath")
            output.info("Restart Nimbus to generate a new certificate. All agents must then re-pin.")
        } catch (e: Exception) {
            output.error("Failed to delete keystore: ${e.message}")
        }
    }

    private fun handleBootstrapUrl(output: CommandOutput) {
        if (!config.cluster.enabled) {
            output.info("Cluster mode is not enabled. Run 'cluster enable' first.")
            return
        }
        if (config.cluster.token.isBlank()) {
            output.info("No cluster token configured. Run 'cluster enable' or 'cluster token regenerate'.")
            return
        }

        val apiHost = when {
            config.api.bind.isBlank() -> "127.0.0.1"
            config.api.bind == "0.0.0.0" -> {
                try { java.net.InetAddress.getLocalHost().hostName } catch (_: Exception) { "127.0.0.1" }
            }
            else -> config.api.bind
        }
        val url = "http://$apiHost:${config.api.port}"

        output.header("Agent Bootstrap")
        output.text(ConsoleFormatter.field("REST URL", ConsoleFormatter.CYAN + url + ConsoleFormatter.RESET))
        output.text(ConsoleFormatter.field("Token", ConsoleFormatter.CYAN + config.cluster.token + ConsoleFormatter.RESET))
        output.text("")
        output.info("Paste these into the agent setup wizard (java -jar nimbus-agent.jar).")
        if (config.api.bind == "0.0.0.0") {
            output.info("Note: api.bind is 0.0.0.0 — if agents connect remotely, replace the host with the public IP.")
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
