package dev.nimbus.agent

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class AgentConfig(
    val agent: AgentDefinition = AgentDefinition()
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
        }
        path.writeText(content)
    }
}
