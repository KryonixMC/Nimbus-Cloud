package dev.kryonix.nimbus.agent

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

class SetupWizard(private val baseDir: Path) {
    private val logger = LoggerFactory.getLogger(SetupWizard::class.java)

    fun run(): AgentConfig? {
        val reader = BufferedReader(InputStreamReader(System.`in`))

        println()
        println("  Nimbus Agent — First Run Setup")
        println("  ──────────────────────────────")
        println()

        print("  Controller URL [ws://127.0.0.1:8443/cluster]: ")
        val controller = reader.readLine()?.trim()?.ifEmpty { "ws://127.0.0.1:8443/cluster" }
            ?: return null

        print("  Auth Token: ")
        val token = reader.readLine()?.trim() ?: return null
        if (token.isEmpty()) {
            println("  Error: Token is required.")
            return null
        }

        val defaultName = try { java.net.InetAddress.getLocalHost().hostName } catch (_: Exception) { "worker-1" }
        print("  Node Name [$defaultName]: ")
        val nodeName = reader.readLine()?.trim()?.ifEmpty { defaultName } ?: defaultName

        val defaultMemory = autoDetectMemory()
        print("  Max Memory [$defaultMemory]: ")
        val maxMemory = reader.readLine()?.trim()?.ifEmpty { defaultMemory } ?: defaultMemory

        val defaultServices = autoDetectMaxServices()
        print("  Max Services [$defaultServices]: ")
        val maxServices = reader.readLine()?.trim()?.toIntOrNull() ?: defaultServices

        println()
        println("  Configuration:")
        println("    Controller: $controller")
        println("    Node Name:  $nodeName")
        println("    Max Memory: $maxMemory")
        println("    Max Services: $maxServices")
        println()

        return AgentConfig(
            agent = AgentDefinition(
                controller = controller,
                token = token,
                nodeName = nodeName,
                maxMemory = maxMemory,
                maxServices = maxServices
            )
        )
    }
}
