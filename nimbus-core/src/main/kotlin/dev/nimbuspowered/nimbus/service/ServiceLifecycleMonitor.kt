package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.velocity.VelocityConfigGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration

internal class ServiceLifecycleMonitor(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val scope: CoroutineScope,
    private val eventBus: EventBus,
    private val groupManager: GroupManager,
    private val portAllocator: PortAllocator,
    private val stateStore: ControllerStateStore?,
    private val processHandles: java.util.concurrent.ConcurrentHashMap<String, ServiceHandle>,
    private val velocityConfigGen: VelocityConfigGen,
    private val getDedicatedServiceManager: () -> DedicatedServiceManager?,
    private val onReloadVelocity: suspend () -> Unit,
    private val onCleanupWorkingDirectory: (java.nio.file.Path) -> Unit,
    private val onStartService: suspend (String) -> Service?,
    private val onStartDedicatedService: suspend (dev.nimbuspowered.nimbus.config.DedicatedDefinition) -> Service?
) {
    private val logger = LoggerFactory.getLogger(ServiceLifecycleMonitor::class.java)

    fun launchReadyMonitor(service: Service, handle: ServiceHandle, readyTimeout: Duration) {
        val serviceName = service.name
        scope.launch {
            try {
                val ready = handle.waitForReady(readyTimeout)
                if (registry.get(serviceName) !== service) return@launch
                if (ready) {
                    service.transitionTo(ServiceState.READY)
                    eventBus.emit(NimbusEvent.ServiceReady(serviceName, service.groupName))
                    logger.info("Service '{}' is ready", serviceName)
                    velocityConfigGen.updateProxyServerList()
                    onReloadVelocity()
                } else {
                    logger.warn("Service '{}' did not become ready within timeout — marking as CRASHED", serviceName)
                    if (service.transitionTo(ServiceState.CRASHED)) {
                        val tail = runCatching { handle.snapshotTail() }.getOrDefault(emptyList())
                        val ctx = StartupDiagnostic.CrashContext.ReadyTimeout(readyTimeout.inWholeSeconds)
                        val diag = StartupDiagnostic.diagnose(tail, ctx)
                        service.lastCrashReport = StartupCrashReport(diag, tail, exitCode = null)
                        logger.warn("Service '{}' crash diagnosis: {}", serviceName, diag)
                        eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, -1, service.restartCount, diag, tail))
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                if (registry.get(serviceName) !== service) return@launch
                logger.error("Error waiting for service '{}' to become ready — marking as CRASHED", serviceName, e)
                if (service.transitionTo(ServiceState.CRASHED)) {
                    eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, -1, service.restartCount))
                }
            }
        }
    }

    fun launchExitMonitor(service: Service, handle: ServiceHandle) {
        val serviceName = service.name
        val restartOnCrash: Boolean
        val maxRestarts: Int
        if (service.isDedicated) {
            val dedicatedConfig = getDedicatedServiceManager()?.getConfig(serviceName)?.dedicated
            restartOnCrash = dedicatedConfig?.restartOnCrash ?: false
            maxRestarts = dedicatedConfig?.maxRestarts ?: 0
        } else {
            val group = groupManager.getGroup(service.groupName)
            restartOnCrash = group?.config?.group?.lifecycle?.restartOnCrash ?: false
            maxRestarts = group?.config?.group?.lifecycle?.maxRestarts ?: 0
        }
        scope.launch {
            try {
                monitorProcess(service, handle, service.groupName, restartOnCrash, maxRestarts)
            } catch (e: Exception) {
                logger.error("Error monitoring service '{}'", serviceName, e)
                handle.destroy()
                processHandles.remove(serviceName)
                stateStore?.removeService(serviceName)
                portAllocator.release(service.port)
                service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
                registry.unregister(serviceName)
                if (!service.isStatic) {
                    onCleanupWorkingDirectory(service.workingDirectory)
                }
                if (service.transitionTo(ServiceState.CRASHED)) {
                    eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, -1, service.restartCount))
                }
            }
        }
    }

    fun startHealthMonitor() = scope.launch {
        val timeoutSeconds = config.controller.serviceStaleTimeout
        if (timeoutSeconds <= 0) return@launch
        while (isActive) {
            delay(60_000)
            val threshold = Instant.now().minusSeconds(timeoutSeconds)
            for (service in registry.getAll()) {
                if (service.state != ServiceState.READY) continue
                val lastReport = service.lastPlayerCountUpdate ?: continue
                if (lastReport.isBefore(threshold)) {
                    val staleSecs = java.time.Duration.between(lastReport, Instant.now()).seconds
                    logger.warn("Service '{}' has not reported health for {}s — marking as CRASHED", service.name, staleSecs)
                    if (service.transitionTo(ServiceState.CRASHED)) {
                        eventBus.emit(NimbusEvent.ServiceCrashed(service.name, -1, service.restartCount))
                    }
                }
            }
        }
    }

    private suspend fun monitorProcess(service: Service, handle: ServiceHandle, groupName: String, restartOnCrash: Boolean, maxRestarts: Int) {
        val serviceName = service.name
        handle.awaitExit()

        if (service.state == ServiceState.DRAINING || service.state == ServiceState.STOPPING || service.state == ServiceState.STOPPED) {
            return
        }

        val currentService = registry.get(serviceName)
        if (currentService !== service) {
            return
        }

        val exitCode = handle.exitCode() ?: -1
        val wasReady = service.state == ServiceState.READY
        val treatAsCrash = exitCode != 0 || !wasReady

        handle.destroy()
        processHandles.remove(serviceName)
        stateStore?.removeService(serviceName)
        portAllocator.release(service.port)
        service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
        registry.unregister(serviceName)
        if (!service.isStatic) {
            onCleanupWorkingDirectory(service.workingDirectory)
        }

        if (!treatAsCrash) {
            service.transitionTo(ServiceState.STOPPED)
            logger.info("Service '{}' exited cleanly (code 0)", serviceName)
            eventBus.emit(NimbusEvent.ServiceStopped(serviceName))
            return
        }

        val justCrashed = service.transitionTo(ServiceState.CRASHED)
        if (justCrashed) {
            if (exitCode == 0 && !wasReady) {
                logger.warn("Service '{}' exited during startup (code 0) — treating as crash", serviceName)
            } else {
                logger.warn("Service '{}' crashed with exit code {}", serviceName, exitCode)
            }
            val tail = runCatching { handle.snapshotTail() }.getOrDefault(emptyList())
            val ctx = StartupDiagnostic.CrashContext.Exited(exitCode)
            val diag = if (!wasReady || tail.isNotEmpty()) StartupDiagnostic.diagnose(tail, ctx) else null
            if (diag != null) {
                service.lastCrashReport = StartupCrashReport(diag, tail, exitCode)
                logger.warn("Service '{}' crash diagnosis: {}", serviceName, diag)
            }
            eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, exitCode, service.restartCount, diag, tail))
        }

        if (restartOnCrash && service.restartCount < maxRestarts) {
            logger.info("Restarting service '{}' (attempt {}/{})", serviceName, service.restartCount + 1, maxRestarts)
            val newService = if (service.isDedicated) {
                val dedicatedConfig = getDedicatedServiceManager()?.getConfig(serviceName)?.dedicated
                if (dedicatedConfig != null) onStartDedicatedService(dedicatedConfig) else null
            } else {
                onStartService(groupName)
            }
            if (newService != null) {
                newService.restartCount = service.restartCount + 1
            }
        } else if (service.restartCount >= maxRestarts) {
            logger.error("Service '{}' exceeded max restarts ({}), not restarting", serviceName, maxRestarts)
        }
    }
}
