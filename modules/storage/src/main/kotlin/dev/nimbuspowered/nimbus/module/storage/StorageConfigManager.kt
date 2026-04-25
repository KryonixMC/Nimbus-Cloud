package dev.nimbuspowered.nimbus.module.storage

import dev.nimbuspowered.nimbus.config.StrictToml
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StorageConfigManager(private val configDir: Path) {

    private val logger = LoggerFactory.getLogger(StorageConfigManager::class.java)
    private val configFile = configDir.resolve("storage.toml")

    var config: StorageConfig = StorageConfig()
        private set

    fun init() {
        configDir.createDirectories()
        if (!configFile.exists()) {
            configFile.writeText(DEFAULT_CONFIG)
            logger.info("Created default storage config at {}", configFile)
        }
        reload()
    }

    fun reload() {
        if (!configFile.exists()) return
        try {
            config = StrictToml.strictDecode(
                StorageConfig.serializer(),
                configFile.readText(),
                "modules/storage/storage.toml",
                strict = false
            )
            logger.debug("Loaded storage config: enabled={}, bucket={}", config.enabled, config.bucket)
        } catch (e: Exception) {
            logger.warn("Failed to load storage config from {}: {}", configFile, e.message)
        }
    }

    companion object {
        private val DEFAULT_CONFIG = """
# Storage module — S3-compatible template sync
# Supported: AWS S3, Cloudflare R2, MinIO, DigitalOcean Spaces, Backblaze B2
enabled = false

# S3-compatible endpoint URL
# AWS S3:              leave empty (auto-detected from region)
# Cloudflare R2:       https://<accountId>.r2.cloudflarestorage.com
# MinIO (local):       http://localhost:9000
# DigitalOcean Spaces: https://<region>.digitaloceanspaces.com
endpoint = ""
region = "us-east-1"
bucket = ""
prefix = "templates"
access_key = ""
secret_key = ""
path_style = false
auto_sync_on_start = false
""".trimIndent()
    }
}
