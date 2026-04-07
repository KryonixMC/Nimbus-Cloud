package dev.nimbuspowered.nimbus.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI configuration stored at ~/.nimbus/cli.json.
 * Supports named connection profiles.
 */
@Serializable
data class CliConfig(
    val defaultProfile: String = "default",
    val profiles: Map<String, ConnectionProfile> = mapOf(
        "default" to ConnectionProfile()
    )
) {
    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
        private val configDir = Path.of(System.getProperty("user.home"), ".nimbus")
        private val configFile = configDir.resolve("cli.json")

        fun load(): CliConfig {
            if (!Files.exists(configFile)) return CliConfig()
            return try {
                json.decodeFromString(Files.readString(configFile))
            } catch (e: Exception) {
                System.err.println("Warning: Could not parse ${configFile}: ${e.message}")
                CliConfig()
            }
        }

        fun save(config: CliConfig) {
            Files.createDirectories(configDir)
            Files.writeString(configFile, json.encodeToString(config))
        }
    }
}

@Serializable
data class ConnectionProfile(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val token: String = ""
)
