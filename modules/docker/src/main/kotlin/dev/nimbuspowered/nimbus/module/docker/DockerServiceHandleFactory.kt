package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.config.DockerServiceConfig
import dev.nimbuspowered.nimbus.service.LocalServiceHandleFactory
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Live DockerServiceHandles keyed by service name. Populated on create +
     * recover, consulted by [DockerMemorySource]. Entries may become stale after
     * a handle is destroyed — callers must tolerate `isAlive() == false`.
     */
    private val liveHandles = ConcurrentHashMap<String, DockerServiceHandle>()

    // Short TTL cache for ping() — [isAvailable] is called on every service
    // start, so during bursty scaling (10+ services in a second) we'd otherwise
    // open 10 sequential Unix-socket connections just to probe the daemon.
    // Single AtomicReference to avoid a torn read where the timestamp
    // appears fresh but the boolean still reflects the previous probe.
    private val pingCache = AtomicReference<Pair<Long, Boolean>>(0L to false)
    private val pingCacheTtlMs: Long = 5_000L

    fun lookupHandle(serviceName: String): DockerServiceHandle? {
        val h = liveHandles[serviceName] ?: return null
        if (!h.isAlive()) {
            liveHandles.remove(serviceName, h)
            return null
        }
        return h
    }

    override fun isAvailable(): Boolean {
        if (!configManager.config.docker.enabled) return false
        val now = System.currentTimeMillis()
        val (cachedAt, cached) = pingCache.get()
        if (now - cachedAt < pingCacheTtlMs) return cached
        val result = client.ping()
        pingCache.set(now to result)
        return result
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
        removeIfExists(containerName, service.name)

        val id = client.createContainer(containerName, spec)
        logger.info("Created container '{}' id={} image={} mem={}MB cpu={} for service '{}'",
            containerName, id.take(12), effective.javaImage,
            effective.memoryBytes / 1024 / 1024, effective.cpuLimit, service.name)

        val handle = DockerServiceHandle(client, service.name, id, containerName)
        if (readyPattern != null) handle.setReadyPattern(readyPattern)
        handle.startAndAttach()
        liveHandles[service.name] = handle
        handle
    }

    /**
     * Enumerates every running `nimbus.managed` container and re-attaches. Called
     * by [ServiceManager.recoverLocalServices] on controller startup so services
     * that were running when the controller died keep going without a restart.
     *
     * Containers with no surviving service record (their workdir has been removed,
     * or the group they belonged to was deleted) should be cleaned up by `docker
     * prune` or the subsequent state-store sync — we don't delete anything here,
     * the ownership decision belongs to the operator.
     */
    override fun recover(): Map<String, ServiceHandle> {
        if (!isAvailable()) return emptyMap()
        val out = mutableMapOf<String, ServiceHandle>()
        val containers = runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true"), all = false) }
            .getOrElse {
                logger.warn("Docker recovery: listContainers failed: {}", it.message)
                return emptyMap()
            }

        for (c in containers) {
            if (!DockerContainerMatcher.isManagedRunning(c)) continue
            val id = c["Id"]?.jsonPrimitive?.content ?: continue
            val serviceName = DockerContainerMatcher.serviceName(c) ?: continue
            val names = c["Names"]?.let {
                (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonPrimitive?.content
            } ?: "nimbus-${serviceName.lowercase()}"
            val containerName = names.removePrefix("/")

            try {
                val handle = DockerServiceHandle(client, serviceName, id, containerName)
                handle.reattach()
                out[serviceName] = handle
                liveHandles[serviceName] = handle
                logger.info("Docker recovery: reattached to '{}' (container {})", serviceName, id.take(12))
            } catch (e: Exception) {
                logger.warn("Docker recovery: failed to reattach '{}': {}", serviceName, e.message)
            }
        }
        return out
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

    private fun removeIfExists(name: String, serviceName: String) {
        try {
            // Container may be findable by name (our deterministic `nimbus-<svc>` form)
            // or by the `nimbus.service` label we set on create. Handle both — the
            // label was written with the original casing of [serviceName] (e.g.
            // `Lobby-1`), while [name] is the lowercased container name, so we
            // must use [serviceName] directly for the label search.
            val inspected = client.inspect(name)
            if (inspected != null) {
                runCatching { client.removeContainer(name, force = true) }
            }
            val existing = client.listContainers(
                labels = mapOf("nimbus.service" to serviceName)
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
