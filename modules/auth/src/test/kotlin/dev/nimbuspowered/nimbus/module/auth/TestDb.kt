package dev.nimbuspowered.nimbus.module.auth

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.auth.db.DashboardLoginChallenges
import dev.nimbuspowered.nimbus.module.auth.db.DashboardRecoveryCodes
import dev.nimbuspowered.nimbus.module.auth.db.DashboardSessions
import dev.nimbuspowered.nimbus.module.auth.db.DashboardTotp
import dev.nimbuspowered.nimbus.module.auth.db.DashboardWebAuthnCredentials
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private val dbCounter = AtomicInteger(0)

/**
 * Build a DatabaseManager mock backed by a real file-based SQLite inside the
 * JUnit-provided [dir]. JUnit's @TempDir cleans [dir] recursively, so the DB
 * file + any `-wal`/`-journal` siblings vanish between runs.
 */
fun buildAuthTestDb(dir: Path): DatabaseManager {
    val file = dir.resolve("auth-test-${dbCounter.incrementAndGet()}.db")
    val db = Database.connect(
        url = "jdbc:sqlite:$file",
        driver = "org.sqlite.JDBC"
    )
    transaction(db) {
        SchemaUtils.create(
            DashboardSessions,
            DashboardLoginChallenges,
            DashboardTotp,
            DashboardRecoveryCodes,
            DashboardWebAuthnCredentials,
        )
    }
    val dm = mockk<DatabaseManager>(relaxed = true)
    every { dm.database } returns db
    return dm
}
