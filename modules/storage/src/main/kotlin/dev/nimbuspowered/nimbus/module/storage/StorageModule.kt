package dev.nimbuspowered.nimbus.module.storage

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.module.api.ModuleContext
import dev.nimbuspowered.nimbus.module.api.NimbusModule
import dev.nimbuspowered.nimbus.module.storage.driver.S3StorageDriver
import org.slf4j.LoggerFactory

class StorageModule : NimbusModule {
    override val id = "storage"
    override val name = "Storage"
    override val version: String get() = NimbusVersion.version
    override val description = "S3-compatible template sync for multi-node clusters"

    private val logger = LoggerFactory.getLogger(StorageModule::class.java)
    private lateinit var configManager: StorageConfigManager
    private var driver: S3StorageDriver? = null
    private var syncManager: TemplateSyncManager? = null

    override suspend fun init(context: ModuleContext) {
        val configDir = context.moduleConfigDir("storage")
        configManager = StorageConfigManager(configDir)
        configManager.init()

        val cfg = configManager.config
        if (cfg.enabled) {
            if (cfg.bucket.isBlank()) {
                logger.warn("Storage module enabled but no bucket configured — S3 sync disabled")
            } else {
                driver = S3StorageDriver(cfg)
                syncManager = TemplateSyncManager(driver!!, context.templatesDir, cfg)
                context.registerService(TemplateSyncManager::class.java, syncManager!!)
                logger.info("Storage module initialized: bucket={}, endpoint={}",
                    cfg.bucket, cfg.endpoint.ifBlank { "AWS S3 (${cfg.region})" })
            }
        } else {
            logger.info("Storage module loaded (disabled — set enabled = true in storage.toml to activate)")
        }
    }

    override suspend fun enable() {}

    override fun disable() {
        driver?.close()
        driver = null
        syncManager = null
    }
}
