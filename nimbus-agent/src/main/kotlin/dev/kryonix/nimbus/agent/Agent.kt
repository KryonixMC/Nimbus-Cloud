package dev.kryonix.nimbus.agent

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.io.path.exists

private val logger = LoggerFactory.getLogger("NimbusAgent")

fun main(args: Array<String>) = runBlocking {
    val baseDir = Path("").toAbsolutePath()
    val configPath = baseDir.resolve("agent.toml")

    logger.info("Nimbus Agent starting...")

    // Parse CLI args
    val cliArgs = parseCliArgs(args)

    // Load or create config
    val config: AgentConfig = run {
        val loaded = if (configPath.exists()) {
            AgentConfigLoader.load(configPath)
        } else if (cliArgs.controller != null && cliArgs.token != null) {
            // CLI args provided — skip wizard
            AgentConfig(
                agent = AgentDefinition(
                    controller = cliArgs.controller,
                    token = cliArgs.token,
                    nodeName = cliArgs.nodeName ?: java.net.InetAddress.getLocalHost().hostName,
                    maxMemory = cliArgs.maxMemory ?: autoDetectMemory(),
                    maxServices = cliArgs.maxServices ?: autoDetectMaxServices()
                )
            )
        } else {
            // Run setup wizard
            val wizardConfig = SetupWizard(baseDir).run()
            if (wizardConfig == null) {
                logger.info("Setup cancelled.")
                return@runBlocking
            }
            AgentConfigLoader.save(configPath, wizardConfig)
            wizardConfig
        }
        // Apply environment variable overrides (secrets, controller URL, etc.)
        AgentConfigLoader.applyEnvironmentOverrides(loaded)
    }

    logger.info("Node: {} | Controller: {} | Max Memory: {} | Max Services: {}",
        config.agent.nodeName, config.agent.controller, config.agent.maxMemory, config.agent.maxServices)

    // Start the agent
    val agent = AgentRuntime(config, baseDir)

    // Shutdown hook — gracefully stop all services on Ctrl+C / SIGTERM
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutdown signal received, stopping all services...")
        kotlinx.coroutines.runBlocking {
            agent.shutdown()
        }
    })

    agent.start()
}

data class CliArgs(
    val controller: String? = null,
    val token: String? = null,
    val nodeName: String? = null,
    val maxMemory: String? = null,
    val maxServices: Int? = null
)

fun parseCliArgs(args: Array<String>): CliArgs {
    var controller: String? = null
    var token: String? = null
    var nodeName: String? = null
    var maxMemory: String? = null
    var maxServices: Int? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--controller" -> { controller = args.getOrNull(i + 1); i += 2 }
            "--token" -> { token = args.getOrNull(i + 1); i += 2 }
            "--name" -> { nodeName = args.getOrNull(i + 1); i += 2 }
            "--max-memory" -> { maxMemory = args.getOrNull(i + 1); i += 2 }
            "--max-services" -> { maxServices = args.getOrNull(i + 1)?.toIntOrNull(); i += 2 }
            else -> i++
        }
    }
    return CliArgs(controller, token, nodeName, maxMemory, maxServices)
}

fun autoDetectMemory(): String {
    val totalMb = Runtime.getRuntime().maxMemory() / 1024 / 1024
    val osMb = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize?.div(1024)?.div(1024) ?: totalMb
    // Reserve 2GB for OS, use the rest
    val available = (osMb - 2048).coerceAtLeast(1024)
    return if (available >= 1024) "${available / 1024}G" else "${available}M"
}

fun autoDetectMaxServices(): Int {
    val totalGb = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize?.div(1024)?.div(1024)?.div(1024) ?: 8
    return (totalGb - 2).toInt().coerceIn(2, 50) // 1 service per GB after 2GB OS reserve
}
