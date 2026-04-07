package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConfigWriter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.loadbalancer.TcpLoadBalancer
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import java.nio.file.Path

class LbCommand(
    private val config: NimbusConfig,
    private val configPath: Path,
    private val loadBalancer: TcpLoadBalancer?,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager
) : Command {
    override val name = "lb"
    override val description = "Manage the TCP load balancer"
    override val usage = "lb [enable|disable|strategy <name>]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty() || args[0].lowercase() == "status") {
            showStatus(output)
            return true
        }

        when (args[0].lowercase()) {
            "enable" -> {
                if (config.loadbalancer.enabled) {
                    output.info("Load balancer is already enabled.")
                } else {
                    ConfigWriter.updateSection(configPath, "loadbalancer", mapOf(
                        "enabled" to "true",
                        "bind" to "\"${config.loadbalancer.bind}\"",
                        "port" to "${config.loadbalancer.port}",
                        "strategy" to "\"${config.loadbalancer.strategy}\"",
                        "proxy_protocol" to "${config.loadbalancer.proxyProtocol}",
                        "connection_timeout" to "${config.loadbalancer.connectionTimeout}",
                        "buffer_size" to "${config.loadbalancer.bufferSize}"
                    ))
                    output.success("Load balancer enabled on ${config.loadbalancer.bind}:${config.loadbalancer.port}")
                    output.info("Restart required to apply changes.")
                }
            }
            "disable" -> {
                if (!config.loadbalancer.enabled) {
                    output.info("Load balancer is already disabled.")
                } else {
                    ConfigWriter.updateSection(configPath, "loadbalancer", mapOf(
                        "enabled" to "false",
                        "bind" to "\"${config.loadbalancer.bind}\"",
                        "port" to "${config.loadbalancer.port}",
                        "strategy" to "\"${config.loadbalancer.strategy}\"",
                        "proxy_protocol" to "${config.loadbalancer.proxyProtocol}",
                        "connection_timeout" to "${config.loadbalancer.connectionTimeout}",
                        "buffer_size" to "${config.loadbalancer.bufferSize}"
                    ))
                    output.success("Load balancer disabled.")
                    output.info("Restart required to apply changes.")
                }
            }
            "strategy" -> {
                val stratArgs = args.drop(1)
                if (stratArgs.isEmpty()) {
                    output.text("  Current strategy: ${ConsoleFormatter.CYAN}${config.loadbalancer.strategy}${ConsoleFormatter.RESET}")
                    output.info("Available: least-players, round-robin")
                } else {
                    val strategy = stratArgs[0].lowercase()
                    if (strategy !in listOf("least-players", "round-robin")) {
                        output.error("Invalid strategy: $strategy")
                        output.info("Available: least-players, round-robin")
                    } else {
                        ConfigWriter.updateValue(configPath, "loadbalancer", "strategy", "\"$strategy\"")
                        output.success("Load balancer strategy set to '$strategy'.")
                        output.info("Restart required to apply changes.")
                    }
                }
            }
            else -> {
                output.info("Unknown subcommand: ${args[0]}")
                output.text("")
                output.text("  ${ConsoleFormatter.BOLD}Usage:${ConsoleFormatter.RESET}")
                output.text(ConsoleFormatter.commandEntry("lb", "Show load balancer status + backends", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("lb enable", "Enable load balancer", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("lb disable", "Disable load balancer", padWidth = 30))
                output.text(ConsoleFormatter.commandEntry("lb strategy <name>", "Set strategy (least-players, round-robin)", padWidth = 30))
            }
        }
        return true
    }

    private fun showStatus(output: CommandOutput) {
        output.header("Load Balancer")
        if (config.loadbalancer.enabled) {
            output.text(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(true)))
            output.text(ConsoleFormatter.field("Bind", "${config.loadbalancer.bind}:${config.loadbalancer.port}"))
            output.text(ConsoleFormatter.field("Strategy", config.loadbalancer.strategy))
            output.text(ConsoleFormatter.field("PROXY v2", ConsoleFormatter.yesNo(config.loadbalancer.proxyProtocol)))
            if (loadBalancer != null) {
                output.text(ConsoleFormatter.field("Active", "${loadBalancer.activeConnections} connections"))
                output.text(ConsoleFormatter.field("Total", "${loadBalancer.totalConnections} connections"))
                output.text(ConsoleFormatter.field("Rejected", "${loadBalancer.rejectedConnections} connections"))
                output.text(ConsoleFormatter.field("Failed", "${loadBalancer.failedConnections} connections"))
            }
            output.text("")

            // Show backend proxies
            val proxyServices = registry.getAll().filter { service ->
                service.state == ServiceState.READY &&
                    groupManager.getGroup(service.groupName)?.config?.group?.software == ServerSoftware.VELOCITY
            }

            if (proxyServices.isEmpty()) {
                output.info("No backend proxies available")
            } else {
                val headers = listOf("BACKEND", "HOST", "PORT", "PLAYERS", "STATE", "HEALTH", "CONNS")
                val rows = proxyServices.map { svc ->
                    val health = loadBalancer?.healthManager?.get(svc.host, svc.port)
                    val healthStr = when {
                        health == null -> "${ConsoleFormatter.GRAY}N/A${ConsoleFormatter.RESET}"
                        health.draining -> "${ConsoleFormatter.YELLOW}DRAINING${ConsoleFormatter.RESET}"
                        health.status.get() == dev.nimbuspowered.nimbus.loadbalancer.BackendHealthManager.HealthStatus.HEALTHY ->
                            "${ConsoleFormatter.GREEN}HEALTHY${ConsoleFormatter.RESET}"
                        else -> "${ConsoleFormatter.RED}UNHEALTHY${ConsoleFormatter.RESET}"
                    }
                    listOf(
                        ConsoleFormatter.colorize(svc.name, ConsoleFormatter.BOLD),
                        svc.host,
                        svc.port.toString(),
                        svc.playerCount.toString(),
                        ConsoleFormatter.coloredState(svc.state),
                        healthStr,
                        (health?.activeConnections?.get() ?: 0).toString()
                    )
                }
                output.text(ConsoleFormatter.formatTable(headers, rows))
            }
        } else {
            output.text(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(false)))
            output.info("Use 'lb enable' to activate")
        }
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
