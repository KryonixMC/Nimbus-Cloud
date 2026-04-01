package dev.kryonix.nimbus.database

import dev.kryonix.nimbus.config.DatabaseConfig
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

class DatabaseManager(private val baseDir: Path, private val config: DatabaseConfig) {

    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)
    private lateinit var database: Database

    fun init() {
        database = when (config.type.lowercase()) {
            "sqlite" -> connectSqlite()
            "mysql", "mariadb" -> connectMysql()
            "postgresql", "postgres" -> connectPostgresql()
            else -> {
                logger.warn("Unknown database type '{}', falling back to SQLite", config.type)
                connectSqlite()
            }
        }

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                PermissionGroups, GroupPermissions, GroupParents,
                Players, PlayerGroups,
                GroupMeta, PlayerMeta,
                GroupPermissionContexts, PlayerGroupContexts,
                PermissionTracks, PermissionAuditLog,
                ServiceEvents, ScalingEvents, PlayerSessions
            )
        }

        logger.info("Database initialized ({})", config.type.lowercase())
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
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
            }
        )
    }

    private fun connectMysql(): Database {
        return Database.connect(
            url = "jdbc:mysql://${config.host}:${config.port}/${config.name}?createDatabaseIfNotExist=true&useSSL=true&requireSSL=true&allowPublicKeyRetrieval=false",
            driver = "com.mysql.cj.jdbc.Driver",
            user = config.username,
            password = config.password
        )
    }

    private fun connectPostgresql(): Database {
        val port = if (config.port == 3306) 5432 else config.port
        return Database.connect(
            url = "jdbc:postgresql://${config.host}:$port/${config.name}?sslmode=prefer",
            driver = "org.postgresql.Driver",
            user = config.username,
            password = config.password
        )
    }

    suspend fun <T> query(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
