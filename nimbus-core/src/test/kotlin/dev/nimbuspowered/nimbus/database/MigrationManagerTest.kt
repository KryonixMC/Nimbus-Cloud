package dev.nimbuspowered.nimbus.database

import dev.nimbuspowered.nimbus.module.Migration
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

class MigrationManagerTest {

    private lateinit var dbFile: Path
    private lateinit var database: Database
    private lateinit var manager: MigrationManager

    @BeforeEach
    fun setUp() {
        // File-backed SQLite so the schema persists across Exposed transactions.
        // Pure :memory: uses a per-connection database that would lose tables
        // between transactions.
        dbFile = Files.createTempFile("nimbus-migration-", ".db")
        database = Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        manager = MigrationManager(database)
        manager.init()
    }

    @AfterEach
    fun tearDown() {
        dbFile.deleteIfExists()
    }

    @Test
    fun `init creates schema_migrations table`() {
        transaction(database) {
            // Selecting from schema_migrations must not throw after init()
            assertEquals(0, SchemaMigrations.selectAll().count())
        }
    }

    @Test
    fun `runPending applies all migrations in version order`() {
        val applied = mutableListOf<Int>()
        val migrations = listOf(
            testMigration(10, "create_a") { applied.add(10); SchemaUtils.create(TableA) },
            testMigration(20, "create_b") { applied.add(20); SchemaUtils.create(TableB) },
            testMigration(15, "create_c") { applied.add(15); SchemaUtils.create(TableC) }
        )

        val count = manager.runPending(migrations)

        assertEquals(3, count)
        // Must run in ascending version order, not list order
        assertEquals(listOf(10, 15, 20), applied)
    }

    @Test
    fun `runPending is idempotent — second run applies zero`() {
        val migrations = listOf(testMigration(1, "create_a") { SchemaUtils.create(TableA) })
        assertEquals(1, manager.runPending(migrations))
        assertEquals(0, manager.runPending(migrations))
    }

    @Test
    fun `runPending skips already-applied migrations when adding new ones`() {
        val first = listOf(testMigration(1, "first") { SchemaUtils.create(TableA) })
        manager.runPending(first)

        var ranSecond = false
        val second = first + testMigration(2, "second") {
            ranSecond = true
            SchemaUtils.create(TableB)
        }
        assertEquals(1, manager.runPending(second))
        assertTrue(ranSecond, "New migration V2 must execute")
    }

    @Test
    fun `failed migration throws and is not recorded`() {
        val migrations = listOf(testMigration(1, "broken") {
            error("intentional migration failure")
        })

        val ex = assertThrows(MigrationException::class.java) {
            manager.runPending(migrations)
        }
        assertTrue(ex.message!!.contains("Migration V1"), "Error must identify failing version: ${ex.message}")

        transaction(database) {
            assertEquals(0, SchemaMigrations.selectAll().count(),
                "Failed migration must not be recorded in schema_migrations")
        }
    }

    @Test
    fun `runPending with empty list returns zero`() {
        assertEquals(0, manager.runPending(emptyList()))
    }

    private fun testMigration(v: Int, desc: String, action: () -> Unit): Migration =
        object : Migration {
            override val version: Int = v
            override val description: String = desc
            override fun org.jetbrains.exposed.sql.Transaction.migrate() {
                action()
            }
        }

    private object TableA : Table("tbl_a") { val id = integer("id"); override val primaryKey = PrimaryKey(id) }
    private object TableB : Table("tbl_b") { val id = integer("id"); override val primaryKey = PrimaryKey(id) }
    private object TableC : Table("tbl_c") { val id = integer("id"); override val primaryKey = PrimaryKey(id) }
}
