package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.module.ModuleContextImpl
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

internal class PluginDeploymentResolver(
    private val softwareResolver: SoftwareResolver,
    private val moduleContext: ModuleContextImpl?
) {

    private val logger = LoggerFactory.getLogger(PluginDeploymentResolver::class.java)

    suspend fun resolveModulePlugins(software: ServerSoftware, version: String, workDir: Path, serviceName: String) {
        val pluginsDir = workDir.resolve("plugins")
        val allDeployments = moduleContext?.pluginDeployments.orEmpty()

        if (software == ServerSoftware.VELOCITY) {
            if (!pluginsDir.exists()) pluginsDir.createDirectories()
            deployBridgePlugin(pluginsDir)
            for (deployment in allDeployments.filter { it.target == dev.nimbuspowered.nimbus.module.PluginTarget.VELOCITY }) {
                deployResourcePlugin(pluginsDir, deployment.fileName, deployment.resourcePath)
            }
            return
        }

        val isPaperBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PURPUR, ServerSoftware.PUFFERFISH, ServerSoftware.LEAF, ServerSoftware.FOLIA)
        if (!isPaperBased) return

        if (!pluginsDir.exists()) pluginsDir.createDirectories()

        deployResourcePlugin(pluginsDir, "nimbus-sdk.jar", "plugins/nimbus-sdk.jar")

        val backendDeployments = allDeployments.filter { it.target == dev.nimbuspowered.nimbus.module.PluginTarget.BACKEND }
        if (backendDeployments.isEmpty()) return

        val minor = version.split(".").getOrNull(1)?.toIntOrNull() ?: 0
        var needsPacketEvents = false

        for (deployment in backendDeployments) {
            val minVersion = deployment.minMinecraftVersion
            if (minVersion != null && minor < minVersion) {
                logger.info("Service '{}': {} skipped (requires 1.{}+, got {})", serviceName, deployment.displayName, minVersion, version)
                continue
            }

            deployResourcePlugin(pluginsDir, deployment.fileName, deployment.resourcePath)

            if (deployment.foliaRequiresPacketEvents && software == ServerSoftware.FOLIA) {
                needsPacketEvents = true
            }
        }

        if (needsPacketEvents) {
            softwareResolver.ensurePacketEventsPlugin(pluginsDir, version)
        }
    }

    fun deployResourcePlugin(pluginsDir: Path, fileName: String, resourcePath: String) {
        val target = pluginsDir.resolve(fileName)
        val resource = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return
        resource.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deployBridgePlugin(pluginsDir: Path) {
        val version = dev.nimbuspowered.nimbus.NimbusVersion.version
        val versionedResource = "plugins/nimbus-bridge-$version.jar"
        val resourcePath = if (javaClass.classLoader.getResource(versionedResource) != null) {
            versionedResource
        } else {
            "plugins/nimbus-bridge.jar"
        }
        deployResourcePlugin(pluginsDir, "nimbus-bridge.jar", resourcePath)
    }
}
