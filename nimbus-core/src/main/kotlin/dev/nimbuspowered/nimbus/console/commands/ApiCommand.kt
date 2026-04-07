package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.api.NimbusApi
import dev.nimbuspowered.nimbus.config.ApiConfig
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter

class ApiCommand(
    private val api: NimbusApi
) : Command {

    override val name = "api"
    override val description = "Manage the REST API (start, stop, status, token)"
    override val usage = "api <start|stop|status|token> [port]"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printStatus()
            return
        }

        when (args[0].lowercase()) {
            "start" -> startApi(args)
            "stop" -> stopApi()
            "status" -> printStatus()
            "token" -> showToken()
            else -> {
                println(ConsoleFormatter.warn("Unknown subcommand: ${args[0]}"))
                println(ConsoleFormatter.info("Usage: $usage"))
            }
        }
    }

    private fun startApi(args: List<String>) {
        if (api.isRunning) {
            println(ConsoleFormatter.warn("REST API is already running on ${api.currentBind}:${api.currentPort}"))
            return
        }

        val port = args.getOrNull(1)?.toIntOrNull() ?: api.currentPort
        val config = ApiConfig(
            enabled = true,
            bind = api.currentBind,
            port = port,
            token = api.token()
        )

        api.startWithConfig(config)
    }

    private fun stopApi() {
        if (!api.isRunning) {
            println(ConsoleFormatter.warn("REST API is not running"))
            return
        }

        api.stop()
    }

    private fun printStatus() {
        println(ConsoleFormatter.header("REST API"))
        if (api.isRunning) {
            println(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(true)))
            println(ConsoleFormatter.field("Endpoint", "${ConsoleFormatter.CYAN}http://${api.currentBind}:${api.currentPort}${ConsoleFormatter.RESET}"))
            println(ConsoleFormatter.field("Auth", if (api.token().isNotBlank()) "Bearer token" else ConsoleFormatter.warn("NONE (open)")))
            println(ConsoleFormatter.field("Health", "${ConsoleFormatter.CYAN}http://${api.currentBind}:${api.currentPort}/api/health${ConsoleFormatter.RESET}"))
            println(ConsoleFormatter.field("Events", "${ConsoleFormatter.CYAN}ws://${api.currentBind}:${api.currentPort}/api/events${ConsoleFormatter.RESET}"))
        } else {
            println(ConsoleFormatter.field("Status", ConsoleFormatter.enabledDisabled(false)))
            println(ConsoleFormatter.hint("  Use 'api start' to launch or set [api] enabled = true in config/nimbus.toml"))
        }
    }


    private fun showToken() {
        val token = api.token()
        if (token.isBlank()) {
            println(ConsoleFormatter.warn("No API token configured"))
            println(ConsoleFormatter.info("Set [api] token = \"your-secret\" in config/nimbus.toml"))
        } else {
            // Show first 4 and last 4 chars, mask the rest
            val masked = if (token.length > 8) {
                token.take(4) + "*".repeat(token.length - 8) + token.takeLast(4)
            } else {
                "*".repeat(token.length)
            }
            println("API Token: $masked")
        }
    }
}
