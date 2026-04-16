package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.config.DockerServiceConfig
import dev.nimbuspowered.nimbus.service.LocalServiceHandleFactory
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Bridge between [ServiceManager] and the Docker module. When the module is
 * enabled and the daemon is reachable, [isAvailable] returns true; [create]
 * then rewrites the JVM command for an in-container Java binary, picks a
 * matching Java image, creates the container, and returns a started handle.
 *
 * Because templates bind-mount their host path directly into `/server`, the
 * JVM command that [dev.nimbuspowered.nimbus.service.ServiceFactory] built
 * (with a host `java` path + host work dir) needs one small rewrite: replace
 * the leading `java` binary so we use the container image's JRE rather than
 * the host's.
 */
class DockerServiceHandleFactory(
    private val client: DockerClient,
    private val configManager: DockerConfigManager
) : LocalServiceHandleFactory {

    private val logger = LoggerFactory.getLogger(DockerServiceHandleFactory::class.java)

    override fun isAvailable(): Boolean {
        if (!configManager.config.docker.enabled) return false
        return client.ping()
    }

    override suspend fun create(
        service: Service,
        workDir: Path,
        command: List<String>,
        env: Map<String, String>,
        dockerConfig: DockerServiceConfig,
        readyPattern: Regex?
    ): ServiceHandle = withContext(Dispatchers.IO) {
        val javaVersion = detectJavaVersion(command)
        val effective = configManager.effectiveFor(dockerConfig, javaVersion)

        client.ensureNetwork(effective.network)
        client.ensureImage(effective.javaImage)

        val containerName = "nimbus-${service.name.lowercase()}"
        // The first element of [command] is the host Java binary path; inside the
        // container we use the image's `java`.
        val containerCmd = mutableListOf<String>("java").apply {
            if (command.size > 1) addAll(command.subList(1, command.size))
        }

        val spec = client.buildContainerSpec(
            image = effective.javaImage,
            cmd = containerCmd,
            workDir = "/server",
            hostWorkDir = workDir.toAbsolutePath().toString(),
            env = env,
            // Same-port mapping — the server binds the host-allocated port inside
            // the container (server.properties / velocity.toml on the bind-mounted
            // workdir carry that port already).
            portMappings = mapOf(service.port to service.port),
            memoryBytes = effective.memoryBytes,
            cpuLimit = effective.cpuLimit,
            network = effective.network,
            labels = mapOf(
                "nimbus.managed" to "true",
                "nimbus.service" to service.name,
                "nimbus.group" to service.groupName,
                "nimbus.port" to service.port.toString()
            )
        )

        // If a container with the same name is left over from a previous run
        // (ungraceful shutdown / crash), remove it first — Docker rejects
        // create-with-existing-name.
        removeIfExists(containerName)

        val id = client.createContainer(containerName, spec)
        logger.info("Created container '{}' id={} image={} mem={}MB cpu={} for service '{}'",
            containerName, id.take(12), effective.javaImage,
            effective.memoryBytes / 1024 / 1024, effective.cpuLimit, service.name)

        val handle = DockerServiceHandle(client, service, id, containerName)
        if (readyPattern != null) handle.setReadyPattern(readyPattern)
        handle.startAndAttach()
        handle
    }

    /**
     * Best-guess Java major version from the configured binary path — used to
     * pick between java_17 / java_21 images. Matches simple patterns like
     * `.../jdk-21/...` or `.../temurin-17/...`; falls back to default image.
     */
    private fun detectJavaVersion(command: List<String>): Int? {
        if (command.isEmpty()) return null
        val bin = command[0]
        val m = Regex("(?:jdk|temurin|jre|openjdk)[^0-9]*(\\d{2})").find(bin.lowercase())
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun removeIfExists(name: String) {
        try {
            // Container may be findable by name (our deterministic `nimbus-<svc>` form)
            // or by the `nimbus.service` label we set on create. Handle both.
            val inspected = client.inspect(name)
            if (inspected != null) {
                runCatching { client.removeContainer(name, force = true) }
            }
            val existing = client.listContainers(
                labels = mapOf("nimbus.service" to name.removePrefix("nimbus-"))
            )
            for (c in existing) {
                val id = c["Id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: continue
                runCatching { client.removeContainer(id, force = true) }
            }
        } catch (e: Exception) {
            logger.debug("removeIfExists('{}') ignored error: {}", name, e.message)
        }
    }
}
