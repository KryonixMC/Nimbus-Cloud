package dev.nimbus.group

import dev.nimbus.config.GroupConfig
import dev.nimbus.config.GroupType
import org.slf4j.LoggerFactory

class GroupManager {

    private val logger = LoggerFactory.getLogger(GroupManager::class.java)
    private val groups = mutableMapOf<String, ServerGroup>()

    fun loadGroups(configs: List<GroupConfig>) {
        groups.clear()
        for (config in configs) {
            val name = config.group.name
            groups[name] = ServerGroup(config)
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
        groups[name] = ServerGroup(updatedConfig)
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
            if (groups.containsKey(name)) {
                groups[name] = ServerGroup(config)
                logger.info("Reloaded group '{}'", name)
            } else {
                groups[name] = ServerGroup(config)
                logger.info("Added new group '{}'", name)
            }
        }
    }
}
