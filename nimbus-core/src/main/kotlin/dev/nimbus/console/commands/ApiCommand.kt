package dev.nimbus.console.commands

import dev.nimbus.api.NimbusApi
import dev.nimbus.config.ApiConfig
import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter

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

        println(ConsoleFormatter.info("Starting REST API on ${config.bind}:${config.port}..."))
        api.startWithConfig(config)

        if (api.isRunning) {
            println(ConsoleFormatter.success("REST API started on http://${config.bind}:${config.port}"))
            if (config.token.isBlank()) {
                println(ConsoleFormatter.warn("Warning: No auth token set — API is open!"))
                println(ConsoleFormatter.info("Set [api] token in nimbus.toml for security."))
            }
        } else {
            println(ConsoleFormatter.error("Failed to start REST API — check logs for details"))
        }
    }

    private fun stopApi() {
        if (!api.isRunning) {
            println(ConsoleFormatter.warn("REST API is not running"))
            return
        }

        api.stop()
        println(ConsoleFormatter.success("REST API stopped"))
    }

    private fun printStatus() {
        if (api.isRunning) {
            println(ConsoleFormatter.success("REST API: RUNNING"))
            println("  Endpoint:  http://${api.currentBind}:${api.currentPort}")
            println("  Auth:      ${if (api.token().isNotBlank()) "Bearer token" else ConsoleFormatter.warn("NONE (open)")}")
            println("  Health:    http://${api.currentBind}:${api.currentPort}/api/health")
            println("  Events:    ws://${api.currentBind}:${api.currentPort}/api/events")
        } else {
            println(ConsoleFormatter.warn("REST API: STOPPED"))
            println("  ${ConsoleFormatter.info("Use 'api start' to launch or set [api] enabled = true in nimbus.toml")}")
        }
    }

    private fun showToken() {
        val token = api.token()
        if (token.isBlank()) {
            println(ConsoleFormatter.warn("No API token configured"))
            println(ConsoleFormatter.info("Set [api] token = \"your-secret\" in nimbus.toml"))
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
