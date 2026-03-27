package dev.nimbus.config

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

object ConfigLoader {

    private val logger = LoggerFactory.getLogger(ConfigLoader::class.java)
    private val toml = Toml()

    fun loadNimbusConfig(path: Path): NimbusConfig {
        if (!path.exists()) {
            logger.warn("Nimbus config not found at {}, using defaults", path)
            return NimbusConfig()
        }
        return try {
            val content = path.readText()
            toml.decodeFromString(serializer<NimbusConfig>(), content)
        } catch (e: Exception) {
            logger.error("Failed to parse nimbus config at {}: {}", path, e.message, e)
            throw ConfigException("Failed to parse nimbus config: ${e.message}", e)
        }
    }

    fun loadGroupConfigs(groupsDir: Path): List<GroupConfig> {
        if (!groupsDir.exists() || !groupsDir.isDirectory()) {
            logger.warn("Groups directory not found at {}, returning empty list", groupsDir)
            return emptyList()
        }
        val configs = mutableListOf<GroupConfig>()
        val files = groupsDir.listDirectoryEntries("*.toml")
        if (files.isEmpty()) {
            logger.info("No group config files found in {}", groupsDir)
            return emptyList()
        }
        for (file in files) {
            try {
                val content = file.readText()
                val config = toml.decodeFromString(serializer<GroupConfig>(), content)
                validateGroupConfig(config, file)
                configs.add(config)
                logger.info("Loaded group config '{}' from {}", config.group.name, file.fileName)
            } catch (e: ConfigException) {
                logger.error("Validation failed for group config {}: {}", file.fileName, e.message)
            } catch (e: Exception) {
                logger.error("Failed to parse group config {}: {}", file.fileName, e.message, e)
            }
        }
        return configs
    }

    fun reloadGroupConfigs(groupsDir: Path): List<GroupConfig> {
        logger.info("Reloading group configs from {}", groupsDir)
        return loadGroupConfigs(groupsDir)
    }

    private fun validateGroupConfig(config: GroupConfig, source: Path) {
        val group = config.group
        val scaling = group.scaling

        require(group.name.isNotBlank()) {
            throw ConfigException("Group name must not be blank in $source")
        }

        if (scaling.minInstances > scaling.maxInstances) {
            throw ConfigException(
                "min_instances (${scaling.minInstances}) must be <= max_instances (${scaling.maxInstances}) " +
                    "in group '${group.name}' ($source)"
            )
        }
        if (scaling.minInstances < 0) {
            throw ConfigException(
                "min_instances must be >= 0 in group '${group.name}' ($source)"
            )
        }
        if (scaling.scaleThreshold < 0.0 || scaling.scaleThreshold > 1.0) {
            throw ConfigException(
                "scale_threshold must be between 0.0 and 1.0 in group '${group.name}' ($source)"
            )
        }
        if (group.resources.maxPlayers < 1) {
            throw ConfigException(
                "max_players must be >= 1 in group '${group.name}' ($source)"
            )
        }
        if (group.lifecycle.maxRestarts < 0) {
            throw ConfigException(
                "max_restarts must be >= 0 in group '${group.name}' ($source)"
            )
        }
    }
}

class ConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
