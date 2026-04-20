package dev.nimbuspowered.nimbus.module.backup

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator

class BackupRetentionTest {

    private lateinit var db: Database
    private lateinit var dest: Path
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        dest = Files.createTempDirectory("retention-dest-")
        // Plain file-backed SQLite — Exposed opens multiple connections, and
        // `:memory:` / shared-cache URIs give each one a private DB, which
        // makes `SchemaUtils.create` land in one connection while inserts hit
        // a different empty one.
        dbFile = Files.createTempFile("nimbus-retention-", ".db")
        db = Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Backups)
        }
    }

    @AfterEach
    fun tearDown() {
        if (Files.exists(dest)) {
            Files.walk(dest).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        if (::dbFile.isInitialized) Files.deleteIfExists(dbFile)
    }

    @Test
    fun `keeps newest N daily per target, deletes rest`() = runBlocking {
        insertBackups("services", "Lobby", "daily", count = 10)
        val ret = BackupRetention(db, dest) { RetentionConfig(dailyKeep = 3, keepManual = true) }
        val result = ret.prune(dryRun = false)
        // 10 - 3 = 7 deleted (file doesn't actually exist — path empty — so no FS work)
        assertEquals(7, result.deleted)
        transaction(db) {
            assertEquals(3, Backups.selectAll().count().toInt())
        }
    }

    @Test
    fun `keepManual makes manual backups immune`() = runBlocking {
        insertBackups("services", "Lobby", "manual", count = 50)
        val ret = BackupRetention(db, dest) { RetentionConfig(keepManual = true) }
        val result = ret.prune(dryRun = false)
        assertEquals(0, result.deleted)
        transaction(db) {
            assertEquals(50, Backups.selectAll().count().toInt())
        }
    }

    @Test
    fun `weekly and monthly have independent budgets`() = runBlocking {
        insertBackups("services", "A", "weekly", count = 8)
        insertBackups("services", "A", "monthly", count = 6)
        val ret = BackupRetention(db, dest) {
            RetentionConfig(weeklyKeep = 4, monthlyKeep = 3, keepManual = true)
        }
        ret.prune(dryRun = false)
        transaction(db) {
            val weekly = Backups.selectAll().where { Backups.scheduleClass eq "weekly" }.count().toInt()
            val monthly = Backups.selectAll().where { Backups.scheduleClass eq "monthly" }.count().toInt()
            assertEquals(4, weekly)
            assertEquals(3, monthly)
        }
    }

    @Test
    fun `dryRun does not delete`() = runBlocking {
        insertBackups("services", "X", "daily", count = 10)
        val ret = BackupRetention(db, dest) { RetentionConfig(dailyKeep = 2) }
        val result = ret.prune(dryRun = true)
        assertEquals(8, result.deleted)
        transaction(db) {
            assertEquals(10, Backups.selectAll().count().toInt())
        }
    }

    @Test
    fun `FAILED rows age-pruned beyond failedKeepDays`() = runBlocking {
        val oldIso = Instant.now().minusSeconds(60L * 60 * 24 * 30).toString() // 30 days ago
        val newIso = Instant.now().toString()
        transaction(db) {
            repeat(5) {
                Backups.insert {
                    it[targetType] = "SERVICE"
                    it[targetName] = "A"
                    it[scheduleClass] = "daily"
                    it[scheduleName] = "s"
                    it[startedAt] = oldIso
                    it[status] = "FAILED"
                    it[archivePath] = ""
                    it[sizeBytes] = 0
                }
            }
            repeat(2) {
                Backups.insert {
                    it[targetType] = "SERVICE"
                    it[targetName] = "A"
                    it[scheduleClass] = "daily"
                    it[scheduleName] = "s"
                    it[startedAt] = newIso
                    it[status] = "FAILED"
                    it[archivePath] = ""
                    it[sizeBytes] = 0
                }
            }
        }
        val ret = BackupRetention(db, dest) { RetentionConfig(failedKeepDays = 7) }
        ret.prune(dryRun = false)
        transaction(db) {
            val remaining = Backups.selectAll().where { Backups.status eq "FAILED" }.count().toInt()
            assertEquals(2, remaining, "only the 2 recent FAILED rows should remain")
        }
    }

    private fun insertBackups(type: String, name: String, cls: String, count: Int) {
        transaction(db) {
            val base = Instant.now().toEpochMilli()
            for (i in 0 until count) {
                // startedAt lexically sorts correctly when all are same format — use Instant with variance
                val iso = Instant.ofEpochMilli(base - i.toLong() * 1_000).toString()
                Backups.insert {
                    it[targetType] = type
                    it[targetName] = name
                    it[scheduleClass] = cls
                    it[scheduleName] = "s"
                    it[startedAt] = iso
                    it[status] = "SUCCESS"
                    it[archivePath] = ""
                    it[sizeBytes] = 1_024L
                }
            }
        }
    }

}
