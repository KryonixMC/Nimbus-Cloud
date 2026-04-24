package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.velocity.VelocityConfigGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class TemplateInitializer(
    private val velocityConfigGen: VelocityConfigGen
) {

    private val logger = LoggerFactory.getLogger(TemplateInitializer::class.java)

    suspend fun initializeFabricTemplate(templateDir: Path) {
        try {
            logger.info("Running Fabric launcher to download vanilla server...")
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", "server.jar", "nogui")
                    .directory(templateDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }
            withContext(Dispatchers.IO) {
                val reader = process.inputStream.bufferedReader()
                val startTime = System.currentTimeMillis()
                val timeout = 120_000L
                while (process.isAlive && System.currentTimeMillis() - startTime < timeout) {
                    if (reader.ready()) {
                        val line = reader.readLine() ?: break
                        if (line.contains("You need to agree to the EULA") || line.contains("Done (") || line.contains("Stopping server")) {
                            break
                        }
                    } else {
                        Thread.sleep(200)
                    }
                }
                if (process.isAlive) process.destroyForcibly()
            }

            if (templateDir.resolve(".fabric").exists()) {
                logger.info("Fabric template initialized — vanilla server downloaded")
            } else {
                logger.warn("Fabric initialization may not have completed — .fabric/ directory not found")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Fabric template: {}", e.message, e)
        }
    }

    suspend fun initializeVelocityTemplate(templateDir: Path, jarName: String) {
        try {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", jarName)
                    .directory(templateDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }
            withContext(Dispatchers.IO) {
                process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                if (process.isAlive) process.destroyForcibly()
            }

            if (templateDir.resolve("velocity.toml").exists()) {
                logger.info("Velocity template initialized successfully")
                cleanDefaultVelocityServers(templateDir)
            } else {
                logger.warn("Velocity config was not generated -- proxy may fail to start")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Velocity template: {}", e.message, e)
        }
    }

    private fun cleanDefaultVelocityServers(templateDir: Path) {
        val configFile = templateDir.resolve("velocity.toml")
        if (!configFile.exists()) return

        val content = configFile.readText()

        val cleanServers = "[servers]\ntry = []\n"
        val cleanForcedHosts = "[forced-hosts]\n"

        var result = velocityConfigGen.replaceTOMLSection(content, "servers", cleanServers)
        result = velocityConfigGen.replaceTOMLSection(result, "forced-hosts", cleanForcedHosts)

        configFile.writeText(result)
        logger.info("Cleaned default server entries from Velocity template")
    }
}
