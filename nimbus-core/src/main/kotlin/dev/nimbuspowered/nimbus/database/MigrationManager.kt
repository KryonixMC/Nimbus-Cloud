package dev.nimbuspowered.nimbus.database

import dev.nimbuspowered.nimbus.database.ServiceEvents
import dev.nimbuspowered.nimbus.module.Migration
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Tracks and applies versioned database migrations.
 *
 * On first run against a fresh database, all migrations are executed in order.
 * On first run against an existing (pre-migration) database, [bootstrap] detects
 * existing tables and marks baseline migrations as already applied.
 */
class MigrationManager(private val database: Database) {

    private val logger = LoggerFactory.getLogger(MigrationManager::class.java)

    /** Ensures the schema_migrations tracking table exists. */
    fun init() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(SchemaMigrations)
        }
    }

    /**
     * Detects an existing (pre-0.2.0) database and marks baseline migrations as applied.
     * Called once before running pending migrations.
     *
     * Detection: if `service_events` table exists but `schema_migrations` is empty,
     * this is an upgrade from a pre-migration version.
     */
    fun bootstrap(baselineVersions: List<Int>) {
        transaction(database) {
            val hasApplied = SchemaMigrations.selectAll().count() > 0
            if (hasApplied) return@transaction

            // Check if this is an existing database (has core tables)
            val hasExistingData = ServiceEvents.exists()

            if (hasExistingData && baselineVersions.isNotEmpty()) {
                logger.info("Existing database detected — marking {} baseline migrations as applied", baselineVersions.size)
                val now = Instant.now().toString()
                for (version in baselineVersions) {
                    SchemaMigrations.insert {
                        it[SchemaMigrations.version] = version
                        it[appliedAt] = now
                        it[description] = "baseline (pre-0.2.0)"
                    }
                }
            }
        }
    }

    /**
     * Runs all pending migrations in version order.
     * Also detects and repairs incorrectly bootstrapped migrations
     * (marked as applied but table doesn't actually exist).
     * Returns the number of migrations applied.
     */
    fun runPending(migrations: List<Migration>): Int {
        if (migrations.isEmpty()) return 0

        // Repair: remove entries for non-baseline migrations that were incorrectly
        // marked as applied by a previous buggy bootstrap (pre-fix for baseline flag)
        transaction(database) {
            val appliedRows = SchemaMigrations.selectAll().map {
                it[SchemaMigrations.version] to it[SchemaMigrations.description]
            }
            for ((version, desc) in appliedRows) {
                if (desc == "baseline (pre-0.2.0)") {
                    val migration = migrations.find { it.version == version }
                    if (migration != null && !migration.baseline) {
                        logger.warn("Repairing incorrectly bootstrapped migration V{}: {}", version, migration.description)
                        SchemaMigrations.deleteWhere { SchemaMigrations.version eq version }
                    }
                }
            }
        }

        val applied = transaction(database) {
            SchemaMigrations.selectAll().map { it[SchemaMigrations.version] }.toSet()
        }

        val pending = migrations
            .filter { it.version !in applied }
            .sortedBy { it.version }

        if (pending.isEmpty()) {
            logger.debug("All {} migrations are up to date", migrations.size)
            return 0
        }

        logger.info("Running {} pending migration(s)...", pending.size)

        for (migration in pending) {
            try {
                transaction(database) {
                    migration.run { migrate() }

                    SchemaMigrations.insert {
                        it[version] = migration.version
                        it[appliedAt] = Instant.now().toString()
                        it[description] = migration.description
                    }
                }
                logger.info("  V{}: {} ✓", migration.version, migration.description)
            } catch (e: Exception) {
                logger.error("Migration V{} failed: {}", migration.version, e.message, e)
                throw MigrationException("Migration V${migration.version} (${migration.description}) failed: ${e.message}", e)
            }
        }

        return pending.size
    }
}

/** Tracking table for applied migrations. */
object SchemaMigrations : Table("schema_migrations") {
    val version = integer("version")
    val appliedAt = varchar("applied_at", 30)
    val description = varchar("description", 256)

    override val primaryKey = PrimaryKey(version)
}

class MigrationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
