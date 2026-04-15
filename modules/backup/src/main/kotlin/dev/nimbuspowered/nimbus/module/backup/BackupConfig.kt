package dev.nimbuspowered.nimbus.module.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ── Data Models ─────────────────────────────────────────

@Serializable
data class BackupModuleConfig(
    val enabled: Boolean = true,
    @SerialName("localDestination") val localDestination: String = "data/backups",
    @SerialName("maxConcurrent") val maxConcurrent: Int = 2,
    @SerialName("compressionLevel") val compressionLevel: Int = 3,
    @SerialName("compressionWorkers") val compressionWorkers: Int = 0,
    @SerialName("quiesceServices") val quiesceServices: Boolean = true,
    @SerialName("quiesceWaitSeconds") val quiesceWaitSeconds: Int = 2,
    val scope: ScopeConfig = ScopeConfig(),
    val excludes: List<String> = defaultExcludes,
    val schedules: List<ScheduleEntry> = emptyList(),
    val retention: RetentionConfig = RetentionConfig()
) {
    companion object {
        val defaultExcludes = listOf(
            "logs/**", "crash-reports/**", "*.log", "*.log.gz",
            "cache/**", "tmp/**", "*.lock", "session.lock",
            "*/region/*.mca.tmp", "config/bStats/**", "plugins/bStats/**"
        )
    }
}

@Serializable
data class ScopeConfig(
    val services: Boolean = true,
    val dedicated: Boolean = true,
    val templates: Boolean = true,
    @SerialName("controllerConfig") val controllerConfig: Boolean = true,
    @SerialName("stateSync") val stateSync: Boolean = true,
    val database: Boolean = true
)

@Serializable
data class ScheduleEntry(
    val name: String,
    val cron: String,
    @SerialName("retentionClass") val retentionClass: String,
    val targets: List<String>
)

@Serializable
data class RetentionConfig(
    @SerialName("hourlyKeep") val hourlyKeep: Int = 24,
    @SerialName("dailyKeep") val dailyKeep: Int = 7,
    @SerialName("weeklyKeep") val weeklyKeep: Int = 4,
    @SerialName("monthlyKeep") val monthlyKeep: Int = 3,
    @SerialName("keepManual") val keepManual: Boolean = true
)

// ── Config Manager ──────────────────────────────────────

class BackupConfigManager(private val configDir: Path) {

    private val logger = LoggerFactory.getLogger(BackupConfigManager::class.java)

    @Volatile
    private var current: BackupModuleConfig = BackupModuleConfig()

    fun init() {
        if (!configDir.exists()) configDir.createDirectories()
        ensureDefaultConfig()
        reload()
    }

    fun getConfig(): BackupModuleConfig = current

    fun ensureDefaultConfig() {
        val file = configDir.resolve("backup.toml")
        if (file.exists()) return
        file.writeText(DEFAULT_TOML)
        logger.info("Generated default backup config at {}", file)
    }

    fun reload() {
        val file = configDir.resolve("backup.toml")
        if (!file.exists()) {
            current = BackupModuleConfig()
            return
        }
        try {
            current = parse(file.readText())
            logger.info("Loaded backup config: {} schedule(s)", current.schedules.size)
        } catch (e: Exception) {
            logger.error("Failed to parse backup config, using defaults: {}", e.message)
            current = BackupModuleConfig()
        }
    }

    /**
     * Validate + atomically persist a new config to disk, then hot-reload.
     * Throws [IllegalArgumentException] on validation errors so callers can
     * return a 400 without the UI blowing up the in-memory state.
     */
    @Synchronized
    fun update(cfg: BackupModuleConfig) {
        validate(cfg)
        val file = configDir.resolve("backup.toml")
        if (!configDir.exists()) configDir.createDirectories()
        val tmp = configDir.resolve("backup.toml.tmp")
        tmp.writeText(render(cfg))
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        current = cfg
        logger.info("Backup config updated: {} schedule(s)", cfg.schedules.size)
    }

    private fun validate(cfg: BackupModuleConfig) {
        require(cfg.compressionLevel in 1..22) {
            "compressionLevel must be 1..22 (got ${cfg.compressionLevel})"
        }
        require(cfg.compressionWorkers in 0..64) {
            "compressionWorkers must be 0..64 (got ${cfg.compressionWorkers})"
        }
        require(cfg.maxConcurrent in 1..32) {
            "maxConcurrent must be 1..32 (got ${cfg.maxConcurrent})"
        }
        require(cfg.quiesceWaitSeconds in 0..60) {
            "quiesceWaitSeconds must be 0..60 (got ${cfg.quiesceWaitSeconds})"
        }
        require(cfg.retention.hourlyKeep >= 0) { "hourlyKeep must be >= 0" }
        require(cfg.retention.dailyKeep >= 0) { "dailyKeep must be >= 0" }
        require(cfg.retention.weeklyKeep >= 0) { "weeklyKeep must be >= 0" }
        require(cfg.retention.monthlyKeep >= 0) { "monthlyKeep must be >= 0" }
        val names = mutableSetOf<String>()
        for (s in cfg.schedules) {
            require(s.name.isNotBlank()) { "Schedule name must not be blank" }
            require(s.name.matches(Regex("[A-Za-z0-9_-]+"))) {
                "Schedule name '${s.name}' — only A-Z, a-z, 0-9, _ and - allowed"
            }
            require(names.add(s.name)) { "Duplicate schedule name '${s.name}'" }
            require(s.retentionClass.lowercase() in setOf("hourly", "daily", "weekly", "monthly", "manual")) {
                "Schedule '${s.name}' has invalid retentionClass '${s.retentionClass}'"
            }
            // Cron sanity: constructing the parser is the validation.
            try { CronExpression(s.cron) }
            catch (e: Exception) {
                throw IllegalArgumentException("Schedule '${s.name}' has invalid cron '${s.cron}': ${e.message}")
            }
            require(s.targets.isNotEmpty()) { "Schedule '${s.name}' must have at least one target" }
            val allowed = setOf("all", "services", "dedicated", "templates", "config", "controller_config", "database", "state", "state_sync")
            for (t in s.targets) {
                require(t.lowercase() in allowed) {
                    "Schedule '${s.name}' has unknown target '$t' (allowed: ${allowed.joinToString(", ")})"
                }
            }
        }
    }

    /**
     * Deterministic TOML renderer — matches the layout produced by
     * [DEFAULT_TOML] so the on-disk file stays readable after edits from
     * either the CLI (hand-edit) or the API.
     */
    private fun render(c: BackupModuleConfig): String {
        val sb = StringBuilder()
        sb.appendLine("[backup]")
        sb.appendLine("enabled = ${c.enabled}")
        sb.appendLine("local_destination = ${tomlStr(c.localDestination)}")
        sb.appendLine("max_concurrent = ${c.maxConcurrent}")
        sb.appendLine("compression_level = ${c.compressionLevel}")
        sb.appendLine("# 0 = auto (Runtime.availableProcessors() / 2, min 1). zstd-jni runs native")
        sb.appendLine("# multi-threaded compression when workers > 0 — this is the 3–5× speedup")
        sb.appendLine("# over subprocess `tar --zstd`.")
        sb.appendLine("compression_workers = ${c.compressionWorkers}")
        sb.appendLine("quiesce_services = ${c.quiesceServices}")
        sb.appendLine("quiesce_wait_seconds = ${c.quiesceWaitSeconds}")
        sb.appendLine()
        sb.appendLine("[backup.scope]")
        sb.appendLine("services = ${c.scope.services}")
        sb.appendLine("dedicated = ${c.scope.dedicated}")
        sb.appendLine("templates = ${c.scope.templates}")
        sb.appendLine("controller_config = ${c.scope.controllerConfig}")
        sb.appendLine("state_sync = ${c.scope.stateSync}")
        sb.appendLine("database = ${c.scope.database}")
        sb.appendLine()
        sb.appendLine("[backup.excludes]")
        if (c.excludes.isEmpty()) {
            sb.appendLine("patterns = []")
        } else {
            sb.appendLine("patterns = [")
            for ((i, p) in c.excludes.withIndex()) {
                val comma = if (i < c.excludes.size - 1) "," else ""
                sb.appendLine("  ${tomlStr(p)}$comma")
            }
            sb.appendLine("]")
        }
        sb.appendLine()
        for (s in c.schedules) {
            sb.appendLine("[[backup.schedules]]")
            sb.appendLine("name = ${tomlStr(s.name)}")
            sb.appendLine("cron = ${tomlStr(s.cron)}")
            sb.appendLine("retention_class = ${tomlStr(s.retentionClass)}")
            sb.appendLine("targets = [${s.targets.joinToString(", ") { tomlStr(it) }}]")
            sb.appendLine()
        }
        sb.appendLine("[backup.retention]")
        sb.appendLine("hourly_keep = ${c.retention.hourlyKeep}")
        sb.appendLine("daily_keep = ${c.retention.dailyKeep}")
        sb.appendLine("weekly_keep = ${c.retention.weeklyKeep}")
        sb.appendLine("monthly_keep = ${c.retention.monthlyKeep}")
        sb.appendLine("keep_manual = ${c.retention.keepManual}")
        return sb.toString()
    }

    private fun tomlStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    // ── TOML Parsing (regex-based, same pattern as SmartScalingConfigManager) ──

    private fun parse(content: String): BackupModuleConfig {
        val backup = sectionBlock(content, "backup") ?: return BackupModuleConfig()

        val enabled = extractBool(backup, "enabled") ?: true
        val localDest = extractStr(backup, "local_destination") ?: "data/backups"
        val maxConcurrent = extractInt(backup, "max_concurrent") ?: 2
        val compressionLevel = extractInt(backup, "compression_level") ?: 3
        val compressionWorkers = extractInt(backup, "compression_workers") ?: 0
        val quiesceServices = extractBool(backup, "quiesce_services") ?: true
        val quiesceWaitSeconds = extractInt(backup, "quiesce_wait_seconds") ?: 2

        val scopeBlock = sectionBlock(content, "backup.scope")
        val scope = if (scopeBlock != null) ScopeConfig(
            services = extractBool(scopeBlock, "services") ?: true,
            dedicated = extractBool(scopeBlock, "dedicated") ?: true,
            templates = extractBool(scopeBlock, "templates") ?: true,
            controllerConfig = extractBool(scopeBlock, "controller_config") ?: true,
            stateSync = extractBool(scopeBlock, "state_sync") ?: true,
            database = extractBool(scopeBlock, "database") ?: true
        ) else ScopeConfig()

        val excludesBlock = sectionBlock(content, "backup.excludes")
        val excludes = excludesBlock?.let { extractStringArray(it, "patterns") }
            ?: BackupModuleConfig.defaultExcludes

        val retentionBlock = sectionBlock(content, "backup.retention")
        val retention = if (retentionBlock != null) RetentionConfig(
            hourlyKeep = extractInt(retentionBlock, "hourly_keep") ?: 24,
            dailyKeep = extractInt(retentionBlock, "daily_keep") ?: 7,
            weeklyKeep = extractInt(retentionBlock, "weekly_keep") ?: 4,
            monthlyKeep = extractInt(retentionBlock, "monthly_keep") ?: 3,
            keepManual = extractBool(retentionBlock, "keep_manual") ?: true
        ) else RetentionConfig()

        val schedules = parseSchedules(content)

        return BackupModuleConfig(
            enabled, localDest, maxConcurrent, compressionLevel, compressionWorkers,
            quiesceServices, quiesceWaitSeconds, scope, excludes, schedules, retention
        )
    }

    private fun parseSchedules(content: String): List<ScheduleEntry> {
        val out = mutableListOf<ScheduleEntry>()
        val blockRegex = Regex(
            """\[\[backup\.schedules]]\s*\n([\s\S]*?)(?=\n\[\[|\n\[(?!\[)|\z)"""
        )
        for (m in blockRegex.findAll(content)) {
            val block = m.groupValues[1]
            val name = extractStr(block, "name") ?: continue
            val cron = extractStr(block, "cron") ?: continue
            val retentionClass = extractStr(block, "retention_class") ?: "manual"
            val targets = extractStringArray(block, "targets") ?: listOf("all")
            out.add(ScheduleEntry(name, cron, retentionClass, targets))
        }
        return out
    }

    // ── Regex helpers ──────────────────────────────────

    private fun sectionBlock(content: String, section: String): String? {
        val escaped = section.replace(".", "\\.")
        val regex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        return regex.find(content)?.groupValues?.get(1)
    }

    private fun extractStr(block: String, key: String): String? =
        Regex("""^\s*$key\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
            .find(block)?.groupValues?.get(1)

    private fun extractBool(block: String, key: String): Boolean? =
        Regex("""^\s*$key\s*=\s*(true|false)\s*$""", RegexOption.MULTILINE)
            .find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun extractInt(block: String, key: String): Int? =
        Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
            .find(block)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractStringArray(block: String, key: String): List<String>? {
        // Match single-line or multi-line array: key = [ "a", "b", ... ]
        val regex = Regex("""^\s*$key\s*=\s*\[([\s\S]*?)]""", RegexOption.MULTILINE)
        val inner = regex.find(block)?.groupValues?.get(1) ?: return null
        val items = Regex(""""([^"]*)"""").findAll(inner).map { it.groupValues[1] }.toList()
        return items.ifEmpty { null }
    }

    companion object {
        val DEFAULT_TOML = """
            |[backup]
            |enabled = true
            |local_destination = "data/backups"
            |max_concurrent = 2
            |compression_level = 3
            |# 0 = auto (Runtime.availableProcessors() / 2, min 1). zstd-jni runs native
            |# multi-threaded compression when workers > 0 — this is the 3–5× speedup
            |# over subprocess `tar --zstd`.
            |compression_workers = 0
            |quiesce_services = true
            |quiesce_wait_seconds = 2
            |
            |[backup.scope]
            |services = true
            |dedicated = true
            |templates = true
            |controller_config = true
            |state_sync = true
            |database = true
            |
            |[backup.excludes]
            |patterns = [
            |  "logs/**", "crash-reports/**", "*.log", "*.log.gz",
            |  "cache/**", "tmp/**", "*.lock", "session.lock",
            |  "*/region/*.mca.tmp", "config/bStats/**", "plugins/bStats/**"
            |]
            |
            |[[backup.schedules]]
            |name = "hourly"
            |cron = "0 * * * *"
            |retention_class = "hourly"
            |targets = ["services", "dedicated", "database"]
            |
            |[[backup.schedules]]
            |name = "daily"
            |cron = "0 3 * * *"
            |retention_class = "daily"
            |targets = ["all"]
            |
            |[[backup.schedules]]
            |name = "weekly"
            |cron = "0 4 * * 0"
            |retention_class = "weekly"
            |targets = ["all"]
            |
            |[backup.retention]
            |hourly_keep = 24
            |daily_keep = 7
            |weekly_keep = 4
            |monthly_keep = 3
            |keep_manual = true
            |
        """.trimMargin()
    }
}
