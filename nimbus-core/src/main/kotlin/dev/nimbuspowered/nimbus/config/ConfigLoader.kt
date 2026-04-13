package dev.nimbuspowered.nimbus.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

object ConfigLoader {

    private val logger = LoggerFactory.getLogger(ConfigLoader::class.java)
    private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

    fun loadNimbusConfig(path: Path): NimbusConfig {
        if (!path.exists()) {
            logger.warn("Nimbus config not found at {}, using defaults", path)
            return NimbusConfig()
        }
        return try {
            val content = path.readText()
            val config = toml.decodeFromString(serializer<NimbusConfig>(), content)
            validateNimbusConfig(config, path)
            config
        } catch (e: ConfigException) {
            throw e
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
        val failedFiles = mutableListOf<String>()
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
                failedFiles.add(file.fileName.toString())
            } catch (e: Exception) {
                logger.error("Failed to parse group config {}: {}", file.fileName, e.message, e)
                failedFiles.add(file.fileName.toString())
            }
        }
        if (failedFiles.isNotEmpty()) {
            logger.warn("Loaded {} group config(s), {} failed: {}", configs.size, failedFiles.size, failedFiles.joinToString(", "))
        }
        return configs
    }

    fun loadDedicatedConfigs(dedicatedDir: Path): List<DedicatedServiceConfig> {
        if (!dedicatedDir.exists() || !dedicatedDir.isDirectory()) {
            logger.warn("Dedicated directory not found at {}, returning empty list", dedicatedDir)
            return emptyList()
        }
        val configs = mutableListOf<DedicatedServiceConfig>()
        val failedFiles = mutableListOf<String>()
        val files = dedicatedDir.listDirectoryEntries("*.toml")
        if (files.isEmpty()) {
            logger.info("No dedicated config files found in {}", dedicatedDir)
            return emptyList()
        }
        for (file in files) {
            try {
                val content = file.readText()
                val config = toml.decodeFromString(serializer<DedicatedServiceConfig>(), content)
                validateDedicatedConfig(config, file)
                configs.add(config)
                logger.info("Loaded dedicated config '{}' from {}", config.dedicated.name, file.fileName)
            } catch (e: ConfigException) {
                logger.error("Validation failed for dedicated config {}: {}", file.fileName, e.message)
                failedFiles.add(file.fileName.toString())
            } catch (e: Exception) {
                logger.error("Failed to parse dedicated config {}: {}", file.fileName, e.message, e)
                failedFiles.add(file.fileName.toString())
            }
        }
        if (failedFiles.isNotEmpty()) {
            logger.warn("Loaded {} dedicated config(s), {} failed: {}", configs.size, failedFiles.size, failedFiles.joinToString(", "))
        }
        return configs
    }

    fun reloadGroupConfigs(groupsDir: Path): List<GroupConfig> {
        logger.info("Reloading group configs from {}", groupsDir)
        return loadGroupConfigs(groupsDir)
    }

    fun applyEnvironmentOverrides(config: NimbusConfig): NimbusConfig {
        var result = config
        val applied = mutableListOf<String>()

        System.getenv("NIMBUS_API_TOKEN")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(api = result.api.copy(token = it))
            applied += "NIMBUS_API_TOKEN"
        }
        System.getenv("NIMBUS_DB_TYPE")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(database = result.database.copy(type = it))
            applied += "NIMBUS_DB_TYPE"
        }
        System.getenv("NIMBUS_DB_HOST")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(database = result.database.copy(host = it))
            applied += "NIMBUS_DB_HOST"
        }
        System.getenv("NIMBUS_DB_PORT")?.takeIf { it.isNotBlank() }?.let { value ->
            value.toIntOrNull()?.let { port ->
                result = result.copy(database = result.database.copy(port = port))
                applied += "NIMBUS_DB_PORT"
            } ?: logger.warn("Ignoring NIMBUS_DB_PORT: '{}' is not a valid integer", value)
        }
        System.getenv("NIMBUS_DB_NAME")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(database = result.database.copy(name = it))
            applied += "NIMBUS_DB_NAME"
        }
        System.getenv("NIMBUS_DB_USERNAME")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(database = result.database.copy(username = it))
            applied += "NIMBUS_DB_USERNAME"
        }
        System.getenv("NIMBUS_DB_PASSWORD")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(database = result.database.copy(password = it))
            applied += "NIMBUS_DB_PASSWORD"
        }
        System.getenv("NIMBUS_CLUSTER_TOKEN")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(cluster = result.cluster.copy(token = it))
            applied += "NIMBUS_CLUSTER_TOKEN"
        }
        System.getenv("NIMBUS_CLUSTER_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let {
            result = result.copy(cluster = result.cluster.copy(keystorePassword = it))
            applied += "NIMBUS_CLUSTER_KEYSTORE_PASSWORD"
        }

        if (applied.isNotEmpty()) {
            logger.info("Applied environment variable overrides: {}", applied.joinToString(", "))
        }

        return result
    }

    private fun validateNimbusConfig(config: NimbusConfig, source: Path) {
        val memoryPattern = Regex("^\\d+[MmGg]$")
        if (!memoryPattern.matches(config.controller.maxMemory)) {
            throw ConfigException(
                "Invalid controller.max_memory format '${config.controller.maxMemory}' in $source — expected format like '512M' or '10G'"
            )
        }
        if (config.bedrock.basePort !in 1..65535) {
            throw ConfigException(
                "bedrock.base_port must be between 1 and 65535 in $source (got ${config.bedrock.basePort})"
            )
        }
        if (config.database.port !in 1..65535) {
            throw ConfigException(
                "database.port must be between 1 and 65535 in $source (got ${config.database.port})"
            )
        }
    }

    private fun validateDedicatedConfig(config: DedicatedServiceConfig, source: Path) {
        val ded = config.dedicated

        require(ded.name.isNotBlank()) {
            throw ConfigException("Dedicated service name must not be blank in $source")
        }
        if (!ded.name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            throw ConfigException(
                "Dedicated service name '${ded.name}' contains invalid characters in $source — only alphanumeric, hyphen and underscore allowed"
            )
        }
        if (ded.port !in 1..65535) {
            throw ConfigException(
                "Port must be between 1 and 65535 for dedicated service '${ded.name}' in $source (got ${ded.port})"
            )
        }
        val memoryPattern = Regex("^\\d+[MmGg]$")
        if (!memoryPattern.matches(ded.memory)) {
            throw ConfigException(
                "Invalid memory format '${ded.memory}' for dedicated service '${ded.name}' in $source — expected format like '512M' or '2G'"
            )
        }
        if (ded.maxRestarts < 0) {
            throw ConfigException(
                "max_restarts must be >= 0 for dedicated service '${ded.name}' in $source"
            )
        }
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
        // sync is only meaningful for STATIC groups — dynamic scaling would push/pull
        // canonical state on every scale-up/down which is almost never what you want.
        if (group.sync.enabled && group.type != GroupType.STATIC) {
            logger.warn(
                "Group '{}' has [group.sync] enabled but type={} — persistence is " +
                        "only supported for STATIC groups. Ignoring sync flag.",
                group.name, group.type
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
