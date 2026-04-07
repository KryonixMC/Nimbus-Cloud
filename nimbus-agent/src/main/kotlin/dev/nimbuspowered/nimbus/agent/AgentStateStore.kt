package dev.nimbuspowered.nimbus.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

private val stateJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
data class PersistedService(
    val serviceName: String,
    val groupName: String,
    val port: Int,
    val pid: Long,
    val workDir: String,
    val isStatic: Boolean,
    val templateName: String,
    val software: String,
    val memory: String,
    val startedAtEpochMs: Long
)

@Serializable
data class AgentState(
    val version: Int = 1,
    val services: List<PersistedService> = emptyList()
)

class AgentStateStore(baseDir: Path) {

    private val logger = LoggerFactory.getLogger(AgentStateStore::class.java)
    private val stateDir = baseDir.resolve("state")
    private val stateFile = stateDir.resolve("services.json")
    private val tmpFile = stateDir.resolve("services.json.tmp")

    fun load(): AgentState {
        if (!stateFile.exists()) return AgentState()
        return try {
            val text = stateFile.readText()
            stateJson.decodeFromString<AgentState>(text)
        } catch (e: Exception) {
            logger.warn("Failed to load agent state (starting fresh): {}", e.message)
            AgentState()
        }
    }

    fun save(state: AgentState) {
        try {
            stateDir.createDirectories()
            tmpFile.writeText(stateJson.encodeToString(state))
            tmpFile.moveTo(stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.error("Failed to save agent state: {}", e.message)
        }
    }

    fun addService(service: PersistedService) {
        val state = load()
        val updated = state.copy(
            services = state.services.filter { it.serviceName != service.serviceName } + service
        )
        save(updated)
    }

    fun removeService(serviceName: String) {
        val state = load()
        if (state.services.none { it.serviceName == serviceName }) return
        save(state.copy(services = state.services.filter { it.serviceName != serviceName }))
    }

    fun clear() {
        try {
            stateFile.deleteIfExists()
        } catch (e: Exception) {
            logger.warn("Failed to delete state file: {}", e.message)
        }
    }
}
