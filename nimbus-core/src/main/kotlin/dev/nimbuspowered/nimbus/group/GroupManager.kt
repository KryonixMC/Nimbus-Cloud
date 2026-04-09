package dev.nimbuspowered.nimbus.group

import dev.nimbuspowered.nimbus.config.GroupConfig
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.template.ModScanner
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class GroupManager(private val templatesDir: Path? = null) {

    private val logger = LoggerFactory.getLogger(GroupManager::class.java)
    private val groups = ConcurrentHashMap<String, ServerGroup>()

    private val MODDED_SOFTWARE = setOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)

    fun loadGroups(configs: List<GroupConfig>) {
        groups.clear()
        for (config in configs) {
            val name = config.group.name
            val group = ServerGroup(config)
            scanModIds(group)
            groups[name] = group
            logger.info("Loaded group '{}'", name)
        }
        logger.info("Loaded {} group(s)", groups.size)
    }

    fun getGroup(name: String): ServerGroup? = groups[name]

    fun getAllGroups(): List<ServerGroup> = groups.values.toList()

    /**
     * Updates a group's type (STATIC/DYNAMIC) at runtime.
     * Returns true if the group was found and updated.
     */
    fun updateGroupType(name: String, type: GroupType): Boolean {
        val group = groups[name] ?: return false
        val updatedDef = group.config.group.copy(type = type)
        val updatedConfig = group.config.copy(group = updatedDef)
        val updated = ServerGroup(updatedConfig)
        scanModIds(updated)
        groups[name] = updated
        logger.info("Updated group '{}' type to {}", name, type)
        return true
    }

    fun reloadGroups(configs: List<GroupConfig>) {
        val incoming = configs.associateBy { it.group.name }

        // Warn about removed groups
        val removed = groups.keys - incoming.keys
        for (name in removed) {
            logger.warn("Group '{}' was removed from configuration — still tracked until restart", name)
        }

        // Update existing and add new groups
        for ((name, config) in incoming) {
            val group = ServerGroup(config)
            scanModIds(group)
            if (groups.containsKey(name)) {
                groups[name] = group
                logger.info("Reloaded group '{}'", name)
            } else {
                groups[name] = group
                logger.info("Added new group '{}'", name)
            }
        }
    }

    private fun scanModIds(group: ServerGroup) {
        if (templatesDir == null) return
        val software = group.config.group.software
        if (software !in MODDED_SOFTWARE) return
        val primaryTemplate = group.config.group.resolvedTemplates.firstOrNull() ?: return
        val templateDir = templatesDir.resolve(primaryTemplate)
        group.modIds = ModScanner.scanMods(templateDir)
        if (group.modIds.isNotEmpty()) {
            logger.info("Group '{}': scanned {} mod(s) from template", group.name, group.modIds.size)
        }
    }
}
