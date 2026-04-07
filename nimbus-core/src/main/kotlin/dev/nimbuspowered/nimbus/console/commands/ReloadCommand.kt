package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.ConfigLoader
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.proxy.ProxySyncManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import java.nio.file.Path

class ReloadCommand(
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry,
    private val groupsDir: Path,
    private val proxySyncManager: ProxySyncManager?,
    private val eventBus: EventBus
) : Command {

    override val name = "reload"
    override val description = "Hot-reload group and proxy configuration files"
    override val usage = "reload"

    override suspend fun execute(args: List<String>) {
        println(ConsoleFormatter.info("Reloading configurations..."))

        val configs = try {
            ConfigLoader.loadGroupConfigs(groupsDir)
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to load configs: ${e.message}"))
            println(ConsoleFormatter.warn("Keeping current configuration."))
            return
        }

        // Snapshot current group names before reload
        val previousGroupNames = groupManager.getAllGroups().map { it.name }.toSet()

        groupManager.reloadGroups(configs)

        val loadedGroups = groupManager.getAllGroups()
        println(ConsoleFormatter.success("Loaded ${configs.size} group configuration(s)."))

        // Show instance count per group
        for (group in loadedGroups.sortedBy { it.name }) {
            val instances = registry.getByGroup(group.name).size
            val icon = if (instances > 0) ConsoleFormatter.success("●") else ConsoleFormatter.hint("○")
            val countText = if (instances > 0) {
                ConsoleFormatter.success("$instances running")
            } else {
                ConsoleFormatter.hint("0 running")
            }
            println("$icon ${ConsoleFormatter.colorize(group.name, ConsoleFormatter.BOLD)}  $countText")
        }

        // Warn about groups with running services that were removed from config
        val configuredNames = configs.map { it.group.name }.toSet()
        val orphanedGroups = previousGroupNames - configuredNames
        for (groupName in orphanedGroups.sorted()) {
            val runningServices = registry.getByGroup(groupName)
            if (runningServices.isNotEmpty()) {
                println()
                println(
                    ConsoleFormatter.warn(
                        "Group '$groupName' was removed from config but has " +
                            "${runningServices.size} running service(s). They will continue until stopped."
                    )
                )
            }
        }

        // Reload proxy sync config and push to connected proxies
        if (proxySyncManager != null) {
            proxySyncManager.reload()
            val cfg = proxySyncManager.getConfig()
            eventBus.emit(NimbusEvent.TabListUpdated(
                header = cfg.tabList.header,
                footer = cfg.tabList.footer,
                playerFormat = cfg.tabList.playerFormat,
                updateInterval = cfg.tabList.updateInterval
            ))
            eventBus.emit(NimbusEvent.MotdUpdated(
                line1 = cfg.motd.line1,
                line2 = cfg.motd.line2,
                maxPlayers = cfg.motd.maxPlayers,
                playerCountOffset = cfg.motd.playerCountOffset
            ))
            eventBus.emit(NimbusEvent.ChatFormatUpdated(
                format = cfg.chat.format,
                enabled = cfg.chat.enabled
            ))
            println(ConsoleFormatter.success("Proxy sync config reloaded and pushed to proxies."))
        }
    }
}
