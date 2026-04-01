package dev.kryonix.nimbus.config

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
        if (!group.name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            throw ConfigException(
                "Group name '${group.name}' contains invalid characters in $source — only alphanumeric, hyphen and underscore allowed"
            )
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
        // Validate memory format (e.g. "512M", "1G", "2048M")
        val memoryPattern = Regex("^\\d+[MmGg]$")
        if (!memoryPattern.matches(group.resources.memory)) {
            throw ConfigException(
                "Invalid memory format '${group.resources.memory}' in group '${group.name}' ($source) — expected format like '512M' or '2G'"
            )
        }
        // Validate version format
        if (!group.version.matches(Regex("^\\d+\\.\\d+(\\.\\d+)?(-.*)?$"))) {
            throw ConfigException(
                "Invalid version format '${group.version}' in group '${group.name}' ($source) — expected format like '1.21.4' or '1.8.8'"
            )
        }
        // Validate template name is not blank
        if (group.template.isBlank()) {
            throw ConfigException(
                "Template name must not be blank in group '${group.name}' ($source)"
            )
        }
        if (!group.template.matches(Regex("^[a-zA-Z0-9_.-]+$"))) {
            throw ConfigException(
                "Template name '${group.template}' contains invalid characters in group '${group.name}' ($source) — only alphanumeric, hyphen, underscore and dot allowed"
            )
        }
    }
}

class ConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
