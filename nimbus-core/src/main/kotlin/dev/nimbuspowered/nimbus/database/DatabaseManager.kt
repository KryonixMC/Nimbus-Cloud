package dev.nimbuspowered.nimbus.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.nimbuspowered.nimbus.config.DatabaseConfig
import dev.nimbuspowered.nimbus.database.migrations.V1_Baseline
import dev.nimbuspowered.nimbus.database.migrations.V2_AuditLog
import dev.nimbuspowered.nimbus.database.migrations.V3_CliSessions
import dev.nimbuspowered.nimbus.database.migrations.V4_ServiceMetricSamples
import dev.nimbuspowered.nimbus.database.migrations.V5_TimestampColumnWidth
import dev.nimbuspowered.nimbus.database.migrations.V6_IndexesAndColumnFixes
import dev.nimbuspowered.nimbus.module.api.Migration
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
class DatabaseInitException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class DatabaseManager(private val baseDir: Path, private val config: DatabaseConfig) {

    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)
    lateinit var database: Database
        private set

    private var dataSource: HikariDataSource? = null

    private val migrationManager by lazy { MigrationManager(database) }

    /** Core migrations bundled with nimbus-core. */
    private val coreMigrations: List<Migration> = listOf(
        V1_Baseline,
        V2_AuditLog,
        V3_CliSessions,
        V4_ServiceMetricSamples,
        V5_TimestampColumnWidth,
        V6_IndexesAndColumnFixes,
    )

    fun init() {
        try {
            database = when (config.type.lowercase()) {
                "sqlite" -> connectSqlite()
                "mysql", "mariadb" -> connectMysql()
                "postgresql", "postgres" -> connectPostgresql()
                else -> {
                    logger.warn("Unknown database type '{}', falling back to SQLite", config.type)
                    connectSqlite()
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to connect to database: {}", e.message)
            logger.error("Check your database configuration in config/nimbus.toml under [database]")
            throw DatabaseInitException("Failed to connect to database: ${e.message}", e)
        }

        // Initialize migration tracking table
        migrationManager.init()

        logger.info("Database initialized ({})", config.type.lowercase())
    }

    /**
     * Runs all pending migrations (core + module).
     * Called from Nimbus.kt after modules have registered their migrations.
     *
     * @param moduleMigrations migrations registered by modules via ModuleContext.registerMigrations()
     */
    fun runMigrations(moduleMigrations: List<Migration> = emptyList()) {
        val allMigrations = coreMigrations + moduleMigrations

        // Only mark actual baselines (pre-existing tables) as applied on upgrade
        val baselineVersions = allMigrations.filter { it.baseline }.map { it.version }
        migrationManager.bootstrap(baselineVersions)

        val applied = migrationManager.runPending(allMigrations)
        if (applied > 0) {
            logger.info("Applied {} migration(s)", applied)
        }
    }

    private fun connectSqlite(): Database {
        val dataDir = baseDir.resolve("data")
        if (!dataDir.exists()) dataDir.createDirectories()
        val dbPath = dataDir.resolve("nimbus.db")

        return Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA busy_timeout=5000")
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
            }
        )
    }

    private fun connectMysql(): Database {
        val ds = createPooledDataSource(
            url = "jdbc:mysql://${config.host}:${config.port}/${config.name}?createDatabaseIfNotExist=true&useSSL=true&requireSSL=true&allowPublicKeyRetrieval=false",
            driver = "com.mysql.cj.jdbc.Driver",
            user = config.username,
            password = config.password
        )
        dataSource = ds
        return Database.connect(ds)
    }

    private fun connectPostgresql(): Database {
        if (config.port == 3306) {
            throw IllegalArgumentException(
                "PostgreSQL is configured with MySQL default port 3306. " +
                    "Set the correct port (default: 5432) in [database] config."
            )
        }
        val ds = createPooledDataSource(
            url = "jdbc:postgresql://${config.host}:${config.port}/${config.name}?sslmode=require",
            driver = "org.postgresql.Driver",
            user = config.username,
            password = config.password
        )
        dataSource = ds
        return Database.connect(ds)
    }

    private fun createPooledDataSource(url: String, driver: String, user: String, password: String): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = url
            driverClassName = driver
            username = user
            this.password = password
            maximumPoolSize = config.poolSize
            minimumIdle = 2
            connectionTimeout = 30_000
            validationTimeout = 5_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            connectionTestQuery = "SELECT 1"
        }
        return HikariDataSource(hikariConfig)
    }

    fun close() {
        dataSource?.close()
        logger.info("Database connection pool closed")
    }

    suspend fun <T> query(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    @Deprecated(
        message = "Use Migration interface and registerMigrations() instead. Will be removed in 0.3.0.",
        replaceWith = ReplaceWith("context.registerMigrations(listOf(...))")
    )
    fun createTables(vararg tables: org.jetbrains.exposed.sql.Table) {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(*tables)
        }
    }
}
