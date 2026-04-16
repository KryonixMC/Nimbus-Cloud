package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.module.AuthLevel
import dev.nimbuspowered.nimbus.module.DashboardConfig
import dev.nimbuspowered.nimbus.module.DashboardSection
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.docker.commands.DockerCommand
import dev.nimbuspowered.nimbus.module.docker.routes.dockerRoutes
import dev.nimbuspowered.nimbus.service.LocalServiceHandleFactory
import dev.nimbuspowered.nimbus.service.ServiceMemoryResolver
import org.slf4j.LoggerFactory

/**
 * Opt-in Docker backend. When enabled for a group or dedicated service, Nimbus
 * launches the service inside a container instead of as a bare Java process.
 *
 * Services without `[docker] enabled = true` keep running as before — this
 * module never forces the container path on the rest of the installation.
 */
class DockerModule : NimbusModule {

    override val id = "docker"
    override val name = "Docker"
    override val version: String get() = NimbusVersion.version
    override val description = "Run services in Docker containers (hard memory/CPU limits, isolation, clean cleanup)"

    override val dashboardConfig = DashboardConfig(
        icon = "Container",
        apiPrefix = "/api/docker",
        sections = listOf(
            DashboardSection("Status", "stats", "/status"),
            DashboardSection("Containers", "table", "/containers")
        )
    )

    private val logger = LoggerFactory.getLogger(DockerModule::class.java)

    private lateinit var configManager: DockerConfigManager
    private var client: DockerClient? = null
    private var factory: DockerServiceHandleFactory? = null

    override suspend fun init(context: ModuleContext) {
        val moduleDir = context.moduleConfigDir(id)
        configManager = DockerConfigManager(moduleDir)
        configManager.load()

        val docker = configManager.config.docker
        if (!docker.enabled) {
            logger.info("Docker module loaded but disabled in config — services will run as processes")
            return
        }

        val c = DockerClient(docker.socket)
        client = c

        // Probe the daemon — non-fatal if it's down. Services that opted into
        // Docker will fall back to the process path (with a warning) until the
        // daemon comes back.
        if (c.ping()) {
            val v = c.version()
            logger.info("Docker daemon reachable at {} — {}",
                docker.socket, v?.let { "${it.version} (api ${it.apiVersion})" } ?: "")
            runCatching { c.ensureNetwork(docker.defaults.network) }
                .onFailure { logger.warn("Failed to ensure Docker network '{}': {}", docker.defaults.network, it.message) }
        } else {
            logger.warn("Docker daemon not reachable at {} — services that opted into Docker will run as processes", docker.socket)
        }

        val f = DockerServiceHandleFactory(c, configManager)
        factory = f
        // Register for ServiceManager lookup. ServiceManager checks isAvailable()
        // at every service start, so a daemon going up/down is transparent.
        context.registerService(LocalServiceHandleFactory::class.java, f)

        // Docker stats give us true cgroup memory — prefer it over reading
        // /proc for the java PID (which misses any sidecar processes and is
        // blocked on non-Linux hosts that run containers inside a VM).
        ServiceMemoryResolver.registerSource(DockerMemorySource(f::lookupHandle))

        context.registerCommand(DockerCommand(c, configManager))
        context.registerCompleter("docker") { args, prefix ->
            when (args.size) {
                1 -> listOf("status", "ps", "inspect", "prune")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                else -> emptyList()
            }
        }

        context.registerRoutes({ dockerRoutes(c, configManager) }, auth = AuthLevel.ADMIN)
        context.registerDoctorCheck(DockerDoctorCheck(c, configManager))
    }

    override suspend fun enable() {}

    override fun disable() {
        // Intentionally do NOT stop/remove containers here — ServiceManager is the
        // owner of each container's lifecycle via DockerServiceHandle.destroy().
        // Disabling the module while services still run would orphan containers
        // to docker ps; let the controller's normal shutdown path drive cleanup.
    }
}
