package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.config.DockerServiceConfig
import dev.nimbuspowered.nimbus.config.StrictToml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Global Docker-module config loaded from `config/modules/docker/docker.toml`.
 *
 * Per-group / per-dedicated overrides come from the service's own TOML
 * (`[docker]` block, parsed as [DockerServiceConfig]) and are merged against
 * the defaults below via [effectiveFor].
 */
@Serializable
data class DockerModuleConfig(
    val docker: DockerTomlBlock = DockerTomlBlock()
)

@Serializable
data class DockerTomlBlock(
    val enabled: Boolean = true,
    val socket: String = "/var/run/docker.sock",
    val defaults: DockerDefaults = DockerDefaults()
)

@Serializable
data class DockerDefaults(
    @SerialName("memory_limit")
    val memoryLimit: String = "2G",
    @SerialName("cpu_limit")
    val cpuLimit: Double = 2.0,
    val network: String = "nimbus",
    @SerialName("java_image")
    val javaImage: String = "eclipse-temurin:21-jre",
    @SerialName("java_17_image")
    val java17Image: String = "eclipse-temurin:17-jre",
    @SerialName("java_21_image")
    val java21Image: String = "eclipse-temurin:21-jre"
)

/**
 * Effective Docker settings for a specific service — produced by merging the
 * per-service [DockerServiceConfig] over the module defaults.
 */
data class EffectiveDockerConfig(
    val memoryBytes: Long,
    val cpuLimit: Double,
    val javaImage: String,
    val network: String
)

class DockerConfigManager(
    private val configDir: Path,
    private val strict: Boolean = false
) {

    private val logger = LoggerFactory.getLogger(DockerConfigManager::class.java)

    @Volatile
    private var current: DockerModuleConfig = DockerModuleConfig()

    val config: DockerModuleConfig get() = current

    fun load(): DockerModuleConfig {
        val file = configDir.resolve("docker.toml")
        if (!file.exists()) {
            logger.info("Creating default Docker config at {}", file)
            Files.createDirectories(configDir)
            file.writeText(DEFAULT_TOML)
            current = DockerModuleConfig()
            return current
        }
        val parsed = try {
            StrictToml.strictDecode(
                serializer<DockerModuleConfig>(), file.readText(), "modules/docker/docker.toml", strict
            )
        } catch (e: Exception) {
            logger.error("Failed to parse {}: {} — using defaults", file, e.message)
            DockerModuleConfig()
        }
        current = parsed
        return current
    }

    /**
     * Merges per-service overrides over the module defaults. Any empty or zero
     * field in the per-service config means "inherit the default".
     */
    fun effectiveFor(service: DockerServiceConfig, javaVersion: Int? = null): EffectiveDockerConfig {
        val d = current.docker.defaults
        val memoryBytes = parseMemoryToBytes(
            service.memoryLimit.ifBlank { d.memoryLimit }
        )
        val cpu = if (service.cpuLimit > 0.0) service.cpuLimit else d.cpuLimit
        val image = when {
            service.javaImage.isNotBlank() -> service.javaImage
            javaVersion == 17 -> d.java17Image
            javaVersion == 21 -> d.java21Image
            else -> d.javaImage
        }
        val network = service.network.ifBlank { d.network }
        return EffectiveDockerConfig(
            memoryBytes = memoryBytes,
            cpuLimit = cpu,
            javaImage = image,
            network = network
        )
    }

    private fun parseMemoryToBytes(spec: String): Long {
        val trimmed = spec.trim()
        val m = Regex("^(\\d+)\\s*([kKmMgGtT]?)([bB]?)$").matchEntire(trimmed)
        if (m == null) {
            // Returning 0 here makes buildContainerSpec omit the Memory cgroup
            // limit entirely — i.e. an unlimited container. That's surprising
            // enough that a typo in `memory_limit` shouldn't fail silently.
            logger.warn("Invalid Docker memory spec '{}' — expected e.g. '2G', '512M'. " +
                "Falling back to no memory limit for this service.", spec)
            return 0L
        }
        val n = m.groupValues[1].toLongOrNull() ?: return 0L
        return when (m.groupValues[2].uppercase()) {
            "T" -> n * 1024L * 1024L * 1024L * 1024L
            "G" -> n * 1024L * 1024L * 1024L
            "M" -> n * 1024L * 1024L
            "K" -> n * 1024L
            else -> n
        }
    }

    companion object {
        private val DEFAULT_TOML = """
            # Nimbus Docker module configuration.
            # Set `enabled = true` on a group (or dedicated service) to run it as a
            # container. Services that never set `[docker] enabled = true` keep
            # running as bare Java processes.

            [docker]
            enabled = true
            # Unix socket (Linux/Mac) or tcp://host:port (Docker Desktop Windows, Podman).
            # Examples:
            #   socket = "/var/run/docker.sock"
            #   socket = "tcp://localhost:2375"
            socket = "/var/run/docker.sock"

            [docker.defaults]
            memory_limit = "2G"
            cpu_limit = 2.0
            network = "nimbus"
            java_image = "eclipse-temurin:21-jre"
            java_17_image = "eclipse-temurin:17-jre"
            java_21_image = "eclipse-temurin:21-jre"
        """.trimIndent() + "\n"
    }
}
