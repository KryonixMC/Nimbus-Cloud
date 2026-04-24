package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.api.NimbusApi
import dev.nimbuspowered.nimbus.config.ApiConfig
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.api.CommandOutput

class ApiCommand(
    private val api: NimbusApi
) : Command {

    override val name = "api"
    override val description = "Manage the REST API (start, stop, status, token)"
    override val usage = "api <start|stop|status|token> [port]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            printStatus(output)
            return true
        }

        when (args[0].lowercase()) {
            "start" -> {
                if (api.isRunning) {
                    output.info("REST API is already running on ${api.currentBind}:${api.currentPort}")
                    return true
                }
                val port = args.getOrNull(1)?.toIntOrNull() ?: api.currentPort
                val config = ApiConfig(
                    enabled = true,
                    bind = api.currentBind,
                    port = port,
                    token = api.token()
                )
                api.startWithConfig(config)
                output.success("REST API starting on ${api.currentBind}:$port")
            }
            "stop" -> {
                if (!api.isRunning) {
                    output.info("REST API is not running")
                    return true
                }
                api.stop()
                output.success("REST API stopped")
            }
            "status" -> printStatus(output)
            "token" -> {
                val token = api.token()
                if (token.isBlank()) {
                    output.info("No API token configured")
                    output.info("Set [api] token = \"your-secret\" in config/nimbus.toml")
                } else {
                    val masked = if (token.length > 8) {
                        token.take(4) + "*".repeat(token.length - 8) + token.takeLast(4)
                    } else {
                        "*".repeat(token.length)
                    }
                    output.text("API Token: $masked")
                }
            }
            else -> {
                output.info("Unknown subcommand: ${args[0]}")
                output.info("Usage: $usage")
            }
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }

    private fun printStatus(output: CommandOutput) {
        output.header("REST API")
        if (api.isRunning) {
            output.text(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(true)))
            output.text(ConsoleFormatter.field("Endpoint", "${ConsoleFormatter.CYAN}http://${api.currentBind}:${api.currentPort}${ConsoleFormatter.RESET}"))
            output.text(ConsoleFormatter.field("Auth", if (api.token().isNotBlank()) "Bearer token" else ConsoleFormatter.warn("NONE (open)")))
            output.text(ConsoleFormatter.field("Health", "${ConsoleFormatter.CYAN}http://${api.currentBind}:${api.currentPort}/api/health${ConsoleFormatter.RESET}"))
            output.text(ConsoleFormatter.field("Events", "${ConsoleFormatter.CYAN}ws://${api.currentBind}:${api.currentPort}/api/events${ConsoleFormatter.RESET}"))
        } else {
            output.text(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(false)))
            output.info("Use 'api start' to launch or set [api] enabled = true in config/nimbus.toml")
        }
    }
}
