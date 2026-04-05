package dev.kryonix.nimbus.agent

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class AgentConfig(
    val agent: AgentDefinition = AgentDefinition(),
    val java: JavaDefinition = JavaDefinition()
)

@Serializable
data class AgentDefinition(
    val controller: String = "wss://127.0.0.1:8443/cluster",
    val token: String = "",
    @SerialName("node_name")
    val nodeName: String = "worker-1",
    @SerialName("max_memory")
    val maxMemory: String = "8G",
    @SerialName("max_services")
    val maxServices: Int = 10,
    @SerialName("tls_verify")
    val tlsVerify: Boolean = true,
    @SerialName("truststore_path")
    val truststorePath: String = "",
    @SerialName("truststore_password")
    val truststorePassword: String = ""
)

@Serializable
data class JavaDefinition(
    @SerialName("java_16")
    val java16: String = "",
    @SerialName("java_17")
    val java17: String = "",
    @SerialName("java_21")
    val java21: String = ""
) {
    fun toMap(): Map<Int, String> {
        return mapOf(16 to java16, 17 to java17, 21 to java21)
            .filter { it.value.isNotBlank() }
    }
}

object AgentConfigLoader {
    private val logger = LoggerFactory.getLogger(AgentConfigLoader::class.java)
    private val toml = Toml()

    fun load(path: Path): AgentConfig {
        val content = path.readText()
        return toml.decodeFromString(serializer<AgentConfig>(), content)
    }

    fun applyEnvironmentOverrides(config: AgentConfig): AgentConfig {
        var agent = config.agent
        val applied = mutableListOf<String>()

        System.getenv("NIMBUS_AGENT_TOKEN")?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(token = it)
            applied += "NIMBUS_AGENT_TOKEN"
        }
        System.getenv("NIMBUS_AGENT_CONTROLLER")?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(controller = it)
            applied += "NIMBUS_AGENT_CONTROLLER"
        }

        if (applied.isNotEmpty()) {
            logger.info("Applied environment variable overrides: {}", applied.joinToString(", "))
        }

        return if (agent !== config.agent) config.copy(agent = agent) else config
    }

    fun save(path: Path, config: AgentConfig) {
        val content = buildString {
            appendLine("[agent]")
            appendLine("controller = \"${config.agent.controller}\"")
            appendLine("token = \"${config.agent.token}\"")
            appendLine("node_name = \"${config.agent.nodeName}\"")
            appendLine("max_memory = \"${config.agent.maxMemory}\"")
            appendLine("max_services = ${config.agent.maxServices}")
            appendLine()
            appendLine("# TLS settings for connecting to the controller.")
            appendLine("# Set tls_verify = false to trust self-signed certificates (dev only).")
            appendLine("tls_verify = ${config.agent.tlsVerify}")
            appendLine("truststore_path = \"${config.agent.truststorePath}\"")
            appendLine("truststore_password = \"${config.agent.truststorePassword}\"")
            appendLine()
            appendLine("# Optional: specify paths to Java installations.")
            appendLine("# Leave empty for auto-detection / auto-download from Adoptium.")
            appendLine("[java]")
            appendLine("java_16 = \"${config.java.java16}\"")
            appendLine("java_17 = \"${config.java.java17}\"")
            appendLine("java_21 = \"${config.java.java21}\"")
        }
        path.writeText(content)
    }
}
