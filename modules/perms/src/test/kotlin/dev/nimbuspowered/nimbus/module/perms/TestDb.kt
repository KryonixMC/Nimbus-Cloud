package dev.nimbuspowered.nimbus.module.perms

import dev.nimbuspowered.nimbus.database.DatabaseManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private val dbCounter = AtomicInteger(0)

/**
 * DatabaseManager mock backed by a real Exposed Database on a file-based SQLite
 * inside the JUnit-provided [dir]. `db.query { }` is stubbed to run against the
 * real DB under `newSuspendedTransaction`. @TempDir handles cleanup.
 */
fun buildPermsTestDb(dir: Path): DatabaseManager {
    val file = dir.resolve("perms-test-${dbCounter.incrementAndGet()}.db")
    val db = Database.connect("jdbc:sqlite:$file", "org.sqlite.JDBC")
    transaction(db) {
        SchemaUtils.create(
            PermissionGroups, GroupPermissions, GroupParents,
            Players, PlayerGroups,
            GroupMeta, PlayerMeta,
            GroupPermissionContexts, PlayerGroupContexts,
            PermissionTracks, PermissionAuditLog
        )
    }
    val dm = mockk<DatabaseManager>(relaxed = true)
    every { dm.database } returns db
    coEvery { dm.query<Any?>(any()) } coAnswers {
        @Suppress("UNCHECKED_CAST")
        val block = firstArg<Transaction.() -> Any?>()
        newSuspendedTransaction(Dispatchers.IO, db) { block() }
    }
    return dm
}
