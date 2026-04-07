package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConfigWriter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
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

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printStatus()
            return
        }

        when (args[0].lowercase()) {
            "status" -> printStatus()
            "enable" -> enableLb()
            "disable" -> disableLb()
            "strategy" -> setStrategy(args.drop(1))
            else -> {
                println(ConsoleFormatter.warn("Unknown subcommand: ${args[0]}"))
                printUsage()
            }
        }
    }

    private fun printStatus() {
        println(ConsoleFormatter.header("Load Balancer"))
        if (config.loadbalancer.enabled) {
            println(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(true)))
            println(ConsoleFormatter.field("Bind", "${config.loadbalancer.bind}:${config.loadbalancer.port}"))
            println(ConsoleFormatter.field("Strategy", config.loadbalancer.strategy))
            println(ConsoleFormatter.field("PROXY v2", ConsoleFormatter.yesNo(config.loadbalancer.proxyProtocol)))
            if (loadBalancer != null) {
                println(ConsoleFormatter.field("Active", "${loadBalancer.activeConnections} connections"))
                println(ConsoleFormatter.field("Total", "${loadBalancer.totalConnections} connections"))
                println(ConsoleFormatter.field("Rejected", "${loadBalancer.rejectedConnections} connections"))
                println(ConsoleFormatter.field("Failed", "${loadBalancer.failedConnections} connections"))
            }
            println()

            // Show backend proxies
            val proxyServices = registry.getAll().filter { service ->
                service.state == ServiceState.READY &&
                    groupManager.getGroup(service.groupName)?.config?.group?.software == ServerSoftware.VELOCITY
            }

            if (proxyServices.isEmpty()) {
                println(ConsoleFormatter.warn("  No backend proxies available"))
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
                println(ConsoleFormatter.formatTable(headers, rows))
            }
        } else {
            println(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(false)))
            println(ConsoleFormatter.hint("  Use 'lb enable' to activate"))
        }
    }

    private fun enableLb() {
        if (config.loadbalancer.enabled) {
            println(ConsoleFormatter.warn("Load balancer is already enabled."))
            return
        }

        ConfigWriter.updateSection(configPath, "loadbalancer", mapOf(
            "enabled" to "true",
            "bind" to "\"${config.loadbalancer.bind}\"",
            "port" to "${config.loadbalancer.port}",
            "strategy" to "\"${config.loadbalancer.strategy}\"",
            "proxy_protocol" to "${config.loadbalancer.proxyProtocol}",
            "connection_timeout" to "${config.loadbalancer.connectionTimeout}",
            "buffer_size" to "${config.loadbalancer.bufferSize}"
        ))

        println(ConsoleFormatter.success("Load balancer enabled on ${config.loadbalancer.bind}:${config.loadbalancer.port}"))
        ConfigWriter.printRestartHint(configPath)
    }

    private fun disableLb() {
        if (!config.loadbalancer.enabled) {
            println(ConsoleFormatter.warn("Load balancer is already disabled."))
            return
        }

        ConfigWriter.updateSection(configPath, "loadbalancer", mapOf(
            "enabled" to "false",
            "bind" to "\"${config.loadbalancer.bind}\"",
            "port" to "${config.loadbalancer.port}",
            "strategy" to "\"${config.loadbalancer.strategy}\"",
            "proxy_protocol" to "${config.loadbalancer.proxyProtocol}",
            "connection_timeout" to "${config.loadbalancer.connectionTimeout}",
            "buffer_size" to "${config.loadbalancer.bufferSize}"
        ))

        println(ConsoleFormatter.success("Load balancer disabled."))
        ConfigWriter.printRestartHint(configPath)
    }

    private fun setStrategy(args: List<String>) {
        if (args.isEmpty()) {
            println("  Current strategy: ${ConsoleFormatter.CYAN}${config.loadbalancer.strategy}${ConsoleFormatter.RESET}")
            println(ConsoleFormatter.info("  Available: least-players, round-robin"))
            return
        }

        val strategy = args[0].lowercase()
        if (strategy !in listOf("least-players", "round-robin")) {
            println(ConsoleFormatter.error("Invalid strategy: $strategy"))
            println(ConsoleFormatter.info("Available: least-players, round-robin"))
            return
        }

        ConfigWriter.updateValue(configPath, "loadbalancer", "strategy", "\"$strategy\"")
        println(ConsoleFormatter.success("Load balancer strategy set to '$strategy'."))
        ConfigWriter.printRestartHint(configPath)
    }

    private fun printUsage() {
        println()
        println("  ${ConsoleFormatter.BOLD}Usage:${ConsoleFormatter.RESET}")
        println(ConsoleFormatter.commandEntry("lb", "Show load balancer status + backends", padWidth = 30))
        println(ConsoleFormatter.commandEntry("lb enable", "Enable load balancer", padWidth = 30))
        println(ConsoleFormatter.commandEntry("lb disable", "Disable load balancer", padWidth = 30))
        println(ConsoleFormatter.commandEntry("lb strategy <name>", "Set strategy (least-players, round-robin)", padWidth = 30))
    }
}
