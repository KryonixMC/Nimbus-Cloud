package dev.nimbuspowered.nimbus.service

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
data class PersistedLocalService(
    val serviceName: String,
    val groupName: String,
    val port: Int,
    val pid: Long,
    val workDir: String,
    val isStatic: Boolean,
    val bedrockPort: Int = 0,
    val startedAtEpochMs: Long
)

@Serializable
data class ControllerState(
    val version: Int = 1,
    val services: List<PersistedLocalService> = emptyList()
)

/**
 * Persists local service state to disk so the controller can recover
 * running services after a restart. Mirrors the agent's AgentStateStore.
 */
class ControllerStateStore(baseDir: Path) {

    private val logger = LoggerFactory.getLogger(ControllerStateStore::class.java)
    private val stateDir = baseDir.resolve("state")
    private val stateFile = stateDir.resolve("services.json")
    private val tmpFile = stateDir.resolve("services.json.tmp")

    fun load(): ControllerState {
        if (!stateFile.exists()) return ControllerState()
        return try {
            val text = stateFile.readText()
            stateJson.decodeFromString<ControllerState>(text)
        } catch (e: Exception) {
            logger.warn("Failed to load controller state (starting fresh): {}", e.message)
            ControllerState()
        }
    }

    fun save(state: ControllerState) {
        try {
            stateDir.createDirectories()
            tmpFile.writeText(stateJson.encodeToString(state))
            tmpFile.moveTo(stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.error("Failed to save controller state: {}", e.message)
        }
    }

    fun addService(service: PersistedLocalService) {
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
