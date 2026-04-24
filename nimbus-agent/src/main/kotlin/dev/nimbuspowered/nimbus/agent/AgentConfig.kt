package dev.nimbuspowered.nimbus.agent

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
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
    @SerialName("trusted_fingerprint")
    val trustedFingerprint: String = "",
    @SerialName("tls_verify")
    val tlsVerify: Boolean = true,
    @SerialName("truststore_path")
    val truststorePath: String = "",
    @SerialName("truststore_password")
    val truststorePassword: String = "",
    /**
     * Publicly reachable IP/hostname the controller's proxy should use to connect
     * to backends on this node. Leave blank to auto-pick the first non-APIPA,
     * non-loopback, non-link-local IPv4 interface. Set explicitly if the agent
     * runs behind NAT or has multiple interfaces (e.g. Tailscale + LAN).
     */
    @SerialName("public_host")
    val publicHost: String = ""
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
    private val strictToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))
    private val lenientToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))
    private val unknownKeyRegex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")
    private val sectionRegex = Regex("^\\s*\\[([^\\]]+)\\]\\s*$")

    fun load(path: Path): AgentConfig {
        val content = path.readText()
        warnUnknownKeys(content, "agent.toml")
        return lenientToml.decodeFromString(serializer<AgentConfig>(), content)
    }

    /**
     * Warn-only unknown-key detection. Iteratively strips one unknown key per
     * re-parse until ktoml stops complaining. Bounded to 64 iterations.
     * Will become an error in Nimbus 1.0.
     */
    private fun warnUnknownKeys(content: String, label: String) {
        var remaining = content
        repeat(64) {
            try {
                strictToml.decodeFromString(serializer<AgentConfig>(), remaining)
                return
            } catch (e: Exception) {
                val m = unknownKeyRegex.find(e.message ?: "") ?: return
                val key = m.groupValues[1]
                val scope = m.groupValues[2]
                logger.warn(
                    "Config [{}]: unknown key '{}' at [{}] — will be an error in Nimbus 1.0",
                    label, key, scope
                )
                val stripped = stripKey(remaining, key, scope) ?: return
                if (stripped == remaining) return
                remaining = stripped
            }
        }
    }

    private fun stripKey(content: String, key: String, scope: String): String? {
        // ktoml reports the root table as scope "rootNode" — normalise to "".
        val targetScope = if (scope == "rootNode" || scope.isEmpty()) "" else scope
        val lines = content.lines().toMutableList()
        val keyRegex = Regex("^\\s*${Regex.escape(key)}\\s*=.*$")
        var currentScope = ""
        for (i in lines.indices) {
            val line = lines[i]
            val section = sectionRegex.matchEntire(line)
            if (section != null) {
                currentScope = section.groupValues[1]
                continue
            }
            if (currentScope == targetScope && keyRegex.matches(line)) {
                lines[i] = ""
                return lines.joinToString("\n")
            }
        }
        return null
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
            appendLine("# trusted_fingerprint: SHA-256 fingerprint of the controller's TLS cert.")
            appendLine("#   Set by the setup wizard via the /api/cluster/bootstrap endpoint.")
            appendLine("#   Takes precedence over truststore_path and system CAs.")
            appendLine("trusted_fingerprint = \"${config.agent.trustedFingerprint}\"")
            appendLine("# tls_verify: set to false to trust any cert (DEV ONLY, MITM-vulnerable).")
            appendLine("tls_verify = ${config.agent.tlsVerify}")
            appendLine("# truststore_path / truststore_password: advanced, for CA-issued certs.")
            appendLine("truststore_path = \"${config.agent.truststorePath}\"")
            appendLine("truststore_password = \"${config.agent.truststorePassword}\"")
            appendLine()
            appendLine("# public_host: IP/hostname the controller's proxy should route players to")
            appendLine("# when they connect to backends on this node. Leave blank to auto-pick a")
            appendLine("# routable IPv4 from a real LAN interface. Set explicitly if the agent runs")
            appendLine("# behind NAT or has multiple interfaces (e.g. Tailscale + LAN).")
            appendLine("public_host = \"${config.agent.publicHost}\"")
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
