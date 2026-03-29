package dev.nimbus.console.commands

import dev.nimbus.config.NimbusConfig
import dev.nimbus.config.ServerSoftware
import dev.nimbus.console.Command
import dev.nimbus.console.ConfigWriter
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.group.GroupManager
import dev.nimbus.loadbalancer.TcpLoadBalancer
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState
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
            println("  Status:     ${ConsoleFormatter.success("ENABLED")}")
            println("  Bind:       ${config.loadbalancer.bind}:${config.loadbalancer.port}")
            println("  Strategy:   ${config.loadbalancer.strategy}")
            println("  PROXY v2:   ${if (config.loadbalancer.proxyProtocol) "yes" else "no"}")
            if (loadBalancer != null) {
                println("  Active:     ${loadBalancer.activeConnections} connections")
                println("  Total:      ${loadBalancer.totalConnections} connections")
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
                val headers = listOf("BACKEND", "HOST", "PORT", "PLAYERS", "STATE")
                val rows = proxyServices.map { svc ->
                    listOf(
                        ConsoleFormatter.colorize(svc.name, ConsoleFormatter.BOLD),
                        svc.host,
                        svc.port.toString(),
                        svc.playerCount.toString(),
                        ConsoleFormatter.coloredState(svc.state)
                    )
                }
                println(ConsoleFormatter.formatTable(headers, rows))
            }
        } else {
            println("  Status:     ${ConsoleFormatter.warn("DISABLED")}")
            println(ConsoleFormatter.colorize("  Use 'lb enable' to activate", ConsoleFormatter.DIM))
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
        println("    lb                          Show load balancer status + backends")
        println("    lb enable                   Enable load balancer")
        println("    lb disable                  Disable load balancer")
        println("    lb strategy <name>          Set strategy (least-players, round-robin)")
    }
}
