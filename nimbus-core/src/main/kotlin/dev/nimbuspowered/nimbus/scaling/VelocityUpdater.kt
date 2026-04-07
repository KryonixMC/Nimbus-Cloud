package dev.nimbuspowered.nimbus.scaling

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Periodically checks for new Velocity versions and updates the proxy template JAR.
 * For static proxies, the JAR in the working directory is also updated (takes effect on restart).
 * For dynamic proxies, new instances automatically pick up the updated template.
 */
class VelocityUpdater(
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry,
    private val softwareResolver: SoftwareResolver,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val templatesDir: Path,
    private val groupsDir: Path,
    private val checkIntervalMs: Long = 6 * 60 * 60 * 1000L // 6 hours
) {
    private val logger = LoggerFactory.getLogger(VelocityUpdater::class.java)

    fun start(): Job = scope.launch {
        // Wait a bit before first check so startup isn't slowed down
        delay(60_000)

        while (isActive) {
            try {
                checkForUpdate()
            } catch (e: Exception) {
                logger.error("Error checking for Velocity update", e)
            }
            delay(checkIntervalMs)
        }
    }

    private suspend fun checkForUpdate() {
        val proxyGroup = groupManager.getAllGroups()
            .firstOrNull { it.config.group.software == ServerSoftware.VELOCITY }
            ?: return

        val currentVersion = proxyGroup.config.group.version
        val versions = softwareResolver.fetchVelocityVersions()
        val latestVersion = versions.latest ?: return

        if (latestVersion == currentVersion) {
            logger.debug("Velocity is up to date ({})", currentVersion)
            return
        }

        logger.info("New Velocity version available: {} -> {}", currentVersion, latestVersion)
        eventBus.emit(NimbusEvent.ProxyUpdateAvailable(currentVersion, latestVersion))

        // Download new JAR to the proxy template
        val templateDir = templatesDir.resolve(proxyGroup.config.group.template)
        val jarName = softwareResolver.jarFileName(ServerSoftware.VELOCITY)
        val templateJar = templateDir.resolve(jarName)

        // Delete old JAR so ensureJarAvailable downloads the new one
        if (Files.exists(templateJar)) {
            Files.delete(templateJar)
        }

        val downloaded = softwareResolver.ensureJarAvailable(
            ServerSoftware.VELOCITY, latestVersion, templateDir
        )

        if (!downloaded) {
            logger.warn("Failed to download Velocity {}, keeping old version", latestVersion)
            softwareResolver.ensureJarAvailable(ServerSoftware.VELOCITY, currentVersion, templateDir)
            return
        }

        // For static proxy services, copy the new JAR into their working directory
        // so the update takes effect on the next restart
        val proxyServices = registry.getByGroup(proxyGroup.name)
        for (service in proxyServices) {
            if (!service.isStatic) continue
            val serviceJar = service.workingDirectory.resolve(jarName)
            if (Files.exists(serviceJar)) {
                Files.copy(templateJar, serviceJar, StandardCopyOption.REPLACE_EXISTING)
                logger.info("Updated Velocity JAR for static service '{}' (restart to apply)", service.name)
            }
        }

        // Update the group TOML so the new version persists across restarts
        updateProxyToml(proxyGroup.config.group.name, currentVersion, latestVersion)

        logger.info("Velocity updated: {} -> {} (restart proxy to apply)", currentVersion, latestVersion)
        eventBus.emit(NimbusEvent.ProxyUpdateApplied(currentVersion, latestVersion))
    }

    private fun updateProxyToml(groupName: String, oldVersion: String, newVersion: String) {
        val tomlFile = groupsDir.resolve("${groupName.lowercase()}.toml")
        if (!Files.exists(tomlFile)) return

        val content = Files.readString(tomlFile)
        val updated = content.replace(
            "version = \"$oldVersion\"",
            "version = \"$newVersion\""
        )

        if (updated != content) {
            Files.writeString(tomlFile, updated)
            logger.debug("Updated {} version in {}", groupName, tomlFile.fileName)
        }
    }
}
