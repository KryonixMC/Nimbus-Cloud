package dev.nimbuspowered.nimbus.module.backup

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ── Data Models ─────────────────────────────────────────

data class BackupTarget(
    val id: String,
    val type: String,          // "service"|"group"|"directory"
    val path: String,
    val schedule: String?,     // cron expression or null for manual-only
    val retentionCount: Int
)

data class BackupGlobalConfig(
    val enabled: Boolean = true,
    val backupDir: String = "backups",
    val maxConcurrent: Int = 2,
    val retentionCount: Int = 10
)

data class BackupConfig(
    val global: BackupGlobalConfig,
    val targets: List<BackupTarget>
)

// ── Config Manager ──────────────────────────────────────

class BackupConfigManager(private val configDir: Path) {

    private val logger = LoggerFactory.getLogger(BackupConfigManager::class.java)
    private var config = defaultConfig()

    fun init() {
        if (!configDir.exists()) configDir.createDirectories()
        val configFile = configDir.resolve("nimbus.toml")
        if (!configFile.exists()) {
            configFile.writeText(defaultConfigToml())
            logger.info("Generated default backup config")
        }
        reload()
    }

    fun reload() {
        val configFile = configDir.resolve("nimbus.toml")
        if (!configFile.exists()) {
            config = defaultConfig()
            return
        }
        try {
            config = parseConfig(configFile.readText())
            logger.info("Loaded backup config ({} targets)", config.targets.size)
        } catch (e: Exception) {
            logger.warn("Failed to load backup config, using defaults", e)
            config = defaultConfig()
        }
    }

    fun getConfig(): BackupConfig = config

    // ── Parsing ──────────────────────────────────────────

    private fun parseConfig(content: String): BackupConfig {
        val enabled = extractBoolean(content, "enabled", "global") ?: true
        val backupDir = extractString(content, "backup_dir", "global") ?: "backups"
        val maxConcurrent = extractInt(content, "max_concurrent", "global") ?: 2
        val retentionCount = extractInt(content, "retention_count", "global") ?: 10

        val global = BackupGlobalConfig(
            enabled = enabled,
            backupDir = backupDir,
            maxConcurrent = maxConcurrent.coerceAtLeast(1),
            retentionCount = retentionCount.coerceAtLeast(1)
        )

        val targets = parseTargets(content, global.retentionCount)
        return BackupConfig(global, targets)
    }

    private fun parseTargets(content: String, defaultRetention: Int): List<BackupTarget> {
        val targets = mutableListOf<BackupTarget>()
        val blockRegex = Regex(
            """\[\[targets]]\s*\n([\s\S]*?)(?=\n\[\[|\n\[(?!\[)|\z)"""
        )

        for (match in blockRegex.findAll(content)) {
            val block = match.groupValues[1]
            val id = extractStringFromBlock(block, "id") ?: continue
            val type = extractStringFromBlock(block, "type") ?: "directory"
            val path = extractStringFromBlock(block, "path") ?: continue
            val schedule = extractStringFromBlock(block, "schedule")
            val retentionCount = extractIntFromBlock(block, "retention_count") ?: defaultRetention

            targets.add(
                BackupTarget(
                    id = id,
                    type = type,
                    path = path,
                    schedule = schedule,
                    retentionCount = retentionCount.coerceAtLeast(1)
                )
            )
        }
        return targets
    }

    // ── TOML helpers ─────────────────────────────────────

    private fun extractString(content: String, key: String, section: String): String? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        return extractStringFromBlock(sectionContent, key)
    }

    private fun extractBoolean(content: String, key: String, section: String): Boolean? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*(true|false)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractInt(content: String, key: String, section: String): Int? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        return extractIntFromBlock(sectionContent, key)
    }

    private fun extractStringFromBlock(block: String, key: String): String? {
        val regex = Regex("""^\s*$key\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        return regex.find(block)?.groupValues?.get(1)
    }

    private fun extractIntFromBlock(block: String, key: String): Int? {
        val regex = Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
        return regex.find(block)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ── Defaults ─────────────────────────────────────────

    private fun defaultConfig() = BackupConfig(
        global = BackupGlobalConfig(),
        targets = emptyList()
    )

    private fun defaultConfigToml() = """
        |[global]
        |enabled = true
        |backup_dir = "backups"
        |max_concurrent = 2
        |retention_count = 10
        |
        |# Example target: back up all templates nightly at 03:00
        |[[targets]]
        |id = "templates"
        |type = "directory"
        |path = "templates"
        |schedule = "0 3 * * *"
        |retention_count = 7
        |
        |# Example target: manual-only backup of a specific directory
        |# [[targets]]
        |# id = "custom-data"
        |# type = "directory"
        |# path = "data/custom"
        |# retention_count = 5
        """.trimMargin() + "\n"
}
