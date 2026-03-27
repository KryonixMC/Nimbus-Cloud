package dev.nimbus.group

import dev.nimbus.config.GroupConfig
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
