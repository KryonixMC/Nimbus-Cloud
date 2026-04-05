package dev.kryonix.nimbus.agent

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
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
    val controller: String = "ws://127.0.0.1:8443/cluster",
    val token: String = "",
    @SerialName("node_name")
    val nodeName: String = "worker-1",
    @SerialName("max_memory")
    val maxMemory: String = "8G",
    @SerialName("max_services")
    val maxServices: Int = 10
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
    private val toml = Toml()

    fun load(path: Path): AgentConfig {
        val content = path.readText()
        return toml.decodeFromString(serializer<AgentConfig>(), content)
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
