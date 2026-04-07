package dev.nimbuspowered.nimbus.module.display

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.display.routes.displayRoutes
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.PluginDeployment
import dev.nimbuspowered.nimbus.module.service

class DisplayModule : NimbusModule {
    override val id = "display"
    override val name = "Display"
    override val version: String get() = NimbusVersion.version
    override val description = "Server selector signs + NPCs via FancyNpcs"

    private lateinit var displayManager: DisplayManager

    override suspend fun init(context: ModuleContext) {
        val groupManager = context.service<GroupManager>()!!
        val eventBus = context.service<EventBus>()!!

        val configDir = context.moduleConfigDir("display")
        displayManager = DisplayManager(configDir)
        displayManager.init()

        // Register plugin deployments
        context.registerPluginDeployment(PluginDeployment(
            resourcePath = "plugins/nimbus-display.jar",
            fileName = "nimbus-display.jar",
            displayName = "NimbusDisplay"
        ))
        context.registerPluginDeployment(PluginDeployment(
            resourcePath = "plugins/FancyNpcs.jar",
            fileName = "FancyNpcs.jar",
            displayName = "FancyNpcs",
            minMinecraftVersion = 20
        ))

        // Auto-generate display configs for existing groups
        val groupConfigs = groupManager.getAllGroups().map { it.config }
        displayManager.ensureDisplays(groupConfigs)

        // Live-sync display configs when groups change
        eventBus.on<NimbusEvent.GroupCreated> { event ->
            val group = groupManager.getGroup(event.groupName) ?: return@on
            displayManager.createDisplay(group.config)
        }

        eventBus.on<NimbusEvent.GroupUpdated> { event ->
            val group = groupManager.getGroup(event.groupName) ?: return@on
            // Ensure config exists (e.g. group type changed from proxy to backend)
            displayManager.createDisplay(group.config)
            // Reload from disk in case the user has customized it
            displayManager.reloadDisplay(event.groupName)
        }

        eventBus.on<NimbusEvent.GroupDeleted> { event ->
            displayManager.deleteDisplay(event.groupName)
        }

        context.registerRoutes({ displayRoutes(displayManager, groupManager) })
    }

    override suspend fun enable() {}

    override fun disable() {}
}
