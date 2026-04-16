package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.config.DockerServiceConfig
import java.nio.file.Path

/**
 * Alternative [ServiceHandle] factory for local services — registered by modules
 * that want to run services in something other than a bare Java process (e.g. the
 * Docker module). When [ServiceManager.startLocalService] sees a non-null
 * [ServiceFactory.PreparedService.dockerConfig], it looks this service up via
 * the module context and delegates the handle creation to it.
 *
 * Registered by the Docker module via `ModuleContext.registerService(
 * LocalServiceHandleFactory::class.java, impl)`.
 */
interface LocalServiceHandleFactory {

    /**
     * Whether this factory is ready to serve requests (Docker daemon reachable,
     * config loaded, etc.). Checked once before [create] so startLocalService can
     * fall back to a plain process if the daemon is down at service-start time.
     */
    fun isAvailable(): Boolean

    /**
     * Creates a started [ServiceHandle] for [service]. The factory is expected to
     * produce a handle whose process is already running (equivalent to calling
     * [ProcessHandle.start]) so the caller can immediately wire ready/exit monitors.
     *
     * @param service         The service metadata (name, group, port, etc.)
     * @param workDir         Working directory the service should run in (bind-mounted
     *                        in Docker's case).
     * @param command         The JVM command line ServiceFactory built for this service.
     * @param env             Environment variables to inject into the process.
     * @param dockerConfig    The effective per-service Docker settings (enabled, resource
     *                        limits, image), never null when this is called.
     * @param readyPattern    Regex to trigger the ready state on; may be null (default
     *                        "Done (" applies).
     */
    suspend fun create(
        service: Service,
        workDir: Path,
        command: List<String>,
        env: Map<String, String>,
        dockerConfig: DockerServiceConfig,
        readyPattern: Regex?
    ): ServiceHandle
}
