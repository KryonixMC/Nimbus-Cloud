package dev.nimbuspowered.nimbus.module.notifications.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.module.SubcommandMeta
import dev.nimbuspowered.nimbus.module.notifications.NotificationsConfigManager
import dev.nimbuspowered.nimbus.module.notifications.NotificationsManager
import kotlinx.coroutines.runBlocking

class NotificationsCommand(
    private val manager: NotificationsManager,
    private val configManager: NotificationsConfigManager
) : Command {

    override val name = "notifications"
    override val description = "Manage Discord/Slack webhook notifications"
    override val usage = "notifications <list|test|reload|status>"
    override val permission = "nimbus.cloud.notifications"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("list", "List all configured webhooks", "notifications list"),
        SubcommandMeta("test", "Send a test notification to a webhook", "notifications test <id>"),
        SubcommandMeta("reload", "Reload the notifications config", "notifications reload"),
        SubcommandMeta("status", "Show sent/failed counts", "notifications status")
    )

    // ── Console execution ────────────────────────────────

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printUsage()
            return
        }

        when (args[0].lowercase()) {
            "list"   -> executeList()
            "test"   -> executeTest(args.drop(1))
            "reload" -> executeReload()
            "status" -> executeStatus()
            else     -> printUsage()
        }
    }

    private fun executeList() {
        val webhooks = configManager.getConfig().webhooks
        println(ConsoleFormatter.header("Notification Webhooks"))
        if (webhooks.isEmpty()) {
            println(ConsoleFormatter.infoLine("No webhooks configured. Edit config/modules/notifications/nimbus.toml."))
            return
        }
        for (webhook in webhooks) {
            val events = if (webhook.events.isEmpty()) "all events" else webhook.events.joinToString(", ")
            println(
                "  ${ConsoleFormatter.BOLD}${webhook.id}${ConsoleFormatter.RESET}" +
                " ${ConsoleFormatter.DIM}(${webhook.type}, min=${webhook.minSeverity})${ConsoleFormatter.RESET}" +
                " — $events"
            )
        }
    }

    private fun executeTest(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.warnLine("Usage: notifications test <webhook-id>"))
            return
        }
        val id = args[0]
        val webhook = configManager.getConfig().webhooks.find { it.id == id }
        if (webhook == null) {
            println(ConsoleFormatter.errorLine("No webhook found with id '$id'"))
            return
        }
        println(ConsoleFormatter.infoLine("Sending test notification to '$id'..."))
        val ok = runBlocking { manager.testWebhook(id) }
        if (ok) {
            println(ConsoleFormatter.successLine("Test notification sent to '$id'"))
        } else {
            println(ConsoleFormatter.errorLine("Test notification failed for '$id' — check logs for details"))
        }
    }

    private fun executeReload() {
        configManager.reload()
        manager.reload(configManager.getConfig())
        val count = configManager.getConfig().webhooks.size
        println(ConsoleFormatter.successLine("Notifications config reloaded ($count webhook(s))"))
    }

    private fun executeStatus() {
        val cfg = configManager.getConfig()
        println(ConsoleFormatter.header("Notifications Status"))
        println("  ${ConsoleFormatter.BOLD}Enabled:${ConsoleFormatter.RESET} ${cfg.global.enabled}")
        println("  ${ConsoleFormatter.BOLD}Webhooks:${ConsoleFormatter.RESET} ${cfg.webhooks.size}")
        println("  ${ConsoleFormatter.BOLD}Total sent:${ConsoleFormatter.RESET} ${manager.totalSent}")
        println("  ${ConsoleFormatter.BOLD}Total failed:${ConsoleFormatter.RESET} ${manager.totalFailed}")
    }

    private fun printUsage() {
        println(ConsoleFormatter.warnLine("Usage: $usage"))
        println("  list              List all configured webhooks")
        println("  test <id>         Send a test notification")
        println("  reload            Reload config from disk")
        println("  status            Show sent/failed counts")
    }

    // ── Remote execution (Bridge/API) ────────────────────

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            remoteHelp(output)
            return true
        }

        when (args[0].lowercase()) {
            "list"   -> remoteList(output)
            "test"   -> remoteTest(args.drop(1), output)
            "reload" -> remoteReload(output)
            "status" -> remoteStatus(output)
            else     -> remoteHelp(output)
        }
        return true
    }

    private fun remoteList(out: CommandOutput) {
        val webhooks = configManager.getConfig().webhooks
        out.header("Notification Webhooks")
        if (webhooks.isEmpty()) {
            out.info("No webhooks configured.")
            return
        }
        for (webhook in webhooks) {
            val events = if (webhook.events.isEmpty()) "all events" else webhook.events.joinToString(", ")
            out.item("${webhook.id} (${webhook.type}, min=${webhook.minSeverity}) — $events")
        }
    }

    private suspend fun remoteTest(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) {
            out.error("Usage: notifications test <webhook-id>")
            return
        }
        val id = args[0]
        if (configManager.getConfig().webhooks.none { it.id == id }) {
            out.error("No webhook found with id '$id'")
            return
        }
        val ok = manager.testWebhook(id)
        if (ok) {
            out.success("Test notification sent to '$id'")
        } else {
            out.error("Test notification failed for '$id' — check server logs")
        }
    }

    private fun remoteReload(out: CommandOutput) {
        configManager.reload()
        manager.reload(configManager.getConfig())
        out.success("Notifications config reloaded (${configManager.getConfig().webhooks.size} webhook(s))")
    }

    private fun remoteStatus(out: CommandOutput) {
        val cfg = configManager.getConfig()
        out.header("Notifications Status")
        out.item("Enabled: ${cfg.global.enabled}")
        out.item("Webhooks: ${cfg.webhooks.size}")
        out.item("Total sent: ${manager.totalSent}")
        out.item("Total failed: ${manager.totalFailed}")
    }

    private fun remoteHelp(out: CommandOutput) {
        out.header("Notifications Commands")
        out.item("list — List all configured webhooks")
        out.item("test <id> — Send a test notification")
        out.item("reload — Reload config from disk")
        out.item("status — Show sent/failed counts")
    }
}
